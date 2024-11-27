import zio.ZIO

import java.time.Instant

@main
def main(): Unit = {
  println("Hello world!")

  val asyncZio1 = ZIO.async[Int] { c =>
    println("Begin async 1")
    Thread.sleep(1000)
    println("Finish Async 1")
    c(1)
  }

  val asyncZio2 = ZIO.async[Int] { c =>
    println("Begin async 2")
    Thread.sleep(500)
    println("Finish Async 2")
    c(2)
  }

  val forked =
    for {
      f <- asyncZio1.fork
      b <- asyncZio2
      a <- f.join
    } yield println(s"1st: $a, 2nd: $b")

  forked.run(_ => ())

  val stackSafety = ZIO.succeed(println(s"Howdy! ${Instant.now()}")).repeat(10)

  stackSafety.run(_ => ())

  Thread.sleep(3000)

}
