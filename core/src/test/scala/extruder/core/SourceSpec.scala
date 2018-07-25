package extruder.core

import java.net.URL

import cats.{Eq, Monad}
import cats.data.{NonEmptyList, OptionT, ValidatedNel}
import cats.syntax.either._
import cats.instances.all._
import cats.kernel.laws.discipline.MonoidTests
import extruder.core.TestCommon._
import extruder.core.ValidationCatsInstances._
import extruder.effect.ExtruderMonadError
import org.scalacheck.Gen.Choose
import org.scalacheck.ScalacheckShapeless._
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.specs2.matcher.{EitherMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure
import org.specs2.{ScalaCheck, Specification}
import org.typelevel.discipline.specs2.Discipline

import scala.concurrent.duration.{Duration, FiniteDuration}

trait SourceSpec extends Specification with ScalaCheck with EitherMatchers with Discipline {
  self: Encode
    with Encoders
    with PrimitiveEncoders
    with DerivedEncoders
    with EncodeTypes
    with Decode
    with Decoders
    with DecodeFromDefaultSource
    with PrimitiveDecoders
    with DerivedDecoders
    with DecodeTypes =>

  type Eff[F[_]] = ExtruderMonadError[F]
  override type OutputData = InputData

  val supportsEmptyNamespace: Boolean = true
  def ext: SpecStructure = s2""
  def ext2: SpecStructure = s2""
  def monoidTests: MonoidTests[EncodeData]#RuleSet

  implicit val caseClassEq: Eq[CaseClass] = Eq.fromUniversalEquals
  implicit val urlEq: Eq[URL] = Eq.fromUniversalEquals
  implicit val durationEq: Eq[Duration] = Eq.fromUniversalEquals
  implicit val finiteDurationEq: Eq[FiniteDuration] = Eq.fromUniversalEquals

  lazy val caseClassData: Map[List[String], String] =
    Map(List("CaseClass", "s") -> "string", List("CaseClass", "i") -> "1", List("CaseClass", "l") -> "1").map {
      case (k, v) =>
        if (defaultSettings.includeClassNameInPath) k -> v
        else k.filterNot(_ == "CaseClass") -> v
    }

  val expectedCaseClass = CaseClass("string", 1, 1L, None)

  def convertData(map: Map[List[String], String]): InputData

  override def is: SpecStructure =
    s2"""
       Can decode and encode the following types
        String ${testType(Gen.alphaNumStr.suchThat(_.nonEmpty))}
        Int ${testNumeric[Int]}
        Long ${testNumeric[Long]}
        Double ${testNumeric[Double]}
        Float ${testNumeric[Float]}
        Short ${testNumeric[Short]}
        Byte ${testNumeric[Byte]}
        Boolean ${testType(Gen.oneOf(true, false))}
        URL ${testType(urlGen)}
        Duration ${testType(durationGen)}
        FiniteDuration ${testType(finiteDurationGen)}
        Case class tree ${test(Gen.resultOf(CaseClass))}
        Case class with defaults set $testDefaults
        Tuple ${test(implicitly[Arbitrary[(Int, Long)]].arbitrary)}
        Optional Tuple ${test(Gen.option(implicitly[Arbitrary[(Int, Long)]].arbitrary))}

      Can load data defaults with
        Standard sync decode $testDefaultDecode

      Can represent the following types as a table of required params

      ${checkAll("Encoder monoid", monoidTests)}
      $ext
      $ext2
      """

  def testNumeric[T: Numeric](
    implicit encoder: Enc[Validation, T],
    decoder: Dec[Validation, T],
    listEncoder: Enc[Validation, List[T]],
    listDecoder: Dec[Validation, List[T]],
    tEq: Eq[T],
    choose: Choose[T]
  ): Prop =
    testType(Gen.posNum[T]) ++
      testType(Gen.negNum[T])

  def testType[T](gen: Gen[T])(
    implicit encoder: Enc[Validation, T],
    decoder: Dec[Validation, T],
    listEncoder: Enc[Validation, List[T]],
    listDecoder: Dec[Validation, List[T]],
    tEq: Eq[T]
  ): Prop =
    test(gen) ++
      test(Gen.option(gen)) ++
      testList(Gen.listOf(gen).suchThat(_.nonEmpty)) ++
      testNonEmptyList(gen)

  def testList[T, F[T] <: TraversableOnce[T]](
    gen: Gen[F[T]]
  )(implicit encoder: Enc[Validation, F[T]], decoder: Dec[Validation, F[T]]): Prop =
    Prop.forAll(gen, namespaceGen) { (value, namespace) =>
      (for {
        encoded <- encode[F[T]](namespace, value)
        decoded <- decode[F[T]](namespace, encoded)
      } yield decoded) must beRight.which(_.toList === value.filter(_.toString.trim.nonEmpty).toList)
    }

  def testNonEmptyList[T](
    gen: Gen[T]
  )(implicit encoder: Enc[Validation, NonEmptyList[T]], decoder: Dec[Validation, NonEmptyList[T]]): Prop =
    Prop.forAllNoShrink(nonEmptyListGen(gen), namespaceGen) { (value, namespace) =>
      (for {
        encoded <- encode[NonEmptyList[T]](namespace, value)
        decoded <- decode[NonEmptyList[T]](namespace, encoded)
      } yield decoded) must beRight.which(_.toList === value.filter(_.toString.trim.nonEmpty))
    }

  def test[T](
    gen: Gen[T]
  )(implicit encoder: Enc[Validation, T], decoder: Dec[Validation, T], teq: Eq[T], equals: Eq[Validation[T]]): Prop = {
    val F: Eff[Validation] = ExtruderMonadError[Validation]
    Prop.forAllNoShrink(gen, namespaceGen) { (value, namespace) =>
      def eqv(encoded: Validation[OutputData], decoded: InputData => Validation[T]): Boolean =
        equals.eqv(F.flatMap(encoded)(decoded), Right(value))

      eqv(encode[T](namespace, value), decode[T](namespace, _)) &&
      (if (supportsEmptyNamespace) eqv(encode[T](value), decode[T]) else true)
    }
  }

  def testDefaults: Prop =
    Prop.forAll(Gen.alphaNumStr, Gen.posNum[Int], Gen.posNum[Long])(
      (s, i, l) =>
        decode[CaseClass](
          convertData(
            Map(List("CaseClass", "s") -> s, List("CaseClass", "i") -> i.toString, List("CaseClass", "l") -> l.toString)
              .map {
                case (k, v) =>
                  if (defaultSettings.includeClassNameInPath) k -> v
                  else k.filterNot(_ == "CaseClass") -> v
              }
          )
        ) must beRight(CaseClass(s, i, l, None))
    )

  def testDefaultDecode(implicit cvEq: Eq[Validation[CaseClass]]): Boolean =
    cvEq.eqv(decode[CaseClass], Right(expectedCaseClass)) &&
      cvEq.eqv(decode[CaseClass](List.empty), Right(expectedCaseClass))

  def testCaseClassParams: MatchResult[String] = parameters[CaseClass] !== ""

  implicit def tuple2Parser[F[_]: Monad, A, B](implicit A: Parser[A], B: Parser[B]): MultiParser[F, (A, B)] =
    new MultiParser[F, (A, B)] {
      override def parse(lookup: List[String] => OptionT[F, String]): OptionT[F, ValidatedNel[String, (A, B)]] =
        for {
          _1 <- lookup(List("_1")).map(A.parseNel)
          _2 <- lookup(List("_2")).map(B.parseNel)
        } yield _1.product(_2)
    }

  implicit def tuple2Show[A, B](implicit A: Show[A], B: Show[B]): MultiShow[(A, B)] = new MultiShow[(A, B)] {
    override def show(v: (A, B)): Map[List[String], String] =
      Map(List("_1") -> A.show(v._1), List("_2") -> B.show(v._2))
  }
}