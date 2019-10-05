/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.expr

import mod.diesel.model.exec.EESnakk
import org.json.JSONObject
import razie.diesel.dom.RDOM.{P, PValue}
import razie.diesel.dom._

/** a json block */
case class JBlockExpr(ex: List[(String, Expr)]) extends Expr {
  val expr = "{" + ex.map(t=>t._1 + ":" + t._2.toString).mkString(",") + "}"

  override def apply(v: Any)(implicit ctx: ECtx) = applyTyped(v).currentStringValue

  override def applyTyped(v: Any)(implicit ctx: ECtx) = {
    // todo this can be way faster for a few types, like Array - $send ctx.set (state281 = {source: [0,1,2,3,4,5], dest: [], aux: []})
//    val orig = template(expr)
    val orig = ex
      .map(t=> (t._1, t._2.applyTyped(v)))
      .map(t=> (t._1, t._2 match {
        case p@P(n,d,WTypes.NUMBER, _, _, _, Some(PValue(i:Int, _, _))) => i
        case p@P(n,d,WTypes.NUMBER, _, _, _, Some(PValue(i:Double, _, _))) => i

        case p@P(n,d,WTypes.BOOLEAN, _, _, _, Some(PValue(b:Boolean, _, _))) => b

        case p:P => p.currentStringValue match {
          case i: String if i.trim.startsWith("[") && i.trim.endsWith("]") => i
          case i: String if i.trim.startsWith("{") && i.trim.endsWith("}") => i
          case i: String => "\"" + i + "\""
        }

      }))
      .map(t=> s""" "${t._1}" : ${t._2} """)
      .mkString(",")
    // parse and clean it up so it blows up right here if invalid
    val j = new JSONObject(s"{$orig}")
    P.fromTypedValue("", j, WTypes.JSON)
//    new JSONObject(s"{$orig}").toString(2)
  }

  override def getType: String = WTypes.JSON

  // replace ${e} with value
  def template(s: String)(implicit ctx: ECtx) = {

    EESnakk.prepStr2(s, Nil)
  }
}

/** a json array */
case class JArrExpr(ex: List[Expr]) extends Expr {
  val expr = "[" + ex.mkString(",") + "]"

  override def apply(v: Any)(implicit ctx: ECtx) = {
//    val orig = template(expr)
    val orig = ex.map(_.apply(v)).mkString(",")
    // parse and clean it up so it blows up right here if invalid
    new org.json.JSONArray(s"[$orig]").toString()
  }

  override def applyTyped(v: Any)(implicit ctx: ECtx): P = {
    val orig = ex.map(_.apply(v)).mkString(",")
    // parse and clean it up so it blows up right here if invalid
    val ja = new org.json.JSONArray(s"[$orig]")
    P.fromTypedValue("", ja)
  }

  override def getType: String = WTypes.ARRAY

  // replace ${e} with value
  def template(s: String)(implicit ctx: ECtx) = {

    EESnakk.prepStr2(s, Nil)
  }

}

