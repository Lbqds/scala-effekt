import scala.language.implicitConversions

package object effekt {

  /**
   * Type alias for convenient use of capabilities
   *
   * @tparam E the effect signature to use
   */
  private[effekt] type Frame[-A, +B] = A => Control[B]

  type using[+A, -E] = given E => Control[A]
  type and[+A, -E] = given E => A

  type CPS[A, E] = given (A => Control[E]) => Control[E]

  type StatefulCPS[A, E, S] = given ((A, S) => Control[E]) => S => Control[E]

  final def run[A](c: Control[A]): A = c.run()

  final def handle(h: Handler)(f: h.R using h.type): Control[h.Res] = h.handle(f)

  final def handling[R0](f: R0 using Prompt { type Res = R0 }): Control[R0] =
    Control.handle(new Prompt { type Res = R0 })(f)

  implicit final def pure[A](a: => A): Control[A] = new Trivial(a)

  final def resume[A, Res](a: A): CPS[A, Res] = given k => k(a)
  final def resume[A, Res, S](a: A, s: S)(implicit k: ((A, S) => Control[Res])): Control[Res] = k(a, s)

  // capture continuations
  // ===
  // TODO rename Prompt to Delimiter?
  @scala.annotation.implicitNotFound("No prompt found for 'use'. Maybe you forgot to handle the effect?")
  trait Prompt { type Res }
  def use[A] given (p: Prompt) = ContinuationScope[A, p.type](p)

  // this complication is only necessary since we can't write `use {}` and have p inferred
  // as `use(p) {}`. So we write `use in {}` to mean `use(p) in {}`
  // In summary, we use value inference to guide type inference.
  case class ContinuationScope[A, P <: Prompt](p: P) {
    def in(body: CPS[A, p.Res]): Control[A] = Control.use(p) { body }
    def apply(body: CPS[A, p.Res]): Control[A] = in(body)
  }

  // ambient state
  // ===
  def stateful[S, R](init: S)(body: Stateful[S] => Control[R]): Control[R] = {
    val state = new Stateful[S] {
      private var state: S = init
      def get(): S = state
      def put(s: S): Unit = state = s
    }

    Control.stateful(state) { body }
  }
}
