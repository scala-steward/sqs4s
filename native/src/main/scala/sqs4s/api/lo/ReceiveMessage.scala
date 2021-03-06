package sqs4s.api.lo

import cats.effect.{Clock, Sync, Timer}
import cats.syntax.all._
import fs2.Chunk
import org.http4s.Request
import org.typelevel.log4cats.Logger
import sqs4s.api.SqsConfig
import sqs4s.errors.UnexpectedResponseError
import sqs4s.serialization.SqsDeserializer

import scala.xml.Elem

final case class ReceiveMessage[F[_]: Sync: Clock: Timer, T](
  maxNumberOfMessages: Int = 10, // max 10 per sqs api doc
  visibilityTimeout: Int = 15,
  waitTimeSeconds: Option[Int] = None
)(implicit decoder: SqsDeserializer[F, T])
    extends Action[F, Chunk[ReceiveMessage.Result[T]]] {

  def mkRequest(config: SqsConfig[F], logger: Logger[F]): F[Request[F]] = {
    val params = List(
      "Action" -> "ReceiveMessage",
      "MaxNumberOfMessages" -> maxNumberOfMessages.toString,
      "VisibilityTimeout" -> visibilityTimeout.toString,
      "AttributeName" -> "All",
      "MessageAttributeName" -> "All"
    ) ++ version ++ waitTimeSeconds.toList.map(sec =>
      "WaitTimeSeconds" -> sec.toString
    )

    SignedRequest.post[F](
      params,
      config.queue,
      config.credentials,
      config.region
    ).render(logger)
  }

  def parseResponse(response: Elem): F[Chunk[ReceiveMessage.Result[T]]] = {
    val msgs = response \\ "Message"
    if (msgs.nonEmpty) {
      Chunk(msgs: _*).traverse { msg =>
        val md5Body = (msg \ "MD5OfBody").text
        val raw = (msg \ "Body").text
        val attributes = (msg \ "Attribute")
          .map(node => (node \ "Name").text -> (node \ "Value").text)
          .toMap
        val messageAttributes = (msg \ "MessageAttribute")
          .map(node =>
            (node \ "Name").text -> (node \ "Value" \ "StringValue").text
          )
          .toMap
        val mid = (msg \ "MessageId").text
        val handle = (msg \ "ReceiptHandle").text
        handle.nonEmpty
          .guard[Option]
          .as {
            decoder.deserialize(raw).map { t =>
              ReceiveMessage.Result(
                mid,
                ReceiptHandle(handle),
                t,
                raw,
                md5Body,
                attributes,
                messageAttributes
              )
            }
          }
          .getOrElse {
            Sync[F].raiseError(
              UnexpectedResponseError("ReceiptHandle", response)
            )
          }
      }
    } else {
      Chunk.empty[ReceiveMessage.Result[T]].pure[F]
    }
  }
}

final case class ReceiptHandle(value: String) extends AnyVal

object ReceiveMessage {
  final case class Result[T](
    messageId: String,
    receiptHandle: ReceiptHandle,
    body: T,
    rawBody: String,
    md5OfBody: String,
    attributes: Map[String, String],
    messageAttributes: Map[String, String]
  )
}
