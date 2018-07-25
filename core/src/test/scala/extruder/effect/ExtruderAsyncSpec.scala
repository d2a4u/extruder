package extruder.effect

import cats.effect.laws.discipline.AsyncTests
import cats.instances.all._
import org.specs2.specification.core.Fragments

abstract class ExtruderAsyncSpec[F[_], E](implicit F: ExtruderAsync[F]) extends EffectSpec[F, E] {
  override def ruleTest: Fragments = checkAllAsync("ExtruderAsync", implicit ec => AsyncTests[F].async[Int, Int, Int])

}