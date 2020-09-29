package wdlTools.generators.code

import wdlTools.generators.code.BaseWdlGenerator._
import wdlTools.generators.code.Spacing.Spacing
import wdlTools.generators.code.Wrapping.Wrapping
import wdlTools.types.TypedAbstractSyntax._
import wdlTools.types.WdlTypes.{T_Int, T_Object, T_String, _}
import wdlTools.syntax.{Operator, WdlVersion}

case class WdlV1Generator(omitNullInputs: Boolean = true) {

  private case class Literal(value: Any, quoting: Boolean = false) extends Sized {
    override lazy val length: Int = toString.length

    override lazy val toString: String = {
      if (quoting) {
        s"${'"'}${value}${'"'}"
      } else {
        value.toString
      }
    }
  }

  private case class Sequence(sizeds: Vector[Sized],
                              wrapping: Wrapping = Wrapping.Never,
                              spacing: Spacing = Spacing.Off,
                              continue: Boolean = true)
      extends Composite {
    require(sizeds.nonEmpty)

    override lazy val length: Int = sizeds.map(_.length).sum + (
        if (spacing == Spacing.On) sizeds.length else 0
    )

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator
        .derive(newSpacing = spacing, newWrapping = wrapping)
        .appendAll(sizeds, continue)
    }
  }

  private abstract class Group(ends: Option[(Sized, Sized)] = None,
                               val wrapping: Wrapping = Wrapping.Never,
                               val spacing: Spacing = Spacing.On)
      extends Composite {

    private val endLengths: (Int, Int) =
      ends.map(e => (e._1.length, e._2.length)).getOrElse((0, 0))

    override lazy val length: Int = body.map(_.length).getOrElse(0) + endLengths._1 + endLengths._2

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      if (ends.isDefined) {
        val (prefix, suffix) = ends.get
        if (body.nonEmpty && (
                wrapping == Wrapping.Always || (wrapping != Wrapping.Never && length > lineGenerator.lengthRemaining)
            )) {
          lineGenerator.append(prefix)
          lineGenerator.endLine()

          val bodyGenerator = lineGenerator
            .derive(increaseIndent = true,
                    continuing = true,
                    newSpacing = Spacing.On,
                    newWrapping = wrapping)
          bodyGenerator.beginLine()
          bodyGenerator.append(body.get)
          bodyGenerator.endLine()

          lineGenerator.beginLine()
          lineGenerator.append(suffix)
        } else {
          val adjacentGenerator = lineGenerator.derive(newSpacing = spacing, newWrapping = wrapping)
          adjacentGenerator.appendPrefix(prefix)
          if (body.nonEmpty) {
            adjacentGenerator.append(body.get)
          }
          adjacentGenerator.appendSuffix(suffix)
        }
      } else if (body.isDefined) {
        lineGenerator.derive(newSpacing = spacing, newWrapping = wrapping).append(body.get)
      }
    }

    def body: Option[Composite]
  }

  private case class Container(items: Vector[Sized],
                               delimiter: Option[String] = None,
                               ends: Option[(Sized, Sized)] = None,
                               override val wrapping: Wrapping = Wrapping.AsNeeded,
                               continue: Boolean = true)
      extends Group(ends = ends, wrapping = wrapping) {

    override lazy val body: Option[Composite] = if (items.nonEmpty) {
      Some(
          Sequence(
              items.zipWithIndex.map {
                case (item, i) if i < items.size - 1 =>
                  if (delimiter.isDefined) {
                    val delimiterLiteral = Literal(delimiter.get)
                    Sequence(Vector(item, delimiterLiteral))
                  } else {
                    item
                  }
                case (item, _) => item
              },
              wrapping = wrapping,
              spacing = Spacing.On,
              continue = continue
          )
      )
    } else {
      None
    }
  }

  private case class KeyValue(key: Sized,
                              value: Sized,
                              delimiter: String = Symbols.KeyValueDelimiter)
      extends Composite {
    private val delimiterLiteral: Literal = Literal(delimiter)

    override def length: Int = key.length + delimiterLiteral.length + value.length + 1

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator
        .derive(newWrapping = Wrapping.Never, newSpacing = Spacing.On)
        .appendAll(Vector(Sequence(Vector(key, delimiterLiteral)), value))
    }
  }

  private object DataType {
    def buildDataType(name: String,
                      quantifiers: Vector[Sized],
                      inner1: Option[Sized] = None,
                      inner2: Option[Sized] = None): Sized = {
      val nameLiteral: Literal = Literal(name)
      if (inner1.isDefined) {
        val openLiteral = Literal(Symbols.TypeParamOpen)
        val prefix = Sequence(Vector(nameLiteral, openLiteral))
        val suffix = if (quantifiers.nonEmpty) {
          Sequence(Vector(Literal(Symbols.TypeParamClose)) ++ quantifiers)
        } else {
          Literal(Symbols.TypeParamClose)
        }
        Container(
            Vector(inner1, inner2).flatten,
            Some(Symbols.ArrayDelimiter),
            Some((prefix, suffix))
        )
      } else if (quantifiers.nonEmpty) {
        Sequence(Vector(nameLiteral) ++ quantifiers)
      } else {
        nameLiteral
      }
    }

    private def isPrimitiveType(wdlType: T): Boolean = {
      wdlType match {
        case T_String    => true
        case T_Boolean   => true
        case T_Int       => true
        case T_Float     => true
        case T_File      => true
        case T_Directory => true
        case _           => false
      }
    }

    def fromWdlType(wdlType: T, quantifiers: Vector[Sized] = Vector.empty): Sized = {
      wdlType match {
        case T_Optional(inner) =>
          fromWdlType(inner, quantifiers = Vector(Literal(Symbols.Optional)))
        case T_String    => buildDataType(Symbols.StringType, quantifiers)
        case T_Boolean   => buildDataType(Symbols.BooleanType, quantifiers)
        case T_Int       => buildDataType(Symbols.IntType, quantifiers)
        case T_Float     => buildDataType(Symbols.FloatType, quantifiers)
        case T_File      => buildDataType(Symbols.FileType, quantifiers)
        case T_Directory => buildDataType(Symbols.DirectoryType, quantifiers)
        case T_Array(inner, nonEmpty) =>
          val quant = if (nonEmpty) {
            Vector(Literal(Symbols.NonEmpty))
          } else {
            Vector.empty
          }
          buildDataType(Symbols.ArrayType, quant ++ quantifiers, Some(fromWdlType(inner)))
        case T_Map(keyType, valueType) if isPrimitiveType(keyType) =>
          buildDataType(Symbols.MapType,
                        quantifiers,
                        Some(fromWdlType(keyType)),
                        Some(fromWdlType(valueType)))
        case T_Pair(left, right) =>
          buildDataType(Symbols.PairType,
                        quantifiers,
                        Some(fromWdlType(left)),
                        Some(fromWdlType(right)))
        case T_Object          => buildDataType(Symbols.ObjectType, quantifiers)
        case T_Struct(name, _) => buildDataType(name, quantifiers)
        case other             => throw new Exception(s"Unrecognized type $other")
      }
    }
  }

  private case class Operation(oper: String,
                               lhs: Sized,
                               rhs: Sized,
                               grouped: Boolean = false,
                               inString: Boolean)
      extends Group(ends = if (grouped) {
        Some(Literal(Symbols.GroupOpen), Literal(Symbols.GroupClose))
      } else {
        None
      }, wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded)
      with Composite {

    override lazy val body: Option[Composite] = {
      val operLiteral = Literal(oper)
      Some(Sequence(Vector(lhs, operLiteral, rhs), wrapping = wrapping, spacing = Spacing.On))
    }
  }

  private case class Placeholder(value: Sized,
                                 open: String = Symbols.PlaceholderOpenDollar,
                                 close: String = Symbols.PlaceholderClose,
                                 options: Option[Vector[Sized]] = None,
                                 inString: Boolean)
      extends Group(
          ends = Some(Literal(open), Literal(close)),
          wrapping = if (inString) Wrapping.Never else Wrapping.AsNeeded,
          spacing = if (inString) Spacing.Off else Spacing.On
      )
      with Composite {

    override lazy val body: Option[Composite] = Some(
        Sequence(
            options.getOrElse(Vector.empty) ++ Vector(value),
            wrapping = wrapping,
            spacing = Spacing.On
        )
    )
  }

  private case class CompoundString(sizeds: Vector[Sized], quoting: Boolean) extends Composite {
    override lazy val length: Int = sizeds
      .map(_.length)
      .sum + (if (quoting) 2 else 0)

    override def generateContents(lineGenerator: LineGenerator): Unit = {
      val unspacedFormatter =
        lineGenerator.derive(newWrapping = Wrapping.Never, newSpacing = Spacing.Off)
      if (quoting) {
        unspacedFormatter.appendPrefix(
            Literal(Symbols.QuoteOpen)
        )
        unspacedFormatter.appendAll(sizeds)
        unspacedFormatter.appendSuffix(
            Literal(Symbols.QuoteClose)
        )
      } else {
        unspacedFormatter.appendAll(sizeds)
      }
    }
  }

  private def buildExpression(
      expr: Expr,
      placeholderOpen: String = Symbols.PlaceholderOpenDollar,
      inStringOrCommand: Boolean = false,
      inPlaceholder: Boolean = false,
      inOperation: Boolean = false,
      parentOperation: Option[String] = None,
      stringModifier: Option[String => String] = None
  ): Sized = {
    // Builds an expression that occurs nested within another expression. By default, passes
    //all the current parameter values to the nested call.
    // @param nestedExpression the nested Expr
    // @param placeholderOpen  override the current value of `placeholderOpen`
    // @param inString         override the current value of `inString`
    // @param inPlaceholder    override the current value of `inPlaceholder`
    // @param inOperation      override the current value of `inOperation`
    // @param parentOperation  if `inOperation` is true, this is the parent operation - nested
    //                         same operations are not grouped.
    // @return a Sized
    def nested(nestedExpression: Expr,
               placeholderOpen: String = placeholderOpen,
               inString: Boolean = inStringOrCommand,
               inPlaceholder: Boolean = inPlaceholder,
               inOperation: Boolean = inOperation,
               parentOperation: Option[String] = None): Sized = {
      buildExpression(
          nestedExpression,
          placeholderOpen = placeholderOpen,
          inStringOrCommand = inString,
          inPlaceholder = inPlaceholder,
          inOperation = inOperation,
          parentOperation = parentOperation,
          stringModifier = stringModifier
      )
    }

    def option(name: String, value: Expr): Sized = {
      val nameLiteral = Literal(name)
      val eqLiteral = Literal(Symbols.Assignment)
      val exprSized = nested(value, inPlaceholder = true)
      Sequence(Vector(nameLiteral, eqLiteral, exprSized))
    }

    expr match {
      // literal values
      case ValueNone(_, _) => Literal(Symbols.None)
      case ValueString(value, _, _) =>
        val v = if (stringModifier.isDefined) {
          stringModifier.get(value)
        } else {
          value
        }
        Literal(v, quoting = inPlaceholder || !inStringOrCommand)
      case ValueBoolean(value, _, _) => Literal(value)
      case ValueInt(value, _, _)     => Literal(value)
      case ValueFloat(value, _, _)   => Literal(value)
      case ExprPair(left, right, _, _) if !(inStringOrCommand || inPlaceholder) =>
        Container(
            Vector(nested(left), nested(right)),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.GroupOpen), Literal(Symbols.GroupClose))
        )
      case ExprArray(value, _, _) =>
        Container(
            value.map(nested(_)),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ArrayLiteralOpen), Literal(Symbols.ArrayLiteralClose))
        )
      case ExprMap(value, _, _) =>
        Container(
            value.map {
              case (k, v) => KeyValue(nested(k), nested(v))
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.MapOpen), Literal(Symbols.MapClose)),
            Wrapping.Always,
            continue = false
        )
      case ExprObject(value, _, _) =>
        Container(
            value.map {
              case (ValueString(k, _, _), v) =>
                KeyValue(Literal(k), nested(v))
              case other =>
                throw new Exception(s"invalid object member ${other}")
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Sequence(Vector(Literal(Symbols.Object), Literal(Symbols.ObjectOpen)),
                          spacing = Spacing.On),
                 Literal(Symbols.ObjectClose)),
            Wrapping.Always,
            continue = false
        )
      // placeholders
      case ExprPlaceholderCondition(t, f, value, _, _) =>
        Placeholder(
            nested(value, inPlaceholder = true),
            placeholderOpen,
            options = Some(
                Vector(
                    option(Symbols.TrueOption, t),
                    option(Symbols.FalseOption, f)
                )
            ),
            inString = inStringOrCommand
        )
      case ExprPlaceholderDefault(default, value, _, _) =>
        Placeholder(nested(value, inPlaceholder = true),
                    placeholderOpen,
                    options = Some(Vector(option(Symbols.DefaultOption, default))),
                    inString = inStringOrCommand)
      case ExprPlaceholderSep(sep, value, _, _) =>
        Placeholder(nested(value, inPlaceholder = true),
                    placeholderOpen,
                    options = Some(Vector(option(Symbols.SepOption, sep))),
                    inString = inStringOrCommand)
      case ExprCompoundString(value, _, _) if !inPlaceholder =>
        // Often/always an ExprCompoundString contains one or more empty
        // ValueStrings that we want to get rid of because they're useless
        // and can mess up formatting
        val filteredExprs = value.filter {
          case ValueString(s, _, _) => s.nonEmpty
          case _                    => true
        }
        CompoundString(filteredExprs.map(nested(_, inString = true)), quoting = !inStringOrCommand)
      // other expressions need to be wrapped in a placeholder if they
      // appear in a string or command block
      case other =>
        val sized = other match {
          case ExprIdentifier(id, _, _) => Literal(id)
          case ExprAt(array, index, _, _) =>
            val arraySized = nested(array, inPlaceholder = inStringOrCommand)
            val prefix = Sequence(
                Vector(arraySized, Literal(Symbols.IndexOpen))
            )
            val suffix = Literal(Symbols.IndexClose)
            Container(
                Vector(nested(index, inPlaceholder = inStringOrCommand)),
                Some(Symbols.ArrayDelimiter),
                Some(prefix, suffix)
            )
          case ExprIfThenElse(cond, tBranch, fBranch, _, _) =>
            val condSized = nested(cond, inOperation = false, inPlaceholder = inStringOrCommand)
            val tSized = nested(tBranch, inOperation = false, inPlaceholder = inStringOrCommand)
            val fSized = nested(fBranch, inOperation = false, inPlaceholder = inStringOrCommand)
            Container(
                Vector(
                    Literal(Symbols.If),
                    condSized,
                    Literal(Symbols.Then),
                    tSized,
                    Literal(Symbols.Else),
                    fSized
                ),
                wrapping = Wrapping.AsNeeded
            )
          case ExprApply(oper, _, Vector(value), _, _) if Operator.All.contains(oper) =>
            val symbol = Operator.All(oper).symbol
            Sequence(Vector(Literal(symbol), nested(value, inOperation = true)))
          case ExprApply(oper, _, Vector(lhs, rhs), _, _) if Operator.All.contains(oper) =>
            val symbol = Operator.All(oper).symbol
            Operation(
                symbol,
                nested(lhs,
                       inPlaceholder = inStringOrCommand,
                       inOperation = true,
                       parentOperation = Some(oper)),
                nested(rhs,
                       inPlaceholder = inStringOrCommand,
                       inOperation = true,
                       parentOperation = Some(oper)),
                grouped = inOperation && !parentOperation.contains(oper),
                inString = inStringOrCommand
            )
          case ExprApply(funcName, _, elements, _, _) =>
            val prefix = Sequence(
                Vector(Literal(funcName), Literal(Symbols.FunctionCallOpen))
            )
            val suffix = Literal(Symbols.FunctionCallClose)
            Container(
                elements.map(nested(_, inPlaceholder = inStringOrCommand)),
                Some(Symbols.ArrayDelimiter),
                Some(prefix, suffix)
            )
          case ExprGetName(e, id, _, _) =>
            val exprSized = nested(e, inPlaceholder = inStringOrCommand)
            val idLiteral = Literal(id)
            Sequence(
                Vector(exprSized, Literal(Symbols.Access), idLiteral)
            )
          case other => throw new Exception(s"Unrecognized expression $other")
        }
        if (inStringOrCommand && !inPlaceholder) {
          Placeholder(sized, placeholderOpen, inString = inStringOrCommand)
        } else {
          sized
        }
    }
  }

  /**
    * Marker base class for Statements.
    */
  private trait Statement {

    /**
      * Format this statement. The `lineGenerator` must have `isLineBegun == false` on
      * both entry and exit.
      *
      * @param lineGenerator the lineGenerator
      */
    def format(lineGenerator: LineGenerator): Unit
  }

  private abstract class BaseStatement extends Statement {

    override def format(lineGenerator: LineGenerator): Unit = {
      lineGenerator.beginLine()
      formatContents(lineGenerator)
      lineGenerator.endLine()
    }

    /**
      * Format the contents of this statement. The `lineGenerator` must have
      * `isLineBegun == true` on both entry and exit.
      */
    protected def formatContents(lineGenerator: LineGenerator): Unit
  }

  private case class VersionStatement(version: Version) extends Statement {
    private val keywordToken = Literal(Symbols.Version)
    private val versionToken = Literal(WdlVersion.V1.name)

    override def format(lineGenerator: LineGenerator): Unit = {
      lineGenerator.beginLine()
      lineGenerator
        .derive(newWrapping = Wrapping.Never)
        .appendAll(Vector(keywordToken, versionToken))
      lineGenerator.endLine()
    }
  }

  private case class ImportStatement(importDoc: ImportDoc) extends BaseStatement {
    private val keywordToken = Literal(Symbols.Import)
    private val uriLiteral = Literal(importDoc.addr)
    private val nameTokens = Vector(Literal(Symbols.As), Literal(importDoc.namespace))
    private val aliasTokens = importDoc.aliases.map { alias =>
      Vector(Literal(Symbols.Alias), Literal(alias.id1), Literal(Symbols.As), Literal(alias.id2))
    }

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator
        .derive(newWrapping = Wrapping.Never)
        .appendAll(Vector(keywordToken, uriLiteral))
      lineGenerator.appendAll(nameTokens)
      aliasTokens.foreach { alias =>
        lineGenerator.derive(newWrapping = Wrapping.Always).appendAll(alias)
      }
    }
  }

  private case class Section(statements: Vector[Statement],
                             emtpyLineBetweenStatements: Boolean = false)
      extends Statement {
    override def format(lineGenerator: LineGenerator): Unit = {
      statements.head.format(lineGenerator)
      statements.tail.foreach { section =>
        if (emtpyLineBetweenStatements) {
          lineGenerator.emptyLine()
        }
        section.format(lineGenerator)
      }
    }
  }

  private case class DeclarationStatement(name: String, wdlType: T, expr: Option[Expr] = None)
      extends BaseStatement {

    private val typeSized = DataType.fromWdlType(wdlType)
    private val nameLiteral = Literal(name)
    private val lhs = Vector(typeSized, nameLiteral)
    private val rhs = expr.map { e =>
      val eqToken = Literal(Symbols.Assignment)
      val exprAtom = buildExpression(e)
      Vector(eqToken, exprAtom)
    }

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(lhs)
      if (rhs.isDefined) {
        lineGenerator.appendAll(rhs.get)
      }
    }
  }

  private abstract class BlockStatement(keyword: String) extends Statement {
    def clause: Option[Sized] = None

    def body: Option[Statement] = None

    protected val keywordLiteral: Literal = Literal(keyword)

    private val clauseSized: Option[Sized] = clause
    // assume the open brace is on the same line as the keyword/clause
    private val openLiteral =
      Literal(Symbols.BlockOpen)
    private val bodyStatement: Option[Statement] = body
    private val closeLiteral = Literal(Symbols.BlockClose)

    override def format(lineGenerator: LineGenerator): Unit = {
      lineGenerator.beginLine()
      lineGenerator.appendAll(Vector(Some(keywordLiteral), clauseSized, Some(openLiteral)).flatten)
      if (bodyStatement.isDefined) {
        lineGenerator.endLine()
        bodyStatement.get.format(lineGenerator.derive(increaseIndent = true))
        lineGenerator.beginLine()
      }
      lineGenerator.append(closeLiteral)
      lineGenerator.endLine()
    }
  }

  private case class InputsBlock(inputs: Vector[InputParameter])
      extends BlockStatement(Symbols.Input) {
    override def body: Option[Statement] =
      Some(Section(inputs.map {
        case RequiredInputParameter(name, wdlType, _) => DeclarationStatement(name, wdlType)
        case OverridableInputParameterWithDefault(name, wdlType, defaultExpr, _) =>
          DeclarationStatement(name, wdlType, Some(defaultExpr))
        case OptionalInputParameter(name, wdlType, _) => DeclarationStatement(name, wdlType)
      }))
  }

  private def buildMeta(metaValue: MetaValue): Sized = {
    metaValue match {
      // literal values
      case MetaValueNull(_) => Literal(Symbols.Null)
      case MetaValueString(value, _) =>
        Literal(value, quoting = true)
      case MetaValueBoolean(value, _) => Literal(value)
      case MetaValueInt(value, _)     => Literal(value)
      case MetaValueFloat(value, _)   => Literal(value)
      case MetaValueArray(value, _) =>
        Container(
            value.map(buildMeta),
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ArrayLiteralOpen), Literal(Symbols.ArrayLiteralClose)),
            continue = false
        )
      case MetaValueObject(value, _) =>
        Container(
            value.map {
              case (name, value) => KeyValue(Literal(name), buildMeta(value))
            }.toVector,
            Some(Symbols.ArrayDelimiter),
            Some(Literal(Symbols.ObjectOpen), Literal(Symbols.ObjectClose)),
            Wrapping.Always,
            continue = false
        )
    }
  }

  private case class StructBlock(struct: StructDefinition) extends BlockStatement(Symbols.Struct) {
    override def clause: Option[Sized] = Some(
        Literal(struct.name)
    )

    override def body: Option[Statement] =
      Some(Section(struct.members.map {
        case (name, wdlType) => DeclarationStatement(name, wdlType)
      }.toVector))
  }

  private case class OutputsBlock(outputs: Vector[OutputParameter])
      extends BlockStatement(Symbols.Output) {
    override def body: Option[Statement] =
      Some(Section(outputs.map { output =>
        DeclarationStatement(output.name, output.wdlType, Some(output.expr))
      }))
  }

  private case class MetaKVStatement(id: String, value: MetaValue) extends BaseStatement {
    private val idToken = Literal(id)
    private val delimToken = Literal(Symbols.KeyValueDelimiter)
    private val lhs = Vector(idToken, delimToken)
    private val rhs = buildMeta(value)

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.derive(newWrapping = Wrapping.Never).appendAll(Vector(Sequence(lhs), rhs))
    }
  }

  private case class MetaBlock(keyword: String, kvs: Map[String, MetaValue])
      extends BlockStatement(keyword) {
    override def body: Option[Statement] =
      Some(Section(kvs.map {
        case (k, v) => MetaKVStatement(k, v)
      }.toVector))
  }

  private def splitWorkflowElements(elements: Vector[WorkflowElement]): Vector[Statement] = {
    var statements: Vector[Statement] = Vector.empty
    var declarations: Vector[PrivateVariable] = Vector.empty

    elements.foreach {
      case declaration: PrivateVariable => declarations :+= declaration
      case other =>
        if (declarations.nonEmpty) {
          statements :+= Section(declarations.map { decl =>
            DeclarationStatement(decl.name, decl.wdlType, decl.expr)
          })
          declarations = Vector.empty
        }
        statements :+= (other match {
          case call: Call               => CallBlock(call)
          case scatter: Scatter         => ScatterBlock(scatter)
          case conditional: Conditional => ConditionalBlock(conditional)
          case other                    => throw new Exception(s"Unexpected workflow body element $other")
        })
    }

    if (declarations.nonEmpty) {
      statements :+= Section(declarations.map { decl =>
        DeclarationStatement(decl.name, decl.wdlType, decl.expr)
      })
    }

    statements
  }

  private case class CallInputsStatement(inputs: Map[String, Expr]) extends BaseStatement {
    private val key = Literal(Symbols.Input)
    private val value = inputs.flatMap {
      case (_, ValueNone(_, _)) if omitNullInputs => None
      case (name, expr) =>
        val nameToken = Literal(name)
        val exprSized = buildExpression(expr)
        Some(
            Container(
                Vector(nameToken, Literal(Symbols.Assignment), exprSized)
            )
        )
    }.toVector

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      val kv = KeyValue(
          key,
          Container(value,
                    delimiter = Some(s"${Symbols.ArrayDelimiter}"),
                    wrapping = Wrapping.Always)
      )
      kv.generateContents(lineGenerator)
    }
  }

  private case class CallBlock(call: Call) extends BlockStatement(Symbols.Call) {
    override def clause: Option[Sized] = Some(
        if (call.alias.isDefined) {
          val alias = call.alias.get
          // assuming all parts of the clause are adjacent
          val tokens =
            Vector(Literal(call.fullyQualifiedName), Literal(Symbols.As), Literal(alias))
          Container(tokens)
        } else {
          Literal(call.actualName)
        }
    )

    override def body: Option[Statement] =
      if (call.inputs.nonEmpty) {
        Some(CallInputsStatement(call.inputs))
      } else {
        None
      }
  }

  private case class ScatterBlock(scatter: Scatter) extends BlockStatement(Symbols.Scatter) {
    override def clause: Option[Sized] = {
      // assuming all parts of the clause are adjacent
      val openToken = Literal(Symbols.GroupOpen)
      val idToken = Literal(scatter.identifier)
      val inToken = Literal(Symbols.In)
      val exprAtom = buildExpression(scatter.expr)
      val closeToken = Literal(Symbols.GroupClose)
      Some(
          Container(
              Vector(idToken, inToken, exprAtom),
              ends = Some(openToken, closeToken)
          )
      )
    }

    override def body: Option[Statement] =
      Some(Section(splitWorkflowElements(scatter.body), emtpyLineBetweenStatements = true))
  }

  private case class ConditionalBlock(conditional: Conditional) extends BlockStatement(Symbols.If) {
    override def clause: Option[Sized] = {
      val exprAtom = buildExpression(conditional.expr)
      val openToken = Literal(Symbols.GroupOpen)
      val closeToken = Literal(Symbols.GroupClose)
      Some(
          Container(
              Vector(exprAtom),
              ends = Some(openToken, closeToken)
          )
      )
    }

    override def body: Option[Statement] =
      Some(Section(splitWorkflowElements(conditional.body), emtpyLineBetweenStatements = true))
  }

  private case class WorkflowBlock(workflow: Workflow) extends BlockStatement(Symbols.Workflow) {
    override def clause: Option[Sized] = Some(Literal(workflow.name))

    override def body: Option[Statement] = {
      val statements: Vector[Statement] = {
        val inputs = if (workflow.inputs.nonEmpty) {
          Some(InputsBlock(workflow.inputs))
        } else {
          None
        }
        val outputs = if (workflow.outputs.nonEmpty) {
          Some(OutputsBlock(workflow.outputs))
        } else {
          None
        }
        val bodySection = if (workflow.body.nonEmpty) {
          Some(Section(splitWorkflowElements(workflow.body), emtpyLineBetweenStatements = true))
        } else {
          None
        }
        Vector(
            inputs,
            bodySection,
            outputs,
            workflow.meta.map(meta => MetaBlock(Symbols.Meta, meta.kvs)),
            workflow.parameterMeta.map(paramMeta => MetaBlock(Symbols.ParameterMeta, paramMeta.kvs))
        ).flatten
      }
      Some(Section(statements, emtpyLineBetweenStatements = true))
    }
  }

  private case class CommandBlock(command: CommandSection) extends BaseStatement {
    // The command block is considered "preformatted" in that we don't try to reformat it.
    // However, we do need to try to indent it correclty. We do this by detecting the amount
    // of indent used on the first non-empty line and remove that from every line and replace
    // it by the lineGenerator's current indent level.
    private val commandStartRegexp = "(?s)^([^\n\r]*)[\n\r]*(.*)$".r
    private val leadingWhitespaceRegexp = "(?s)^([ \t]*)(.*)$".r
    private val commandEndRegexp = "\n*\\s*$".r

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(
          Vector(Literal(Symbols.Command), Literal(Symbols.CommandOpen))
      )
      if (command.parts.nonEmpty) {
        lineGenerator.endLine()

        // The parser swallows anyting after the opening token ('{' or '<<<') as part of the comment
        // block, so we need to parse out any in-line comment. Also determine whether we should try
        // to trim off leading whitespace or just leave as-is.
        val (headExpr: Expr, indent) = command.parts.head match {
          case ValueString(value, wdlType, text) =>
            value match {
              case commandStartRegexp(first, rest) =>
                first.trim match {
                  case s
                      if (
                          s.isEmpty || s.startsWith(Symbols.Comment)
                      ) && rest.trim.isEmpty && command.parts.size == 1 =>
                    // command block is empty
                    (ValueString("", wdlType, text), None)
                  case s if (s.isEmpty || s.startsWith(Symbols.Comment)) && rest.trim.isEmpty =>
                    // weird case, like there is a placeholder in the comment - we don't want to break
                    // anything so we'll just format the whole block as-is
                    (s, None)
                  case s if s.isEmpty || s.startsWith(Symbols.Comment) =>
                    // opening line was empty or a comment
                    val (ws, trimmedRest) = rest match {
                      case leadingWhitespaceRegexp(ws, trimmedRest) => (Some(ws), trimmedRest)
                      case _                                        => (None, rest)
                    }
                    // the first line will be indented, so we need to trim the indent from `rest`
                    (ValueString(trimmedRest, wdlType, text), ws)
                  case s if rest.trim.isEmpty =>
                    // single-line expression
                    (ValueString(s, wdlType, text), None)
                  case s =>
                    // opening line has some real content, so just trim any leading whitespace
                    val ws = leadingWhitespaceRegexp
                      .findFirstMatchIn(rest)
                      .map(m => m.group(1))
                    (ValueString(s"${s}\n${rest}", wdlType, text), ws)
                }
              case _ => throw new RuntimeException("sanity")
            }
          case other => (other, None)
        }

        def trimLast(last: Expr): Expr = {
          last match {
            case ValueString(s, wdlType, text) =>
              // If the last part is just the whitespace before the close block, throw it out
              ValueString(commandEndRegexp.replaceFirstIn(s, ""), wdlType, text)
            case other =>
              other
          }
        }

        val newParts = if (command.parts.size == 1) {
          Vector(trimLast(headExpr))
        } else {
          val last = Vector(trimLast(command.parts.last))
          Vector(headExpr) ++ (
              if (command.parts.size == 2) {
                last
              } else {
                command.parts.slice(1, command.parts.size - 1) ++ last
              }
          )
        }

        val bodyGenerator = lineGenerator.derive(increaseIndent = true,
                                                 newSpacing = Spacing.Off,
                                                 newWrapping = Wrapping.Never)

        val replaceIndent = indent.map { ws =>
          // Function to replace indenting in command block expressions with the current
          // indent level of the formatter
          val indentRegexp = s"\n${ws}".r
          val replacement = s"\n${bodyGenerator.getIndent()}"
          (s: String) => indentRegexp.replaceAllIn(s, replacement)
        }

        bodyGenerator.beginLine()
        newParts.foreach { expr =>
          bodyGenerator.append(
              buildExpression(
                  expr,
                  placeholderOpen = Symbols.PlaceholderOpenTilde,
                  inStringOrCommand = true,
                  stringModifier = replaceIndent
              )
          )
        }
        bodyGenerator.endLine()

        lineGenerator.beginLine()
      }

      lineGenerator.append(Literal(Symbols.CommandClose))
    }
  }

  private case class KVStatement(id: String, expr: Expr) extends BaseStatement {
    private val idToken = Literal(id)
    private val delimToken = Literal(Symbols.KeyValueDelimiter)
    private val lhs = Vector(idToken, delimToken)
    private val rhs = buildExpression(expr)

    override def formatContents(lineGenerator: LineGenerator): Unit = {
      lineGenerator.appendAll(Vector(Sequence(lhs), rhs))
    }
  }

  private case class RuntimeBlock(runtime: RuntimeSection) extends BlockStatement(Symbols.Runtime) {
    override def body: Option[Statement] =
      Some(Section(runtime.kvs.map {
        case (name, expr) => KVStatement(name, expr)
      }.toVector))
  }

  private case class TaskBlock(task: Task) extends BlockStatement(Symbols.Task) {
    override def clause: Option[Sized] =
      Some(Literal(task.name))

    override def body: Option[Statement] = {
      val statements: Vector[Statement] = {
        val inputs = task.inputs match {
          case v: Vector[InputParameter] if v.nonEmpty => Some(InputsBlock(v))
          case _                                       => None

        }
        val decls = task.privateVariables match {
          case v: Vector[PrivateVariable] if v.nonEmpty =>
            Some(Section(v.map { decl =>
              DeclarationStatement(decl.name, decl.wdlType, decl.expr)
            }))
          case _ => None
        }
        val outputs = task.outputs match {
          case v: Vector[OutputParameter] if v.nonEmpty => Some(OutputsBlock(v))
          case _                                        => None
        }
        Vector(
            inputs,
            decls,
            Some(CommandBlock(task.command)),
            outputs,
            task.runtime.map(RuntimeBlock),
            task.meta.map(meta => MetaBlock(Symbols.Meta, meta.kvs)),
            task.parameterMeta.map(paramMeta => MetaBlock(Symbols.ParameterMeta, paramMeta.kvs))
        ).flatten
      }
      Some(Section(statements, emtpyLineBetweenStatements = true))
    }
  }

  private case class DocumentSections(document: Document) extends Statement {
    override def format(lineGenerator: LineGenerator): Unit = {
      // the version statement must be the first line in the file
      // so we start the section after appending it just in case
      // there were comments at the top of the source file
      val versionStatement = VersionStatement(document.version)
      versionStatement.format(lineGenerator)

      val imports = document.elements.collect { case imp: ImportDoc => imp }
      if (imports.nonEmpty) {
        lineGenerator.emptyLine()
        Section(imports.map(ImportStatement)).format(lineGenerator)
      }

      document.elements
        .collect {
          case struct: StructDefinition => StructBlock(struct)
        }
        .foreach { struct =>
          lineGenerator.emptyLine()
          struct.format(lineGenerator)
        }

      if (document.workflow.isDefined) {
        lineGenerator.emptyLine()
        WorkflowBlock(document.workflow.get).format(lineGenerator)
      }

      document.elements
        .collect {
          case task: Task => TaskBlock(task)
        }
        .foreach { task =>
          lineGenerator.emptyLine()
          task.format(lineGenerator)
        }
    }
  }

  def generateElement(element: Element,
                      headerLines: Vector[String] = Vector.empty): Vector[String] = {
    val stmt = element match {
      case d: Document => DocumentSections(d)
      case t: Task     => TaskBlock(t)
      case w: Workflow => WorkflowBlock(w)
      case other =>
        throw new RuntimeException(s"Formatting element of type ${other.getClass} not supported")
    }
    val lineGenerator = LineGenerator()
    stmt.format(lineGenerator)
    val headerComments = headerLines.map(s => s"# ${s}")
    val lines = lineGenerator.toVector
    headerComments ++ lines
  }

  def generateDocument(document: Document,
                       headerComment: Vector[String] = Vector.empty): Vector[String] = {
    generateElement(document, headerComment)
  }
}
