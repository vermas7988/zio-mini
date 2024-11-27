package zio

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.ExecutionContext

trait Fiber[+A] {
  def join: ZIO[A]
}

class FiberImpl[A](zio: ZIO[A]) extends Fiber[A] {

  sealed trait FiberState

  final case class Running(callbacks: List[A => Any]) extends FiberState
  final case class Done(result: A)                    extends FiberState

  val state: AtomicReference[FiberState] = new AtomicReference[FiberState](Running(List.empty))

  def complete(result: A): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get()
      oldState match
        case Running(callbacks) =>
          if (!state.compareAndSet(oldState, Done(result))) {
            callbacks.foreach(cb => cb(result))
            loop = false
          }
        case Done(_) =>
          throw new Exception("Internal Defect: Fiber being completed multiple times")
    }
  }

  def await(callback: A => Any): Unit = {
    var loop = true
    while (loop) {
      val oldState = state.get()
      oldState match
        case Running(callbacks) =>
          val newStates = Running(callback :: callbacks)
          loop = !state.compareAndSet(oldState, newStates)
        case Done(result) =>
          loop = false
          callback(result)
    }
  }

  def start(): Unit = ExecutionContext.global.execute { () =>
    zio.run(complete)

  }

  override def join: ZIO[A] =
    ZIO.async { callback =>
      await(callback)
    }

}

sealed trait ZIO[+A] { self =>

  def fork: ZIO[Fiber[A]] = ZIO.Fork(self)

  def flatMap[B](f: A => ZIO[B]): ZIO[B] = ZIO.FlatMap(self, f)

  def map[B](f: A => B): ZIO[B] = flatMap(a => ZIO.succeedNow(f(a)))

  def as[B](b: B): ZIO[B] = map(_ => b)

  def zip[B](that: ZIO[B]): ZIO[(A, B)] =
    zipWith(that)((a, b) => (a, b))

  def zipRight[B](that: => ZIO[B]): ZIO[B] =
    zipWith(that)((_, b) => b)

  def *>[B](that: => ZIO[B]): ZIO[B] =
    zipRight(that)

  def zipPar[B](that: => ZIO[B]): ZIO[(A, B)] =
    for {
      f <- self.fork
      b <- that
      a <- f.join
    } yield (a, b)

  def repeat(n: Int): ZIO[Unit] =
    if (n <= 0) {
      ZIO.succeedNow(())
    } else {
      self *> repeat(n - 1)
    }

  def zipWith[B, C](that: => ZIO[B])(f: (A, B) => C): ZIO[C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  final def run(callback: A => Unit): Unit = {
    type Erased         = ZIO[Any]
    type ErasedCallback = Any => Any
    type Cont           = Any => Erased

    val stack = new mutable.Stack[Cont]()

    def erase[E](zio: ZIO[E]): Erased = zio

    var currentZIO = erase(self)

    var loop = true

    def resume(): Unit = {
      loop = true
      run()
    }

    def complete(value: Any): Unit =
      if (stack.isEmpty) {
        loop = false
        callback(value.asInstanceOf[A])
      } else {
        val cont: Cont = stack.pop()
        currentZIO = cont(value)
      }

    def run(): Unit =
      while (loop) {
        currentZIO match
          case ZIO.Succeed(value) =>
            complete(value)
          case ZIO.Effect(thunk) =>
            complete(thunk())
          case ZIO.FlatMap(zio, f) =>
            stack.push(f.asInstanceOf[Cont])
            currentZIO = zio
          case ZIO.Async(register) =>
            if (stack.isEmpty) {
              loop = false
              register(callback.asInstanceOf[ErasedCallback])
            } else {
              loop = false
              register { c =>
                currentZIO = ZIO.succeedNow(c)
                resume()
              }
            }
          case ZIO.Fork(zio) =>
            val fiber = new FiberImpl(zio)
            fiber.start()
            complete(fiber)
      }

    run()
  }

}

object ZIO {

  def succeedNow[A](value: A): ZIO[A] = ZIO.Succeed(value)

  def succeed[A](value: => A): ZIO[A] = ZIO.Effect(() => value)

  def async[A](register: (A => Any) => Any): ZIO[A] = ZIO.Async(register)

  case class Succeed[A](value: A) extends ZIO[A] {
//     override def run(callback: A => Unit): Unit = callback(value)
  }

  case class Effect[A](f: () => A) extends ZIO[A] {
//     override def run(callback: A => Unit): Unit = callback(f())
  }

  case class FlatMap[A, B](zio: ZIO[A], f: A => ZIO[B]) extends ZIO[B] {
//     override def run(callback: B => Unit): Unit = zio.run(a => f(a).run(callback))
  }

  case class Async[A](register: (A => Any) => Any) extends ZIO[A] {
//     override def run(callback: A => Unit): Unit = register(callback)
  }

  case class Fork[A](zio: ZIO[A]) extends ZIO[Fiber[A]] {
//    override def run(callback: Fiber[A] => Unit): Unit = {
//      val fiber = new FiberImpl[A](zio)
//      fiber.start()
//      callback(fiber)
//    }
  }
}
