package effekt

private[effekt]
sealed trait MetaCont[-A, +B] extends Serializable {
  def apply(a: A): Result[B]

  def append[C](s: MetaCont[B, C]): MetaCont[A, C]

  def splitAt(c: Prompt): (MetaCont[A, c.Result], MetaCont[c.Result, B])

  def map[C](f: C => A): MetaCont[C, B] = flatMap(x => pure(f(x)))

  def flatMap[C](f: Frame[C, A]): MetaCont[C, B] = FramesCont(List(f), this)
}

private[effekt]
case class CastCont[-A, +B]() extends MetaCont[A, B] {

  final def apply(a: A): Result[B] = Value(a.asInstanceOf[B])

  final def append[C](s: MetaCont[B, C]): MetaCont[A, C] = s.asInstanceOf[MetaCont[A, C]]

  final def splitAt(c: Prompt) = sys error s"Prompt $c not found on the stack."

//  override def map[C](g: C => A): MetaCont[C, B] = ReturnCont(x => g(x).asInstanceOf[B])
}

private[effekt]
case class ReturnCont[-A, +B](f: A => B) extends MetaCont[A, B] {
  final def apply(a: A): Result[B] = Value(f(a))

  final def append[C](s: MetaCont[B, C]): MetaCont[A, C] = s map f

  final def splitAt(c: Prompt) = sys error s"Prompt $c not found on the stack."

//  override def map[C](g: C => A): MetaCont[C, B] = ReturnCont(x => f(g(x)))
}

private[effekt]
case class FramesCont[-A, B, +C](frames: List[Frame[Nothing, Any]], tail: MetaCont[B, C]) extends MetaCont[A, C] {

  final def apply(a: A): Result[C] = {
    val first :: rest = frames
    val result = first.asInstanceOf[Frame[A, B]](a)
    if (rest.isEmpty) {
      Computation(result, tail)
    } else {
      Computation(result, FramesCont(rest, tail))
    }
  }

  final def append[D](s: MetaCont[C, D]): MetaCont[A, D] = FramesCont(frames, tail append s)

  final def splitAt(c: Prompt) = tail.splitAt(c) match {
    case (head, tail) => (FramesCont(frames, head), tail)
  }

  override def flatMap[D](f: Frame[D, A]): MetaCont[D, C] = FramesCont(f :: frames, tail)
}

private[effekt]
case class HandlerCont[Res, +A](h: Prompt)(tail: MetaCont[Res, A]) extends MetaCont[Res, A] {
  final def apply(r: Res): Result[A] = tail(r)

  final def append[C](s: MetaCont[A, C]): MetaCont[Res, C] = HandlerCont(h)(tail append s)

  // Here we can see that our semantics is closer to spawn/controller than delimCC
  final def splitAt(c: Prompt) =
  // Here we deduce type equality from referential equality
    if (h eq c) {
      // Res == c.Res
      val head = HandlerCont(h)(CastCont[Res, c.Result]())
      val rest = tail.asInstanceOf[MetaCont[c.Result, A]]
      (head, rest)
    } else tail.splitAt(c) match {
      case (head, tail) => (HandlerCont(h)(head), tail)
    }
}

// we will NEVER split at a state cont. Instead a statecont
// just calls into the Stateful interface on capture
//private[effekt]
//case class StateCont[Res, S, +A](h: Stateful[S], state: S, tail: MetaCont[Res, A]) extends MetaCont[Res, A] {
//  final def apply(r: Res): Result[A] = tail(r)
//
//  final def append[C](rest: MetaCont[A, C]): MetaCont[Res, C] = {
//    h put state
//    StateCont(h, state, tail append rest)
//  }
//
//  final def splitAt(c: Prompt) = tail.splitAt(c) match {
//    case (head, tail) => (StateCont(h, h.get(), head), tail)
//  }
//}