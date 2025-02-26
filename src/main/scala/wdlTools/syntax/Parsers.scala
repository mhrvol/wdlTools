package wdlTools.syntax

import wdlTools.syntax.AbstractSyntax.Document
import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import dx.util.{FileNode, FileSourceResolver, Logger}

case class NoSuchParserException(msg: String) extends Exception

case class Parsers(
    followImports: Boolean = false,
    fileResolver: FileSourceResolver = FileSourceResolver.get,
    listenerFactories: Vector[ParseTreeListenerFactory] = Vector.empty,
    errorHandler: Option[Vector[SyntaxError] => Boolean] = None,
    logger: Logger = Logger.get
) {
  private val parsers: Map[WdlVersion, WdlParser] = Map(
      WdlVersion.Draft_2 -> draft_2.ParseAll(followImports,
                                             fileResolver,
                                             listenerFactories,
                                             errorHandler,
                                             logger),
      WdlVersion.V1 -> v1.ParseAll(followImports,
                                   fileResolver,
                                   listenerFactories,
                                   errorHandler,
                                   logger),
      WdlVersion.V1_1 -> v1_1.ParseAll(followImports,
                                       fileResolver,
                                       listenerFactories,
                                       errorHandler,
                                       logger),
      WdlVersion.V2 -> v2.ParseAll(followImports,
                                   fileResolver,
                                   listenerFactories,
                                   errorHandler,
                                   logger)
  )

  def getWdlVersion(fileSource: FileNode): WdlVersion = {
    parsers
      .collectFirst {
        case (wdlVersion, parser) if parser.canParse(fileSource) => wdlVersion
      }
      .getOrElse(
          throw NoSuchParserException(s"No parser is able to parse document ${fileSource}")
      )
  }

  def getParser(fileSource: FileNode): WdlParser = {
    parsers.values.collectFirst {
      case parser if parser.canParse(fileSource) => parser
    } match {
      case Some(parser) => parser
      case _ =>
        throw NoSuchParserException(s"No parser is able to parse document ${fileSource}")
    }
  }

  def getParser(wdlVersion: WdlVersion): WdlParser = {
    parsers.get(wdlVersion) match {
      case Some(parser) => parser
      case _ =>
        throw NoSuchParserException(s"No parser defined for WdlVersion ${wdlVersion}")
    }
  }

  def parseDocument(fileSource: FileNode): Document = {
    val parser = getParser(fileSource)
    parser.parseDocument(fileSource)
  }

  def getDocumentWalker[T](fileSource: FileNode, results: T): DocumentWalker[T] = {
    val parser = getParser(fileSource)
    parser.Walker(fileSource, results)
  }
}

object Parsers {
  lazy val default: Parsers = Parsers()
}
