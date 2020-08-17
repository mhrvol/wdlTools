package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.SourceLocation
import wdlTools.types.WdlTypes

object Utils {
  // sUnit is a units parameter (KB, KiB, MB, GiB, ...)
  def sizeUnit(sUnit: String, loc: SourceLocation): Double = {
    sUnit.toLowerCase match {
      case "b"   => 1
      case "kb"  => 1000d
      case "mb"  => 1000d * 1000d
      case "gb"  => 1000d * 1000d * 1000d
      case "tb"  => 1000d * 1000d * 1000d * 1000d
      case "kib" => 1024d
      case "mib" => 1024d * 1024d
      case "gib" => 1024d * 1024d * 1024d
      case "tib" => 1024d * 1024d * 1024d * 1024d
      case _     => throw new EvalException(s"Unknown unit ${sUnit}", loc)
    }
  }

  private val stringSizeRegexp = "([\\d.]+)(?:\\s*(.+))?".r

  def sizeToFloat(size: Double, suffix: String, loc: SourceLocation): Double = {
    size * sizeUnit(suffix, loc)
  }

  def sizeStringToFloat(sizeString: String,
                        loc: SourceLocation,
                        defaultSuffix: String = "b"): Double = {
    sizeString match {
      case stringSizeRegexp(d, u) if u == null => d.toDouble * sizeUnit(defaultSuffix, loc)
      case stringSizeRegexp(d, u)              => d.toDouble * sizeUnit(u, loc)
      case other                               => throw new EvalException(s"Invalid size string ${other}", loc)
    }
  }

  def floatToInt(d: Double): Long = {
    Math.ceil(d).toLong
  }

  def checkTypes(id: String,
                 userTypes: Vector[WdlTypes.T] = Vector.empty,
                 allowedTypes: Vector[WdlTypes.T] = Vector.empty): Vector[WdlTypes.T] = {
    if (userTypes.isEmpty) {
      allowedTypes
    } else {
      if (allowedTypes.nonEmpty) {
        val extraTypes = userTypes.toSet -- allowedTypes.toSet
        if (extraTypes.nonEmpty) {
          throw new RuntimeException(
              s"Type(s) ${extraTypes.mkString(",")} not allowed for Runtime key ${id}"
          )
        }
      }
      userTypes
    }
  }

  // TODO: within string interpolation, V_Null should render as empty string
  @scala.annotation.tailrec
  def primitiveValueToString(wv: V, loc: SourceLocation): String = {
    wv match {
      case V_Null           => "null"
      case V_Boolean(value) => value.toString
      case V_Int(value)     => value.toString
      case V_Float(value)   => value.toString
      case V_String(value)  => value
      case V_File(value)    => value
      case V_Optional(x)    => primitiveValueToString(x, loc)
      case other =>
        throw new EvalException(s"${other} is not a primitive value", loc)
    }
  }
}
