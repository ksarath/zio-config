package zio.config.refined

import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import zio.config.{BaseSpec, _}
import zio.random.Random
import zio.test.Assertion._
import zio.test._

import ReadError._
import RefinedUtils._

object RefinedSpec extends BaseSpec {
  override def spec: ZSpec[Environment, Failure] =
    suite("Refine package")(
      testM("RefineType can successfully read valid refined values from a given path") {
        check(KeyValue.gen) { keyValue =>
          val cfg =
            refineType[NonEmptyString](keyValue.k.underlying)

          val result =
            read(cfg from ConfigSource.fromMap(Map(keyValue.k.underlying -> keyValue.v.underlying)))

          assert(result)(equalTo(Right(keyValue.v.value)))
        }
      },
      testM("RefineType returns ReadError for invalid values in a given path") {
        check(Key.gen) { key =>
          val cfg = refineType[NonEmptyString](key.underlying)

          val result   =
            read(cfg from ConfigSource.fromMap(Map(key.underlying -> "")))

          val expected =
            ConversionError(List(Step.Key(key.underlying)), "Predicate isEmpty() did not fail.", Set.empty)

          assert(result)(equalTo(Left(expected)))
        }
      }
    )
}

object RefinedUtils {
  final case class Key(value: NonEmptyString) {
    def underlying: String = value.value
  }

  object Key {
    val gen: Gen[Random with Sized, Key] =
      Gen
        .alphaNumericStringBounded(1, 10)
        .map(string => Refined.unsafeApply[String, NonEmpty](string))
        .map(Key.apply)
  }

  final case class Value(value: NonEmptyString) {
    def underlying: String = value.value
  }

  object Value {
    val gen: Gen[Random with Sized, Value] =
      Gen
        .alphaNumericStringBounded(1, 10)
        .map(string => Refined.unsafeApply[String, NonEmpty](string))
        .map(Value.apply)
  }

  final case class KeyValue(k: Key, v: Value)

  object KeyValue {
    val gen: Gen[Random with Sized, KeyValue] =
      for {
        key   <- Key.gen
        value <- Value.gen
      } yield KeyValue(key, value)
  }
}
