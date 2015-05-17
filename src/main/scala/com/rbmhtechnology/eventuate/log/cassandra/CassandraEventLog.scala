/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate.log.cassandra

import java.lang.{Long => JLong}

import akka.actor._

import com.rbmhtechnology.eventuate._
import com.rbmhtechnology.eventuate.EventsourcingProtocol._
import com.rbmhtechnology.eventuate.ReplicationProtocol._
import com.rbmhtechnology.eventuate.log.{BatchingEventLog, AggregateRegistry}

import scala.collection.immutable.Seq
import scala.language.implicitConversions
import scala.util._

/**
 * An event log actor with [[http://cassandra.apache.org/ Apache Cassandra]] as storage backend. It uses
 * the [[Cassandra]] extension to connect to a Cassandra cluster. Applications should create an instance
 * of this actor using the `props` method of the `CassandraEventLog` [[CassandraEventLog$ companion object]].
 *
 * {{{
 *   val factory: ActorRefFactory = ... // ActorSystem or ActorContext
 *   val logId: String = "example"      // Unique log id
 *
 *   val log = factory.actorOf(CassandraEventLog.props(logId))
 * }}}
 *
 * Each event log actor creates two tables in the configured keyspace (see also [[Cassandra]]). Assuming
 * the following table prefix
 *
 * {{{
 *   eventuate.log.cassandra.table-prefix = "log"
 * }}}
 *
 * and a log `id` with value `example`, the names of these two tables are
 *
 *  - `log_example` which represents the local event log.
 *  - `log_example_agg` which is an index of the local event log for those events that have non-empty
 *    [[DurableEvent#routingDestinations routingDestinations]] set. It is used for fast recovery of
 *    event-sourced actors or views that have an [[Eventsourced#aggregateId aggregateId]] defined.
 *
 * @param id unique log id.
 *
 * @see [[Cassandra]]
 * @see [[DurableEvent]]
 */
class CassandraEventLog(id: String) extends Actor with Stash {
  import CassandraEventLog._

  private val eventStream = context.system.eventStream
  private val cassandra: Cassandra = Cassandra(context.system)

  cassandra.createEventTable(id)
  cassandra.createAggregateEventTable(id)

  private val statement = cassandra.prepareWriteEventBatch(id)

  private val reader = createReader(cassandra, id)
  private val index = createIndex(cassandra, reader, id)

  private var sequenceNrUpdates: Long = 0L
  private var sequenceNr: Long = 0L

  private var defaultRegistry: Set[ActorRef] = Set.empty
  private var aggregateRegistry: AggregateRegistry = AggregateRegistry()

  def initializing: Receive = {
    case Initialize(snr) =>
      sequenceNr = snr
      unstashAll()
      context.become(initialized)
    case other =>
      stash()
  }

  def initialized: Receive = {
    case cmd: GetLastSourceLogReadPosition =>
      index.forward(cmd)
    case cmd @ Replay(_, requestor, Some(emitterAggregateId), _) =>
      aggregateRegistry = aggregateRegistry.add(context.watch(requestor), emitterAggregateId)
      index.forward(cmd)
    case Replay(from, requestor, None, iid) =>
      defaultRegistry = defaultRegistry + context.watch(requestor)

      import cassandra.readDispatcher
      reader.replayAsync(from)(event => requestor ! Replaying(event, iid) ) onComplete {
        case Success(_) => requestor ! ReplaySuccess(iid)
        case Failure(e) => requestor ! ReplayFailure(e, iid)
      }
    case r @ ReplicationRead(from, max, filter, targetLogId) =>
      val sdr = sender()
      eventStream.publish(r)

      import cassandra.readDispatcher
      reader.readAsync(from, max, filter, targetLogId) onComplete {
        case Success(result) =>
          val r = ReplicationReadSuccess(result.events, result.to, targetLogId)
          sdr ! r
          eventStream.publish(r)
        case Failure(cause)  =>
          val r = ReplicationReadFailure(cause.getMessage, targetLogId)
          sdr ! r
          eventStream.publish(r)
      }
    case Write(events, eventsSender, requestor, iid) =>
      val updated = prepareWrite(events)

      Try(write(DurableEventBatch(updated))) match {
        case Success(_) =>
          pushWriteSuccess(updated, eventsSender, requestor, iid)
          publishUpdateNotification(updated)
          requestIndexUpdate()
        case Failure(e) =>
          pushWriteFailure(updated, eventsSender, requestor, iid, e)
      }
    case WriteN(writes) =>
      val updatedWrites = writes.map(w => w.copy(prepareWrite(w.events)))
      val updatedEvents = updatedWrites.map(_.events).flatten

      Try(write(DurableEventBatch(updatedEvents))) match {
        case Success(_) =>
          updatedWrites.foreach(w => pushWriteSuccess(w.events, w.eventsSender, w.requestor, w.instanceId))
          publishUpdateNotification(updatedEvents)
          requestIndexUpdate()
        case Failure(e) =>
          updatedWrites.foreach(w => pushWriteFailure(w.events, w.eventsSender, w.requestor, w.instanceId, e))
      }
      sender() ! WriteNComplete // notify batch layer that write completed
    case r @ ReplicationWrite(Seq(), _, _) =>
      index.forward(r)
    case ReplicationWrite(events, sourceLogId, lastSourceLogSequenceNrRead) =>
      val snr = sequenceNr
      val updated = prepareReplicate(events)

      Try(write(DurableEventBatch(updated, Some(sourceLogId), Some(lastSourceLogSequenceNrRead)))) match {
        case Success(_) =>
          sender() ! ReplicationWriteSuccess(events.size, lastSourceLogSequenceNrRead)
          pushReplicateSuccess(updated)
          publishUpdateNotification(updated)
          requestIndexUpdate()
        case Failure(e) =>
          sender() ! ReplicationWriteFailure(e)
          sequenceNr = snr
      }
    case Terminated(requestor) =>
      aggregateRegistry.aggregateId(requestor) match {
        case Some(aggregateId) => aggregateRegistry = aggregateRegistry.remove(requestor, aggregateId)
        case None              => defaultRegistry = defaultRegistry - requestor
      }
  }

