package org.scalatra

import scala.concurrent.duration._
import _root_.akka.util.Timeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.servlet.{ServletContext, AsyncEvent, AsyncListener}
import servlet.AsyncSupport
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import scala.concurrent.{ExecutionContext, Future}

object AsyncResult {
  val DefaultTimeout = Timeout(30 seconds)
}
abstract class AsyncResult(implicit override val scalatraContext: ScalatraContext) extends ScalatraContext  {

  implicit val request: HttpServletRequest = scalatraContext.request
  implicit val response: HttpServletResponse = scalatraContext.response
  val servletContext: ServletContext = scalatraContext.servletContext

  // This is a Duration instead of a timeout because a duration has the concept of infinity
  implicit def timeout: Duration = 30 seconds
  def is: Future[_]
}

trait FutureSupport extends AsyncSupport {

  implicit protected def executor: ExecutionContext

  override def asynchronously(f: ⇒ Any): Action = () ⇒ Future(f)

  // Still thinking of the best way to specify this before making it public.
  // In the meantime, this gives us enough control for our test.
  // IPC: it may not be perfect but I need to be able to configure this timeout in an application
  // This is a Duration instead of a timeout because a duration has the concept of infinity
  protected def asyncTimeout: Duration = 30 seconds


  override protected def isAsyncExecutable(result: Any) = classOf[Future[_]].isAssignableFrom(result.getClass)

  override protected def renderResponse(actionResult: Any) {
    actionResult match {
      case r: AsyncResult ⇒ handleFuture(r.is, r.timeout)
      case f: Future[_]   ⇒ handleFuture(f, asyncTimeout)
      case a              ⇒ super.renderResponse(a)
    }
  }

  private[this] def handleFuture(f: Future[_], timeout: Duration) {
    val gotResponseAlready = new AtomicBoolean(false)
    val context = request.startAsync()
    if (timeout.isFinite())
      context.setTimeout(timeout.toMillis)
    else
      context.setTimeout(-1)
    context addListener (new AsyncListener {
      def onComplete(event: AsyncEvent) {}

      def onTimeout(event: AsyncEvent) {
        onAsyncEvent(event) {
          if (gotResponseAlready.compareAndSet(false, true)) {
            renderHaltException(HaltException(Some(504), None, Map.empty, "Gateway timeout"))
            event.getAsyncContext.complete()
          }
        }
      }

      def onError(event: AsyncEvent) {}

      def onStartAsync(event: AsyncEvent) {}
    })

    f onComplete {
      case t ⇒ {
        withinAsyncContext(context) {
          if (gotResponseAlready.compareAndSet(false, true)) {
            t map { result =>
              runFilters(routes.afterFilters)
              super.renderResponse(result)
            } recover {
              case e: HaltException ⇒ renderHaltException(e)
              case e => renderResponse(errorHandler(e))
            }
            context.complete()
          }
        }
      }
    }
  }
}




