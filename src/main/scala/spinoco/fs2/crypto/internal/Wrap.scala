package spinoco.fs2.crypto.internal


import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.{HandshakeStatus, Status}

import fs2._
import fs2.util.Async.Ref
import fs2.util._
import fs2.util.syntax._


/**
  * Simple interface for `WRAP` operations in SSLEngine.
  *
  * Note that this must be not accessed from the multiple threads cocnurrently.
  *
  */
private[crypto] trait Wrap[F[_]] {

  /** wraps supplied data producing an result **/
  def wrap(data: Chunk[Byte]): F[WrapResult[F]]

  /** yields to true, when this awaits handshake to complete **/
  def awaitsHandshake: F[Boolean]

  /** From the unwrap side, indicates the handshake is complete **/
  def handshakeComplete: F[Unit]

}

private[crypto] object Wrap {

  def mk[F[_]](implicit engine: SSLEngine, F: Async[F], S: Strategy): F[Wrap[F]] = {
    F.refOf[Option[F[Unit]]](None) flatMap { handshakeDoneRef =>
    InputOutputBuffer.mk[F](engine.getSession.getPacketBufferSize, engine.getSession.getApplicationBufferSize) flatMap { ioBuff =>
    SSLTaskRunner.mk[F](engine) map { implicit sslTaskRunner =>

      new Wrap[F] {
        def wrap(data: Chunk[Byte]) = {
          ioBuff.input(data) flatMap { _ =>
            impl.wrap[F](ioBuff, handshakeDoneRef)
          }
        }

        def awaitsHandshake: F[Boolean] =
          handshakeDoneRef.get.map(_.nonEmpty)

        def handshakeComplete: F[Unit] =
          handshakeDoneRef.modify{ _ => None }.flatMap { _.previous.getOrElse(F.pure(())) }
      }

    }}}
  }


  object impl {

    def wrap[F[_]](
      ioBuff: InputOutputBuffer[F]
      , handshakeDoneRef: Ref[F, Option[F[Unit]]]
    )(implicit engine: SSLEngine, F: Async[F], RT: SSLTaskRunner[F]): F[WrapResult[F]] = {


      ioBuff.perform(engine.wrap) flatMap { result =>
        result.getStatus match {
        case Status.OK => result.getHandshakeStatus match {
          case HandshakeStatus.NOT_HANDSHAKING =>
            ioBuff.output map { chunk => WrapResult[F](None, chunk, closed = false) }

          case HandshakeStatus.NEED_WRAP =>
            // we need to drain all the bytes that may need wrap before we will move further
            // so we are just performing as many wraps until this finishes with other operation
            // than wrap
            // note that buffers are kept untouched, in case we recurse
            // also if nothing was produced, this will fail
            if (result.bytesProduced() == 0) F.fail(new Throwable("Request to WRAP again, but no bytes were produced"))
            else wrap(ioBuff, handshakeDoneRef)

          case HandshakeStatus.NEED_UNWRAP =>
            // indicates that handshake is in process and we need to output
            // whatever we had collected and then await any unwrap to move further.
            // always `unwrap` side is taking from this point to finish the handshake (w/o consulting the appBuffer)
            // so we just create the signal that will be signalled when unwrap will be done, and
            // in that case we will still signal next unwrap operation that may at this time
            // produce application data from appBuffer.
            ioBuff.output flatMap { chunk =>
            F.ref[Unit] flatMap { signal =>
            handshakeDoneRef.modify(_ => Some(signal.setPure(()))) map { c =>
              WrapResult(Some(signal.get), chunk, closed = false)
            }}}

          case HandshakeStatus.NEED_TASK =>
            // asynchronous tasks need to be run
            // this operation may be invoked from wrap/unwrap side. We rely on SSL engine
            // also note that NEED_TASK alway only consumed elements, never actually
            // when the tasks are run, then this is again run to  perform `unwrap`
            RT.runTasks flatMap { _ => wrap(ioBuff, handshakeDoneRef) }

          case HandshakeStatus.FINISHED =>
            // wrap (wrap0) is consulted only when application is about to send data
            // it is impossible that we will write data and at the same time yield handshake to be finished.
            // so we rather fail
            F.fail(new Throwable("bug: FINISHED after WRAP from App"))
        }

        case Status.BUFFER_OVERFLOW =>
          // need to increase the target buffer and retry operation
          ioBuff.expandOutput flatMap { _ => wrap(ioBuff, handshakeDoneRef) }

        case Status.BUFFER_UNDERFLOW =>
          // indicates not enough input data
          // highly unlikely on wrap, but this means we just get result from the output bytes
          // and finish the operation
          ioBuff.output map { chunk => WrapResult[F](None, chunk, closed = false) }

        case Status.CLOSED =>
          ioBuff.output map { chunk => WrapResult[F](None, chunk, closed = true) }

      }}
    }
  }

}


/**
  * Result of wrap operation
  * @param awaitAfterSend If nonempty, shall be used to await after `out` was sent to network
  * @param out            Data send to network
  * @param closed         If true, the wrap operation was not successfull because ssl engine is closed.
  * @tparam F
  */
case class WrapResult[F[_]](
  awaitAfterSend: Option[F[Unit]]
  , out: Chunk[Byte]
  , closed: Boolean
)