  override def receive =
    initializing

  private[eventuate] def createReader(cassandra: Cassandra, logId: String) =
    new CassandraEventReader(cassandra, logId)

  private[eventuate] def createIndex(cassandra: Cassandra, eventReader: CassandraEventReader, logId: String) =
    context.actorOf(CassandraIndex.props(cassandra, eventReader, logId))

  private[eventuate] def write(batch: DurableEventBatch): Unit =
    cassandra.session.execute(statement.bind(0L: JLong, sequenceNr: JLong, cassandra.eventBatchToByteBuffer(batch)))

  // ---------------------------------------------------------------------------
  //  Notifications for writers, subscribers, listeners and index
  // ---------------------------------------------------------------------------

  private def requestIndexUpdate(): Unit = {
    if (sequenceNrUpdates >= cassandra.config.indexUpdateLimit) {
      index ! CassandraIndex.UpdateIndex()
      sequenceNrUpdates = 0L
    }
  }

  private def publishUpdateNotification(events: Seq[DurableEvent] = Seq()): Unit =
    if (events.nonEmpty) eventStream.publish(Updated(id, events))

  private def pushReplicateSuccess(events: Seq[DurableEvent]): Unit = {
    events.foreach { event =>
      // in any case, notify all default subscribers
      defaultRegistry.foreach(_ ! Written(event))
      // notify subscribers with matching aggregate id
      for {
        aggregateId <- event.routingDestinations
        aggregate <- aggregateRegistry(aggregateId)
      } aggregate ! Written(event)
    }
  }

  private def pushWriteSuccess(events: Seq[DurableEvent], eventsSender: ActorRef, requestor: ActorRef, instanceId: Int): Unit =
    events.foreach { event =>
      requestor.tell(WriteSuccess(event, instanceId), eventsSender)
      // in any case, notify all default subscribers (except requestor)
      defaultRegistry.foreach(r => if (r != requestor) r ! Written(event))
      // notify subscribers with matching aggregate id (except requestor)
      for {
        aggregateId <- event.routingDestinations
        aggregate <- aggregateRegistry(aggregateId) if aggregate != requestor
      } aggregate ! Written(event)
    }

  private def pushWriteFailure(events: Seq[DurableEvent], eventsSender: ActorRef, requestor: ActorRef, instanceId: Int, cause: Throwable): Unit =
    events.foreach { event =>
      requestor.tell(WriteFailure(event, cause, instanceId), eventsSender)
    }

  // ---------------------------------------------------------------------------
  //  ...
  // ---------------------------------------------------------------------------

  private def prepareWrite(events: Seq[DurableEvent]): Seq[DurableEvent] =
    events.map { event =>
      val snr = nextSequenceNr()
      event.copy(
        sourceLogId = id,
        targetLogId = id,
        sourceLogSequenceNr = snr,
        targetLogSequenceNr = snr)
    }

  private def prepareReplicate(events: Seq[DurableEvent]): Seq[DurableEvent] =
    events.map { event =>
      val snr = nextSequenceNr()
      event.copy(
        sourceLogId = event.targetLogId,
        targetLogId = id,
        sourceLogSequenceNr = event.targetLogSequenceNr,
        targetLogSequenceNr = snr)
    }

  private def nextSequenceNr(): Long = {
    sequenceNr += 1L
    sequenceNrUpdates += 1
    sequenceNr
  }

  private[eventuate] def currentSequenceNr: Long =
    sequenceNr
}

object CassandraEventLog {
  private[eventuate] case class Initialize(sequenceNr: Long)

  /**
   * Creates a [[CassandraEventLog]] configuration object.
   *
   * @param logId unique log id.
   * @param batching `true` if write-batching shall be enabled (recommended).
   */
  def props(logId: String, batching: Boolean = true): Props = {
    val logProps = Props(new CassandraEventLog(logId)).withDispatcher("eventuate.log.cassandra.write-dispatcher")
    if (batching) Props(new BatchingEventLog(logProps)) else logProps
  }
}
