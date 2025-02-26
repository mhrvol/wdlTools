package wdlTools.cli

import java.nio.file.Files

import wdlTools.generators.code.WdlFormatter
import dx.util.{FileSourceResolver, LocalFileSource}

import scala.jdk.CollectionConverters._
import scala.language.reflectiveCalls

case class Format(conf: WdlToolsConf) extends Command {
  override def apply(): Unit = {
    val docSource = FileSourceResolver.get.resolve(conf.format.uri())
    val outputDir = conf.format.outputDir.toOption
    val overwrite = conf.format.overwrite()
    val formatter = WdlFormatter(followImports = conf.format.followImports())
    val documents = formatter.formatDocuments(docSource)
    documents.foreach {
      case (source, lines) if outputDir.isDefined =>
        Files.write(outputDir.get.resolve(source.name), lines.asJava)
      case (localSource: LocalFileSource, lines) if overwrite =>
        Files.write(localSource.canonicalPath, lines.asJava)
      case (_, lines) =>
        println(lines.mkString(System.lineSeparator()))
    }
  }
}
