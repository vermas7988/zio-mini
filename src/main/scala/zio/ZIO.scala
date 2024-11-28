package zio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.ExecutionContext

trait Fiber[+A] {
  def join: ZIO[A]
  def interrupt: ZIO[Unit] = ???
}

final case class FiberContext[A](startZIO: ZIO[A]) extends Fiber[A] {

  sealed trait FiberState

  final case class Running(callbacks: List[A => Any]) extends FiberState

  final case class Done(result: A) extends FiberState

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

  override def join: ZIO[A] =
    ZIO.async { callback =>
      await(callback)
    }

  type Erased = ZIO[Any]
//    type ErasedCallback = Any => Any
  type Cont = Any => Erased

  val stack = new mutable.Stack[Cont]()

  def erase[E](zio: ZIO[E]): Erased = zio

  var currentZIO = erase(startZIO)

  var loop = true

  def resume(): Unit = {
    loop = true
    run()
  }

  def continue(value: Any): Unit =
    if (stack.isEmpty) {
      loop = false
      complete(value.asInstanceOf[A])
    } else {
      val cont: Cont = stack.pop()
      currentZIO = cont(value)
    }

  def run(): Unit =
    while (loop) {
      currentZIO match
        case ZIO.Succeed(value) =>
          continue(value)
        case ZIO.Effect(thunk) =>
          continue(thunk())
        case ZIO.FlatMap(zio, f) =>
          stack.push(f.asInstanceOf[Cont])
          currentZIO = zio
        case ZIO.Async(register) =>
          if (stack.isEmpty) {
            loop = false
            register(a => complete(a.asInstanceOf[A]))
          } else {
            loop = false
            register { c =>
              currentZIO = ZIO.succeedNow(c)
              resume()
            }
          }
        case ZIO.Fork(zio) =>
          val fiber = FiberContext(zio)
          continue(fiber)
    }

  ExecutionContext.global.execute(() => run())

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

  final def unsafeRunFiber: Fiber[A] =
    FiberContext(self)

  final def unsafeRunSync: A = {
    val latch     = new CountDownLatch(1)
    var result: A = null.asInstanceOf[A]
    val zio = self.flatMap { a =>
      ZIO.succeed {
        result = a
        latch.countDown()
      }
    }

    zio.unsafeRunFiber
    latch.await()

    result
  }

}

object ZIO {

  def succeedNow[A](value: A): ZIO[A] = ZIO.Succeed(value)

  def succeed[A](value: => A): ZIO[A] = ZIO.Effect(() => value)

  def async[A](register: (A => Any) => Any): ZIO[A] = ZIO.Async(register)

  case class Succeed[A](value: A) extends ZIO[A]

  case class Effect[A](f: () => A) extends ZIO[A]

  case class FlatMap[A, B](zio: ZIO[A], f: A => ZIO[B]) extends ZIO[B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[A]

  case class Fork[A](zio: ZIO[A]) extends ZIO[Fiber[A]]
}
