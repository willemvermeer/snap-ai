package snap.json

/**
 * Two-space indented, trailing-LF JSON output (SPEC.md §4.1: "Writers SHOULD use
 * two-space indentation and a trailing LF so repositories remain pleasant to inspect.").
 */
object JsonWriter {
  def write(json: Json): String = {
    val sb = new StringBuilder
    writeValue(json, sb, 0)
    sb.append('\n')
    sb.toString()
  }

  private def indent(sb: StringBuilder, level: Int): Unit = {
    var n = level
    while (n > 0) { sb.append("  "); n -= 1 }
  }

  private def writeValue(json: Json, sb: StringBuilder, level: Int): Unit = json match {
    case Json.Null => sb.append("null")
    case Json.Bool(b) => sb.append(if (b) "true" else "false")
    case Json.Num(raw, _) => sb.append(raw)
    case Json.Str(v) => writeString(v, sb)
    case Json.Arr(items) =>
      if (items.isEmpty) {
        sb.append("[]")
      } else {
        sb.append("[\n")
        val last = items.length - 1
        items.zipWithIndex.foreach { case (item, idx) =>
          indent(sb, level + 1)
          writeValue(item, sb, level + 1)
          if (idx < last) sb.append(',')
          sb.append('\n')
        }
        indent(sb, level)
        sb.append(']')
      }
    case Json.Obj(fields) =>
      if (fields.isEmpty) {
        sb.append("{}")
      } else {
        sb.append("{\n")
        val last = fields.length - 1
        fields.zipWithIndex.foreach { case ((k, v), idx) =>
          indent(sb, level + 1)
          writeString(k, sb)
          sb.append(": ")
          writeValue(v, sb, level + 1)
          if (idx < last) sb.append(',')
          sb.append('\n')
        }
        indent(sb, level)
        sb.append('}')
      }
  }

  private def writeString(v: String, sb: StringBuilder): Unit = {
    sb.append('"')
    v.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c.toInt < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"')
  }
}
