/*
 * Copyright (C) 2015 - 2016 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
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

package com.rbmhtechnology.eventuate.serializer

import akka.actor._
import akka.serialization.SerializerWithStringManifest
import akka.serialization.Serializer

import com.rbmhtechnology.eventuate._
import com.typesafe.config.ConfigFactory

import org.scalatest._

object DurableEventSerializerSpec {
  case class ExamplePayload(foo: String, bar: String)

  val serializerConfig = ConfigFactory.parseString(
    """
      |akka.actor.serializers {
      |  eventuate-test = "com.rbmhtechnology.eventuate.serializer.DurableEventSerializerSpec$ExamplePayloadSerializer"
      |}
      |akka.actor.serialization-bindings {
      |  "com.rbmhtechnology.eventuate.serializer.DurableEventSerializerSpec$ExamplePayload" = eventuate-test
      |}
    """.stripMargin)

  val serializerWithStringManifestConfig = ConfigFactory.parseString(
    """
      |akka.actor.serializers {
      |  eventuate-test = "com.rbmhtechnology.eventuate.serializer.DurableEventSerializerSpec$ExamplePayloadSerializerWithStringManifest"
      |}
      |akka.actor.serialization-bindings {
      |  "com.rbmhtechnology.eventuate.serializer.DurableEventSerializerSpec$ExamplePayload" = eventuate-test
      |}
    """.stripMargin)
  /**
   * Swaps `foo` and `bar` of `ExamplePayload`.
   */
  trait SwappingExamplePayloadSerializer {
    def toBinary(o: AnyRef): Array[Byte] = o match {
      case ExamplePayload(foo, bar) => s"${foo}-${bar}".getBytes("UTF-8")
    }

    def _fromBinary(bytes: Array[Byte]): AnyRef = {
      val s = new String(bytes, "UTF-8").split("-")
      ExamplePayload(s(1), s(0))
    }
  }

  class ExamplePayloadSerializer(system: ExtendedActorSystem) extends Serializer with SwappingExamplePayloadSerializer {
    val ExamplePayloadClass = classOf[ExamplePayload]

    override def identifier: Int = 44085
    override def includeManifest: Boolean = true

    override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = manifest.get match {
      case ExamplePayloadClass => _fromBinary(bytes)
    }
  }

  class ExamplePayloadSerializerWithStringManifest(system: ExtendedActorSystem) extends SerializerWithStringManifest with SwappingExamplePayloadSerializer {
    val StringManifest = "manifest"

    override def identifier: Int = 44084

    override def manifest(o: AnyRef) = StringManifest

    override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
      case StringManifest => _fromBinary(bytes)
    }
  }

  val event = DurableEvent(
    payload = ExamplePayload("foo", "bar"),
    emitterId = "r",
    emitterAggregateId = Some("a"),
    customDestinationAggregateIds = Set("x", "y"),
    systemTimestamp = 2L,
    vectorTimestamp = VectorTime("p1" -> 1L, "p2" -> 2L),
    processId = "p4",
    localLogId = "p3",
    localSequenceNr = 17L,
    persistOnEventSequenceNr = Some(12L))
}

class DurableEventSerializerSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  import DurableEventSerializerSpec._

  val context = new SerializationContext(
    LocationConfig.create(),
    LocationConfig.create(customConfig = serializerConfig),
    LocationConfig.create(customConfig = serializerWithStringManifestConfig))

  override def afterAll(): Unit =
    context.shutdown()

  import context._

  "A DurableEventSerializer" must {
    "support default payload serialization" in {
      val expected = event

      serializations(0).deserialize(serializations(0).serialize(event).get, classOf[DurableEvent]).get should be(expected)
    }
    "support custom payload serialization" in serializations.tail.foreach { serialization =>
      val expected = event.copy(ExamplePayload("bar", "foo"))

      serialization.deserialize(serialization.serialize(event).get, classOf[DurableEvent]).get should be(expected)
    }
  }
}
