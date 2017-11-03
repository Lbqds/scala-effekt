package effekt

private[effekt]
sealed trait MetaCont[-A, +B] extends Serializable {
  def apply(a: A): Result[B]

  def append[C](s: MetaCont[B, C]): MetaCont[A, C]

  def splitAt(c: Capability): (MetaCont[A, c.Res], MetaCont[c.Res, B])

  def map[C](f: C => A): MetaCont[C, B] = flatMap(x => pure(f(x)))

  def flatMap[C](f: Frame[C, A]): MetaCont[C, B] = FramesCont(List(f), this)
}

private[effekt]
case class CastCont[-A, +B]() extends MetaCont[A, B] {

  final def apply(a: A): Result[B] = Pure(a.asInstanceOf[B])

  final def append[C](s: MetaCont[B, C]): MetaCont[A, C] = s.asInstanceOf[MetaCont[A, C]]

  final def splitAt(c: Capability) = sys error s"Prompt $c not found on the stack."

  override def map[C](g: C => A): MetaCont[C, B] = ReturnCont(x => g(x).asInstanceOf[B])
}

private[effekt]
case class ReturnCont[-A, +B](f: A => B) extends MetaCont[A, B] {
  final def apply(a: A): Result[B] = Pure(f(a))

  final def append[C](s: MetaCont[B, C]): MetaCont[A, C] = s map f

  final def splitAt(c: Capability) = sys error s"Prompt $c not found on the stack."

  override def map[C](g: C => A): MetaCont[C, B] = ReturnCont(x => f(g(x)))
}

private[effekt]
case class FramesCont[-A, B, +C](frames: List[Frame[Nothing, Any]], tail: MetaCont[B, C]) extends MetaCont[A, C] {

  final def apply(a: A): Result[C] = {
    val first :: rest = frames
    val result = first.asInstanceOf[Frame[A, B]](a)
    if (rest.isEmpty) {
      Impure(result, tail)
    } else {
      Impure(result, FramesCont(rest, tail))
    }
  }

  final def append[D](s: MetaCont[C, D]): MetaCont[A, D] = FramesCont(frames, tail append s)

  final def splitAt(c: Capability) = tail.splitAt(c) match {
    case (head, tail) => (FramesCont(frames, head), tail)
  }

  override def flatMap[D](f: Frame[D, A]): MetaCont[D, C] = FramesCont(f :: frames, tail)
}

private[effekt]
case class HandlerCont[Res, +A](h: Cap[_])(tail: MetaCont[Res, A]) extends MetaCont[Res, A] {
  final def apply(r: Res): Result[A] = tail(r)

  final def append[C](s: MetaCont[A, C]): MetaCont[Res, C] = HandlerCont(h)(tail append s)

  final def splitAt(c: Capability) =
  // Here we deduce type equality from referential equality
    if (h eq c) {
      // R0 == c.Res
      val head = HandlerCont(h)(CastCont[Res, c.Res]())
      val rest = tail.asInstanceOf[MetaCont[c.Res, A]]
      (head, rest)
    } else tail.splitAt(c) match {
      case (head, tail) => (HandlerCont(h)(head), tail)
    }
}