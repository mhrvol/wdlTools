package wdlTools.syntax.draft_2

import wdlTools.syntax.Antlr4Util.ParseTreeListenerFactory
import wdlTools.syntax.{
  Operator,
  SourceLocation,
  SyntaxError,
  SyntaxException,
  WdlParser,
  WdlVersion,
  AbstractSyntax => AST
}
import wdlTools.syntax.draft_2.{ConcreteSyntax => CST}
import dx.util.{AddressableFileSource, FileNode, FileSourceResolver, Logger, StringFileNode}

import scala.collection.immutable.TreeSeqMap

// parse and follow imports
case class ParseAll(followImports: Boolean = false,
                    fileResolver: FileSourceResolver = FileSourceResolver.get,
                    listenerFactories: Vector[ParseTreeListenerFactory] = Vector.empty,
                    errorHandler: Option[Vector[SyntaxError] => Boolean] = None,
                    logger: Logger = Logger.get)
    extends WdlParser(followImports, fileResolver, logger) {

  private case class Translator(docSource: FileNode) {
    def translateType(t: CST.Type): AST.Type = {
      t match {
        case CST.TypeOptional(t, srcText) =>
          AST.TypeOptional(translateType(t), srcText)
        case CST.TypeArray(t, nonEmpty, srcText) =>
          AST.TypeArray(translateType(t), nonEmpty, srcText)
        case CST.TypeMap(k, v, srcText) =>
          AST.TypeMap(translateType(k), translateType(v), srcText)
        case CST.TypePair(l, r, srcText) =>
          AST.TypePair(translateType(l), translateType(r), srcText)
        case CST.TypeString(srcText)         => AST.TypeString(srcText)
        case CST.TypeFile(srcText)           => AST.TypeFile(srcText)
        case CST.TypeBoolean(srcText)        => AST.TypeBoolean(srcText)
        case CST.TypeInt(srcText)            => AST.TypeInt(srcText)
        case CST.TypeFloat(srcText)          => AST.TypeFloat(srcText)
        case CST.TypeIdentifier(id, srcText) => AST.TypeIdentifier(id, srcText)
        case CST.TypeObject(srcText)         => AST.TypeObject(srcText)
      }
    }

    def translateExpr(e: CST.Expr): AST.Expr = {
      e match {
        // values
        case CST.ExprString(value, srcText)  => AST.ValueString(value, srcText)
        case CST.ExprBoolean(value, srcText) => AST.ValueBoolean(value, srcText)
        case CST.ExprInt(value, srcText)     => AST.ValueInt(value, srcText)
        case CST.ExprFloat(value, srcText)   => AST.ValueFloat(value, srcText)

        // compound values
        case CST.ExprIdentifier(id, srcText) => AST.ExprIdentifier(id, srcText)
        case CST.ExprCompoundString(vec, srcText) =>
          AST.ExprCompoundString(vec.map(translateExpr), srcText)
        case CST.ExprPair(l, r, srcText) =>
          AST.ExprPair(translateExpr(l), translateExpr(r), srcText)
        case CST.ExprArrayLiteral(vec, srcText) =>
          AST.ExprArray(vec.map(translateExpr), srcText)
        case CST.ExprMapLiteral(m, srcText) =>
          AST.ExprMap(m.map { item =>
            AST.ExprMember(translateExpr(item.key), translateExpr(item.value), item.loc)
          }, srcText)
        case CST.ExprObjectLiteral(m, srcText) =>
          AST.ExprObject(m.map { member =>
            AST.ExprMember(translateExpr(member.key), translateExpr(member.value), member.loc)
          }, srcText)

        // string place holders
        case CST.ExprPlaceholder(t, f, sep, default, value, srcText) =>
          AST.ExprPlaceholder(t.map(translateExpr),
                              f.map(translateExpr),
                              sep.map(translateExpr),
                              default.map(translateExpr),
                              translateExpr(value),
                              srcText)

        // operators on one argument
        case CST.ExprUnaryPlus(value, srcText) =>
          AST.ExprApply(Operator.UnaryPlus.name, Vector(translateExpr(value)), srcText)
        case CST.ExprUnaryMinus(value, srcText) =>
          AST.ExprApply(Operator.UnaryMinus.name, Vector(translateExpr(value)), srcText)
        case CST.ExprNegate(value, srcText) =>
          AST.ExprApply(Operator.LogicalNot.name, Vector(translateExpr(value)), srcText)

        // operators on two arguments
        case CST.ExprLor(a, b, srcText) =>
          AST.ExprApply(Operator.LogicalOr.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprLand(a, b, srcText) =>
          AST.ExprApply(Operator.LogicalAnd.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprEqeq(a, b, srcText) =>
          AST.ExprApply(Operator.Equality.name, Vector(translateExpr(a), translateExpr(b)), srcText)
        case CST.ExprNeq(a, b, srcText) =>
          AST.ExprApply(Operator.Inequality.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprLt(a, b, srcText) =>
          AST.ExprApply(Operator.LessThan.name, Vector(translateExpr(a), translateExpr(b)), srcText)
        case CST.ExprLte(a, b, srcText) =>
          AST.ExprApply(Operator.LessThanOrEqual.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprGt(a, b, srcText) =>
          AST.ExprApply(Operator.GreaterThan.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprGte(a, b, srcText) =>
          AST.ExprApply(Operator.GreaterThanOrEqual.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprAdd(a, b, srcText) =>
          AST.ExprApply(Operator.Addition.name, Vector(translateExpr(a), translateExpr(b)), srcText)
        case CST.ExprSub(a, b, srcText) =>
          AST.ExprApply(Operator.Subtraction.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprMul(a, b, srcText) =>
          AST.ExprApply(Operator.Multiplication.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)
        case CST.ExprDivide(a, b, srcText) =>
          AST.ExprApply(Operator.Division.name, Vector(translateExpr(a), translateExpr(b)), srcText)
        case CST.ExprMod(a, b, srcText) =>
          AST.ExprApply(Operator.Remainder.name,
                        Vector(translateExpr(a), translateExpr(b)),
                        srcText)

        // Access an array element at [index]
        case CST.ExprAt(array, index, srcText) =>
          AST.ExprAt(translateExpr(array), translateExpr(index), srcText)

        case CST.ExprIfThenElse(cond, tBranch, fBranch, srcText) =>
          AST.ExprIfThenElse(translateExpr(cond),
                             translateExpr(tBranch),
                             translateExpr(fBranch),
                             srcText)
        case CST.ExprApply(funcName, elements, srcText) =>
          if (Operator.All.contains(funcName)) {
            throw new SyntaxException(s"${funcName} is reserved and not a valid function name",
                                      srcText)
          }
          AST.ExprApply(funcName, elements.map(translateExpr), srcText)
        case CST.ExprGetName(e, id, srcText) =>
          AST.ExprGetName(translateExpr(e), id, srcText)

        case other =>
          throw new Exception(s"invalid concrete syntax element ${other}")
      }
    }

    private def translateMetaKVs(kvs: Vector[CST.MetaKV],
                                 sectionName: String): Vector[AST.MetaKV] = {
      kvs
        .foldLeft(TreeSeqMap.empty[String, AST.MetaKV]) {
          case (accu, kv) =>
            val metaValue = AST.MetaValueString(kv.value, kv.loc)
            if (accu.contains(kv.id)) {
              logger.warning(
                  s"""duplicate ${sectionName} key ${kv.id}: earlier value ${accu(kv.id)} 
                     |is overridden by later value ${metaValue}""".stripMargin.replaceAll("\n", " ")
              )
            }
            accu + (kv.id -> AST.MetaKV(kv.id, metaValue, kv.loc))
        }
        .values
        .toVector
    }

    private def translateInputSection(
        inp: CST.InputSection
    ): AST.InputSection = {
      AST.InputSection(inp.declarations.map(translateDeclaration), inp.loc)
    }

    private def translateOutputSection(
        output: CST.OutputSection
    ): AST.OutputSection = {
      AST.OutputSection(output.declarations.map(translateDeclaration), output.loc)
    }

    private def translateCommandSection(
        cs: CST.CommandSection
    ): AST.CommandSection = {
      AST.CommandSection(cs.parts.map(translateExpr), cs.loc)
    }

    private def translateDeclaration(decl: CST.Declaration): AST.Declaration = {
      AST.Declaration(decl.name,
                      translateType(decl.wdlType),
                      decl.expr.map(translateExpr),
                      decl.loc)
    }

    private def translateMetaSection(meta: CST.MetaSection): AST.MetaSection = {
      AST.MetaSection(translateMetaKVs(meta.kvs, "meta"), meta.loc)
    }

    private def translateParameterMetaSection(
        paramMeta: CST.ParameterMetaSection
    ): AST.ParameterMetaSection = {
      AST.ParameterMetaSection(translateMetaKVs(paramMeta.kvs, "parameter_meta"), paramMeta.loc)
    }

    private def translateRuntimeSection(
        runtime: CST.RuntimeSection
    ): AST.RuntimeSection = {
      AST.RuntimeSection(
          runtime.kvs
            .foldLeft(TreeSeqMap.empty[String, AST.RuntimeKV]) {
              case (accu, CST.RuntimeKV(id, expr, text)) =>
                val tExpr = translateExpr(expr)
                if (accu.contains(id)) {
                  logger.warning(
                      s"duplicate runtime key ${id}: earlier value ${accu(id)} is overridden by later value ${tExpr}"
                  )
                }
                accu + (id -> AST.RuntimeKV(id, tExpr, text))
            }
            .values
            .toVector,
          runtime.loc
      )
    }

    private def translateWorkflowElement(
        elem: CST.WorkflowElement
    ): AST.WorkflowElement = {
      elem match {
        case CST.Declaration(name, wdlType, expr, text) =>
          AST.Declaration(name, translateType(wdlType), expr.map(translateExpr), text)

        case CST.Call(name, alias, inputs, text) =>
          AST.Call(
              name,
              alias.map {
                case CST.CallAlias(callName, callText) =>
                  AST.CallAlias(callName, callText)
              },
              Vector.empty,
              inputs.map {
                case CST.CallInputs(inputsVec, inputsText) =>
                  AST.CallInputs(inputsVec.map { inp =>
                    AST.CallInput(inp.name, translateExpr(inp.expr), inp.loc)
                  }, inputsText)
              },
              text
          )

        case CST.Scatter(identifier, expr, body, text) =>
          AST.Scatter(identifier, translateExpr(expr), body.map(translateWorkflowElement), text)

        case CST.Conditional(expr, body, text) =>
          AST.Conditional(translateExpr(expr), body.map(translateWorkflowElement), text)
      }
    }

    private def translateWorkflow(wf: CST.Workflow): AST.Workflow = {
      AST.Workflow(
          wf.name,
          wf.input.map(translateInputSection),
          wf.output.map(translateOutputSection),
          wf.meta.map(translateMetaSection),
          wf.parameterMeta.map(translateParameterMetaSection),
          wf.body.map(translateWorkflowElement),
          wf.loc
      )
    }

    private def translateImportDoc(importDoc: CST.ImportDoc,
                                   importedDoc: Option[AST.Document]): AST.ImportDoc = {
      val addrAbst = AST.ImportAddr(importDoc.addr.value, importDoc.addr.loc)
      val nameAbst = importDoc.name.map {
        case CST.ImportName(value, text) => AST.ImportName(value, text)
      }

      // Replace the original statement with a new one
      AST.ImportDoc(nameAbst, Vector.empty, addrAbst, importedDoc, importDoc.loc)
    }

    private def translateTask(task: CST.Task): AST.Task = {
      AST.Task(
          task.name,
          task.input.map(translateInputSection),
          task.output.map(translateOutputSection),
          translateCommandSection(task.command),
          task.declarations.map(translateDeclaration),
          task.meta.map(translateMetaSection),
          task.parameterMeta.map(translateParameterMetaSection),
          task.runtime.map(translateRuntimeSection),
          None,
          task.loc
      )
    }

    // start from a document [doc], and recursively dive into all the imported
    // documents. Replace all the raw import statements with fully elaborated ones.
    def translateDocument(doc: ConcreteSyntax.Document): AST.Document = {
      // translate all the elements of the document to the abstract syntax
      val elems: Vector[AST.DocumentElement] = doc.elements.map {
        case importDoc: ConcreteSyntax.ImportDoc =>
          val importedDoc = if (followImports) {
            val parent = doc.source match {
              case fs: AddressableFileSource => fs.getParent
              case _                         => None
            }
            followImport(importDoc.addr.value, parent)
          } else {
            None
          }
          translateImportDoc(importDoc, importedDoc)
        case task: ConcreteSyntax.Task => translateTask(task)
        case other                     => throw new Exception(s"unrecognized document element ${other}")
      }
      val aWf = doc.workflow.map(translateWorkflow)
      val version = AST.Version(WdlVersion.Draft_2, SourceLocation.empty)
      AST.Document(doc.source, version, elems, aWf, doc.loc, doc.comments)
    }
  }

  override def canParse(fileSource: FileNode): Boolean = {
    fileSource.readLines.foreach { line =>
      val trimmed = line.trim
      if (!(trimmed.isEmpty || trimmed.startsWith("#"))) {
        return trimmed.trim.startsWith("import") ||
          trimmed.startsWith("workflow") ||
          trimmed.startsWith("task")
      }
    }
    false
  }

  override def parseDocument(fileSource: FileNode): AST.Document = {
    val grammar = WdlDraft2Grammar.newInstance(fileSource, listenerFactories, logger)
    val visitor = ParseTop(grammar)
    val top: ConcreteSyntax.Document =
      try {
        visitor.parseDocument
      } catch {
        case ex: Throwable =>
          throw new SyntaxException(s"error parsing document ${fileSource.toString}", ex)
      }
    val errorListener = grammar.errListener
    if (errorListener.hasErrors && errorHandler
          .forall(eh => eh(errorListener.getErrors))) {
      throw new SyntaxException(errorListener.getErrors)
    }
    val translator = Translator(fileSource)
    translator.translateDocument(top)
  }

  override def parseExpr(text: String): AST.Expr = {
    val docSource = StringFileNode(text)
    val parser = ParseTop(
        WdlDraft2Grammar.newInstance(docSource, listenerFactories, logger)
    )
    val translator = Translator(docSource)
    translator.translateExpr(parser.parseExpr)
  }

  override def parseType(text: String): AST.Type = {
    val docSource = StringFileNode(text)
    val parser = ParseTop(
        WdlDraft2Grammar.newInstance(docSource, listenerFactories, logger)
    )
    val translator = Translator(docSource)
    translator.translateType(parser.parseWdlType)
  }
}
