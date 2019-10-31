/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import razie.diesel.dom.RDOM.P
import razie.diesel.dom.{RDomain, WTypes}
import razie.diesel.engine.{DomAst, DomEngECtx}
import razie.diesel.ext.{EVal, EWarning}
import razie.tconf.{DSpec, DTemplate}
import scala.util.Try

/**
 * A map-like context of attribute values, used by the Diesel engine.
 * Also, most expression evaluators work within a context.
 *
 * These contexts are hierarchical, with inheritance and overwriting. Each engine has a root context. There are also
 * scope contexts (which don't allow propagation of values) etc
 *
 * They also capture a spec environment: a list of specs (could be drafts or a specific version)
 *
 * Also, they have an optional domain - this is used to source values and functions and other objects for expressions.
 * Normally, the domain is set by the engine at the root context level.
 *
 * todo keep a ref to the original specs, to get more details, so for the duration of this context, the configuration is the right version
 */
trait ECtx {

  /** root domain - it normally is an instance of DomEngineCtx and you can get more details from it */
  def root: DomEngECtx
  /** in a hierarchy, this is my failback */
  def base: Option[ECtx]
  def hostname:Option[String]

  def credentials: Option[String] // original credentials
  /** the domain - normally this is only set in the root EngineCtx */
  def domain: Option[RDomain]
  /** the specs for this engine - normally this is only set in the root EngineCtx */
  def specs: List[DSpec]

  /** find the template corresponding to the ea and direction (direction is optional
    *
    * @param ea entity.action
    * @param direction "request" vs "response"
    * @return
    */
  def findTemplate (ea:String, direction:String="") : Option[DTemplate]

  /** find template with predicate */
  def findTemplate (p : DTemplate => Boolean) : Option[DTemplate]

  def exists(f: scala.Function1[P, scala.Boolean]): scala.Boolean

  def remove (name: String): Option[P]
  def apply  (name: String): String = get(name).mkString
  def getp   (name: String): Option[P] // overwrite this one - leave the get
  def get    (name: String): Option[String] = getp(name).map(_.currentStringValue)
  def put    (p: P): Unit
  def putAll (p: List[P]): Unit
  def clear  : Unit
  def listAttrs: List[P]

  def getRequired   (name: String): String = getRequiredp(name).currentStringValue
  def getRequiredp  (name: String): P = {
    val p = getp(name)
    if(! p.isDefined) throw new IllegalArgumentException(s"'$name' not found!")
    p.get
  }

  def curNode : Option[DomAst]

  /** see if this is a qualified name in a structure
    * @deprecated - use AExprIdent instead
    */
  def sourceStruc (name:String, root:Option[Map[String,Any]] = None) : Option[P] = {
    val x:Option[_] = if (name contains ".") {
      try {
        val R = """([^.]+)\.(.*)""".r
        val R(n, rest) = name
        root.flatMap(_.get(n)).orElse {
          Try {
            val p = getp(n).filter(_.hasCurrentValue)
            val m =
              if (p.flatMap(_.value.map(_.value)).exists(_.isInstanceOf[Map[_, _]]))
                p.flatMap(_.value.map(_.value)).map(_.asInstanceOf[Map[_, _]])
              else {
                // if json try to parse it
                p.map(_.currentStringValue).map { v =>
                  if (v.trim.startsWith("{"))
                    razie.js.parse(v)
                  else Map.empty
                }
              }
            m
          }.recover {
            Map.empty
          }.get
        }.collect {
          case x: Map[_, _] => sourceStruc(rest, Some(x.asInstanceOf[Map[String, Any]]))
        }.flatten
      }
      catch {
        case t: Throwable => throw new IllegalArgumentException(s"Can't sourceStruc parm with name: ${name.take(100)}", t)
      }
    }
      else
      {
        val xxx = root.flatMap(_.get(name))
        val s = xxx.map(razie.js.anytojsons)
        s.map { s =>
          P(name, s, WTypes.typeOf(xxx.get)).withValue(xxx.get, WTypes.typeOf(xxx.get))
        }
      }

    if(x.exists(_.isInstanceOf[P]))
      Some(x.get.asInstanceOf[P])
    else
    // todo typed
      x.map(x=>P(name, x.toString))
  }
}

object ECtx {
  /** empty context */
  val empty = new StaticECtx()

  def apply (attrs:List[P]) = new StaticECtx(attrs)
}
