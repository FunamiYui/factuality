package org.clulab.factuality.client.utils

class Timer(val description: String) {
  var elapsedTime: Option[Long] = None
  var startTime: Option[Long] = None

  def time[R](block: => R): R = {
    val t0 = System.currentTimeMillis()
    val result: R = block    // call-by-name
    val t1 = System.currentTimeMillis()

    elapsedTime = Some(t1 - t0)
    result
  }

  def start(): Unit = {
    val t0 = System.currentTimeMillis()

    startTime = Some(t0)
  }

  def stop(): Unit = {
    if (startTime.isDefined) {
      val t1 = System.currentTimeMillis()

      elapsedTime = Some(t1 - startTime.get)
    }
  }
}

object Timer {

  def log(text: String): Unit = {
    println(text)
  }
  
  // See http://biercoff.com/easily-measuring-code-execution-time-in-scala/
  def time[R](description: String)(block: => R): R = {
    val t0 = System.currentTimeMillis()
    log("Start " + t0 + " ms: " + description)

    val result: R = block    // call-by-name

    val t1 = System.currentTimeMillis()
    log(" Stop " + t1 + " ms: " + description)

    val diff = t1 - t0
    log(s" Diff " + diff + " ms: " + description)

    val  days = (diff / (1000 * 60 * 60 * 24)) / 1
    val hours = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
    val  mins = (diff % (1000 * 60 * 60)) / (1000 * 60)
    val  secs = (diff % (1000 * 60)) / 1000
    val msecs = (diff % (1000 * 1)) / 1

    log(f" Time $days:$hours%02d:$mins%02d:$secs%02d.$msecs%03d")
    result
  }
}
