package zio

import scala.concurrent.ExecutionContext

trait Fiber[+A] {
  def join: ZIO[A]
}

class FiberImpl[A](zio: ZIO[A]) extends Fiber[A] {
  var maybeResult: Option[A]    = None
  var callbacks: List[A => Any] = List.empty

  def start(): Unit = ExecutionContext.global.execute { () =>
    zio.run { a =>
      maybeResult = Some(a)
      callbacks.foreach(c => c(a))
    }
  }

  override def join: ZIO[A] = maybeResult match
    case Some(value) => ZIO.succeedNow(value)
    case None =>
      ZIO.async { complete =>
        // complete is the callback of this async zio,
        // we register this into the callbacks list/stack and start() would eventually execute the callback from
        // the list. Finishing ZIO.async requires execution of callback in the end so, when callback is executed this
        // async zio is finished.
        callbacks = complete :: callbacks
      }

}

trait ZIO[+A] { self =>
  def run(callback: A => Unit): Unit

  def fork: ZIO[Fiber[A]] = ZIO.Fork(self)

  def flatMap[B](f: A => ZIO[B]): ZIO[B] = ZIO.FlatMap(self, f)

  def map[B](f: A => B): ZIO[B] = flatMap(a => ZIO.succeedNow(f(a)))

  def as[B](b: B): ZIO[B] = map(_ => b)

  def zip[B](that: ZIO[B]): ZIO[(A, B)] =
    for {
      a <- self
      b <- that
    } yield (a, b)

  def zipPar[B](that: ZIO[B]): ZIO[(A, B)] =
    for {
      f <- self.fork
      b <- that
      a <- f.join
    } yield (a, b)

}

object ZIO {

  def succeedNow[A](value: A): ZIO[A] = ZIO.Succeed(value)

  def succeed[A](value: => A): ZIO[A] = ZIO.Effect(() => value)

  def async[A](register: (A => Any) => Any): ZIO[A] = ZIO.Async(register)

  case class Succeed[A](value: A) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = callback(value)
  }

  case class Effect[A](f: () => A) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = callback(f())
  }

  case class FlatMap[A, B](zio: ZIO[A], f: A => ZIO[B]) extends ZIO[B] {
    override def run(callback: B => Unit): Unit = zio.run(a => f(a).run(callback))
  }

  case class Async[A](register: (A => Any) => Any) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = register(callback)
  }

  case class Fork[A](zio: ZIO[A]) extends ZIO[Fiber[A]] {
    override def run(callback: Fiber[A] => Unit): Unit = {
      val fiber = new FiberImpl[A](zio)
      fiber.start()
      callback(fiber)
    }
  }
}
