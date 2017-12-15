/******************************************************************************
 * Copyright © 2016 Maxim Karpov                                              *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *     http://www.apache.org/licenses/LICENSE-2.0                             *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package ru.makkarpov.scalingua.pofile

import java.io._
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date

import ru.makkarpov.scalingua.StringUtils

object PoFile {
  /**
    * This file parsing code has a few assumptions for the structure of .po file (which are always held in case when
    * file is generated by code in this object).
    *
    * 1. Each message has comments before the first `msgctxt` or `msgid`.
    * 2. Multi-line string literals are separated only by empty strings.
    * 3. All message entries are in following order:
    *   * singluar: [msgctxt] msgid msgstr
    *   * plural: [msgctxt] msgid msgid_plural msgstr[0] .. msgstr[N]
    * 4. After entry key there is always a string literal, e.g. no enties like "msgstr\n\"\""
    * 5. Encoding of file is always UTF-8
    *
    * These assumptions helps to simplify parsing code a lot.
    */

  val encoding                = StandardCharsets.UTF_8

  val lineAnyHeader           = "^#.*$".r
  val lineTrComment           = "^# \\s*(.*)$".r // translator comment
  val lineExComment           = "^#\\.\\s*(.*)$".r // extracted comment
  val lineOtherComment        = "^#[^:,.].*$".r // other unknown comment
  val lineLocation            = "^#:\\s*(.+):(\\d+)$".r // location
  val lineFlags               = "^#,\\s*(.+)$".r // flags like "#, fuzzy"

  private val stringRegex     = "\"((?:\\\\(?:u[0-9A-Fa-f]{4}|[bfrtn\"'\\\\])|[^\\\\\"])*)\""
  val lineLiteral             = s"^$stringRegex$$".r
  val entryLiteral            = s"^([a-z_]+(?:\\[\\d+\\])?)\\s*$stringRegex$$".r

  val entryMsgPlural          = "^msgstr\\[\\d*\\]$".r

  private def headerComment(s: String) = s"#  !Generated: $s"

  def apply(f: File): Iterator[Message] = apply(new FileInputStream(f))

  def apply(is: InputStream): Iterator[Message] = {
    val input = new BufferedReader(new InputStreamReader(is, encoding))

    val header = headerComment("")
    val lines = Iterator.continually(input.readLine()).takeWhile(_ ne null).map(_.trim)
                    .filter(x => x.nonEmpty && !x.startsWith(header)).buffered

    def readEntry(): (String, MultipartString) = {
      if (!lines.hasNext) {
        input.close()
        throw new IllegalArgumentException("Premature end of stream")
      }

      val entryLiteral(cmd, stringHead) = lines.next()
      var done = false
      var strings = Seq(StringUtils.unescape(stringHead))
      while (!done && lines.hasNext) lines.head match {
        case lineLiteral(str) =>
          strings :+= StringUtils.unescape(str)
          lines.next() // consume
        case _ => done = true
      }
      (cmd, MultipartString(strings:_*))
    }

    def peekEntry(): Option[String] = {
      if (!lines.hasNext)
        return None

      lines.head match {
        case entryLiteral(cmd, _) => Some(cmd)
        case _ => None
      }
    }

    def readHeader(): MessageHeader = {
      var trComments = Seq.empty[String]
      var exComments = Seq.empty[String]
      var locations = Seq.empty[MessageLocation]
      var flags = MessageFlag.ValueSet.empty

      while (lines.hasNext && lineAnyHeader.findFirstIn(lines.head).isDefined) lines.next() match {
        case lineTrComment(cmt) => trComments :+= cmt
        case lineExComment(cmt) => exComments :+= cmt
        case lineOtherComment() => // ignore it.
        case lineLocation(fle, lne) => locations :+= MessageLocation(fle, lne.toInt)
        case lineFlags(flgStr) =>
          for (s <- flgStr.split(",").map(_.trim.toLowerCase))
            flags += MessageFlag.values.find(_.toString == s).getOrElse {
              throw new IllegalArgumentException(s"Undefined message flag: '$s'")
            }
        case x => throw new IllegalArgumentException(s"Incorrect header line: '$x'")
      }

      MessageHeader(trComments, exComments, locations, flags)
    }

    def readMessage(): Message = {
      if (!lines.hasNext) {
        input.close()
        return null
      }

      try {
        val hdr = readHeader()

        if (!lines.hasNext) {
          input.close()
          return null
        }

        val (msgCtx, msgId) = readEntry() match {
          case ("msgctxt", ctxt) =>
            val ("msgid", id) = readEntry()
            (Some(ctxt), id)

          case ("msgid", id) => (None, id)

          case (x, _) => throw new IllegalArgumentException(s"Either `msgctxt` or `msgid` expected, got `$x`")
        }

        peekEntry() match {
          case Some("msgstr") => // singular entry
            val ("msgstr", msgStr) = readEntry()
            Message.Singular(hdr, msgCtx, msgId, msgStr)

          case Some("msgid_plural") => // plural entry
            val ("msgid_plural", msgIdPlural) = readEntry()

            var translations = Seq.empty[MultipartString]
            var done = false
            do {
              val nextStr = s"msgstr[${translations.size}]"

              peekEntry() match {
                case Some(`nextStr`) =>
                  val (_, msgs) = readEntry()
                  translations :+= msgs

                case Some(e@entryMsgPlural()) =>
                  throw new IllegalArgumentException(s"Entries are out-of-order: '$e'")

                case _ => done = true
              }
            } while (!done)

            Message.Plural(hdr, msgCtx, msgId, msgIdPlural, translations)

          case Some(x) =>
            throw new IllegalArgumentException(s"Bad entry: $x")

          case None =>
            throw new IllegalArgumentException("\"msgstr\" or \"msgid_plural\" expected.")
        }
      } catch {
        case e: Exception =>
          input.close()
          throw e
      }
    }

    Iterator.continually(readMessage()).takeWhile(_ ne null)
  }

  def update(f: File, messages: Iterator[Message]): Unit = {
    val output = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), encoding), false)
    try {
      output.println(headerComment(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())))
      output.println()

      def printEntry(s: String, m: MultipartString): Unit = {
        output.print(s + " ")

        if (m.parts.isEmpty) output.println("\"\"")
        else for (p <- m.parts) output.println("\"" + StringUtils.escape(p) + "\"")
      }

      for (m <- messages) {
        for (s <- m.header.comments)
          output.println(s"#  $s")

        for (s <- m.header.extractedComments)
          output.println(s"#. $s")

        for (s <- m.header.locations)
          output.println(s"#: ${s.file}:${s.line}")

        if (m.header.flags.nonEmpty)
          output.println(s"#, " + m.header.flags.map(_.toString).mkString(", "))

        for (c <- m.context)
          printEntry("msgctxt", c)

        printEntry("msgid", m.message)

        m match {
          case Message.Singular(_, _, _, tr) =>
            printEntry("msgstr", tr)

          case Message.Plural(_, _, _, id, trs) =>
            printEntry("msgid_plural", id)

            for ((m, i) <- trs.zipWithIndex)
              printEntry(s"msgstr[$i]", m)
        }

        output.println()
      }
    } finally output.close()
  }
}
