/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import akka.util.Timeout
import razie.diesel.dom.RDOM.P
import razie.diesel.dom.WTypes
import razie.diesel.engine._
import razie.diesel.engine.nodes._
import razie.diesel.expr.ECtx
import scala.concurrent.duration.DurationInt

object EEStreams {
  final val PREFIX = "diesel.stream"
}

/** executor for "ctx." messages - operations on the current context
  *
  * See
  * http://specs.dieselapps.com/Topic/Concurrency,_asynchronous_and_distributed
  */
class EEStreams extends EExecutor(EEStreams.PREFIX) {

  import razie.diesel.engine.exec.EEStreams.PREFIX

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == PREFIX && messages.exists(_.met == m.met)
    // don't eat .onDone...
  }

  /**
    * this is client-side: these operations go through the stream's actor
    */
  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    in.met match {

      case "new" => {
        val name = ctx.getRequired("stream")
        val batch = ctx.getp("batch").map(_.calculatedTypedValue.asBoolean).getOrElse(false)
        val batchSize = ctx.getp("batchSize").map(_.calculatedTypedValue.asLong.toInt).getOrElse(100)

        val others = in.attrs
            .filter(_.name != "stream")
            .filter(_.name != "batch")
            .filter(_.name != "batchSize")
            .map(_.calculatedP)

        val context = P.of("context", others.map(p => (p.name, p)).toMap)

        // todo factory for V1
        val warn = DieselAppContext.findStream(name).map { s =>
          // todo clean and remove the old one, sync
//          import akka.pattern.ask
//          implicit val timeout = Timeout(5 seconds)
//          DieselAppContext.activeActors.values.map(_ ? DEStop)
//          DieselAppContext ? DESDone(name)
          EWarning(s"Stream $name was open!! Closing, but some generator or consumer may still use it!")
        }.toList

        val s = DieselAppContext.mkStream(
          new DomStreamV1(ctx.root.engine.get, name, name, batch, batchSize, context))
        ctx.root.engine.get.evAppStream(s)

        warn ::: EInfo("stream - creating " + name) ::
            EVal(P.fromTypedValue(name, s, WTypes.wt.OBJECT)) ::
            Nil
      }

      case "put" => {
        val name = ctx.getRequired("stream")
        val parms = in.attrs.filter(_.name != "stream").map(_.calculatedP)
        val list = parms.map(_.calculatedTypedValue.value)
        DieselAppContext ! DESPut(name, list)
        EInfo(s"stream.put - put ${list.size} elements") :: Nil
      }

      case "putAll" => {
        val name = ctx.getRequired("stream")
        val parms = in.attrs.filter(_.name != "stream").map(_.calculatedP)
        val list = parms.flatMap(_.calculatedTypedValue.asArray.toList)
        DieselAppContext ! DESPut(name, list)
        EInfo(s"stream.put - put ${list.size} elements") :: Nil
      }

      case "generate" => {
        val name = ctx.getRequired("stream")
        val start = ctx.getRequired("start").toInt
        val end = ctx.getRequired("end").toInt
        val map = ctx.get("mapper")

        val list = (start to end).toList

        DieselAppContext ! DESPut(name, list)
        EInfo(s"stream.put - put ${list.size} elements") :: Nil
      }

      case "error" => {
        val name = ctx.getRequired("stream")
        val parms = in.attrs.filter(_.name != "stream").map(_.calculatedP)

        DieselAppContext ! DESError(name, parms)

        EInfo(s"stream.done") :: Nil
      }

      case "done" => {
        val name = ctx.getRequired("stream")

        val stream = DieselAppContext.findStream(name)

        DieselAppContext ! DESDone(name)

        val found = stream.map(_.name).mkString
        val consumed = stream.map(_.getIsConsumed).mkString
        val done = stream.map(_.streamIsDone).mkString
        EInfo(s"stream.done: found:$found consumed:$consumed done:$done") :: Nil
      }

      case "consume" => {
        val name = ctx.getRequired("stream")
        val timeout = ctx.get("timeout")

        if (DieselAppContext.activeStreamsByName.get(name).isDefined) {
          new EEngSuspend("stream.consume", "", Some((e, a, l) => {
            val stream = DieselAppContext.activeStreamsByName.get(name).get
            stream.withTargetId(a.id)

            DieselAppContext ! DESConsume(stream.name)

            timeout.foreach(d => {
              DieselAppContext ! DELater(e.id, d.toInt, DEComplete(e.id, a.id, recurse = true, l, Nil))
            })
          })) :: //with KeepOnlySomeChildren ::
              Nil
        } else {
          EError(s"stream.consume - stream not found: " + name) :: Nil
        }
      }

      case s@_ => {
        new EError(s"ctx.$s - unknown activity ") :: Nil
      }
    }
  }

  override def toString = "$executor::ctx "

  override val messages: List[EMsg] =
    EMsg(PREFIX, "put") ::
        EMsg(PREFIX, "new") ::
        EMsg(PREFIX, "done") ::
        EMsg(PREFIX, "consume") ::
        EMsg(PREFIX, "putAll") ::
        EMsg(PREFIX, "generate") ::
        Nil
}
