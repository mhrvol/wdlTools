package wdlTools.syntax

import java.nio.file.Path

import wdlTools.util.Util.Verbosity._

object Util {
  case class Conf(localDirectories: Seq[Path],
                  verbosity: Verbosity = Normal,
                  antlr4Trace: Boolean = false)
}
