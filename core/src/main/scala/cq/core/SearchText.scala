package cq.core

import java.nio.charset.StandardCharsets.UTF_8
import java.text.Normalizer
import java.util.Locale

object SearchText {
  val MaxWordBytes = 512
  private val Word = "[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*".r
  private val UnsearchableWord = "~"

  def words(value: String): List[String] =
    Word.findAllIn(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).toList

  def bounded(word: String): Boolean = word.getBytes(UTF_8).length <= MaxWordBytes

  def document(title: String, body: String): String =
    words(title + " " + body).map(word => if (bounded(word)) word else UnsearchableWord).mkString(" ", " ", " ")

  def contains(document: String, words: List[String], phrase: Boolean): Boolean = {
    require(words.nonEmpty && words.forall(word => bounded(word) && SearchText.words(word) == List(word)), "Expected normalized query words")
    if (phrase) document.contains(words.mkString(" ", " ", " "))
    else words.forall(word => document.contains(s" $word "))
  }
}
