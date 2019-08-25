package entity

object Logger {
  def error(text: String): Unit = println(text)
  def info(text: String): Unit = println(text)
}
