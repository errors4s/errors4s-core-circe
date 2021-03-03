package io.isomarcte.errors4s.http4s

import _root_.io.isomarcte.errors4s.core._
import cats._
import cats.implicits._
import eu.timepit.refined.types.all._
import fs2._
import org.http4s._
import scodec.bits._

/** Error type which can be used in the `expectOr` method from http4s.
  *
  * @note If present, the query string on the request URI will be redacted. In
  *       0.2.x.x this behavior will be configurable, but this can not be done
  *       on 0.1.x without breaking binary compatibility.
  */
final case class ResponseError(
  status: Status,
  headers: Headers,
  errorBody: ResponseError.ErrorBody,
  maxBodySize: Long,
  requestUri: Option[Uri],
  requestMethod: Option[Method]
) extends Error {

  /** We redact the query string because we can't be sure it is safe to
    * render. It is not uncommon for sensitive parameters to be placed in the
    * query string. In 0.2.x.x we will give users the ability to configure
    * this, but we can't do that in 0.1.x without breaking binary
    * compatibility.
    */
  private lazy val requestUriWithoutQuery: Option[Uri] = requestUri.map(value =>
    value.copy(query =
      Query(
        value
          .query
          .pairs
          .foldLeft(Vector.empty[Query.KeyValue]) { case (acc, (key, value)) =>
            acc ++ Vector(key -> value.map(Function.const("<REDACTED>")))
          }: _*
      )
    )
  )

  private lazy val errorBodyMessages: Vector[String] = {
    import ResponseError.ErrorBody._

    val partialBodyErrorMessage: Vector[String] =
      errorBody match {
        case _: PartialErrorBody =>
          Vector(s"Body was larger than ${maxBodySize}, so it was truncated")
        case _ =>
          Vector.empty
      }

    errorBody
      .asText
      .fold(
        Function.const(Vector("Unable to decode error message as UTF-8 String")),
        _.fold(Vector("No HTTP body on response"))(error => Vector(error))
      ) ++ partialBodyErrorMessage
  }

  final override lazy val primaryErrorMessage: NonEmptyString = requestUri
    .flatMap(
      _.host
        .flatMap(host => NonEmptyString.from(s"Unexpected response from HTTP call to ${host.renderString}").toOption)
    )
    .getOrElse(NonEmptyString("Unexpected response from HTTP call"))

  final override lazy val secondaryErrorMessages: Vector[String] =
    requestUriWithoutQuery.map(uri => s"Request URI: ${uri.renderString}").toVector ++
      requestMethod.map(method => s"Request Method: ${method.renderString}").toVector ++
      Vector(s"Status: ${status}", s"ResponseHeaders: ${headers}") ++ errorBodyMessages

  final override lazy val causes: Vector[Throwable] = errorBody
    .asText
    .fold(e => Vector(e), Function.const(Vector.empty))
}

object ResponseError {

  /** Create a [[ResponseError]] which will read, at most, the given bytes from
    * the error response.
    */
  def fromResponse_[F[_]](maxBodySize: Long, requestUri: Option[Uri], requestMethod: Option[Method])(
    response: Response[F]
  )(implicit C: Stream.Compiler[F, F], F: FlatMap[F]): F[Throwable] =
    ErrorBody
      .fromResponse_(maxBodySize)(response)
      .map(errorBody =>
        ResponseError(response.status, response.headers, errorBody, maxBodySize, requestUri, requestMethod)
      )

  /** Create a [[ResponseError]] which will read, at most, the default number of
    * bytes from the error response, given by
    * [[ErrorBody#defaultMaxBodySizeBytes]].
    */
  def fromResponse[F[_]](requestUri: Option[Uri], requestMethod: Option[Method])(
    response: Response[F]
  )(implicit C: Stream.Compiler[F, F], F: FlatMap[F]): F[Throwable] =
    fromResponse_(ErrorBody.defaultMaxBodySizeBytes, requestUri, requestMethod)(response)

  /** Create a [[ResponseError]] which will read, at most, the default number of
    * bytes from the error response, given by
    * [[ErrorBody#defaultMaxBodySizeBytes]].
    *
    * @note This constructor will extract the `org.http4s.Uri` and
    *       `org.http4s.Status` from the `org.http4s.Request`. If the Uri is
    *       expected to contain sensitive information which you do not want in
    *       the [[ResponseError]] you should transform it ''before'' invoking
    *       this constructor.
    *
    * @param request The `org.http4s.Request` from which to extract the Uri and
    *                Status for the [[ResponseError]].
    *
    * @param response The `org.http4s.Response` which is the cause of this
    *        error.
    */
  def from_[F[_]](
    request: Request[F]
  )(response: Response[F])(implicit C: Stream.Compiler[F, F], F: FlatMap[F]): F[ResponseError] = {
    val maxBodySize: Long = ErrorBody.defaultMaxBodySizeBytes
    ErrorBody
      .fromResponse_(maxBodySize)(response)
      .map(errorBody =>
        ResponseError(response.status, response.headers, errorBody, maxBodySize, request.uri.some, request.method.some)
      )
  }

  /** As [[#from_]], but widened to [[java.lang.Throwable]] as is commonly required by APIs.
    */
  def from[F[_]](request: Request[F])(
    response: Response[F]
  )(implicit C: Stream.Compiler[F, F], F: FlatMap[F]): F[Throwable] = from_[F](request)(response)(C, F).widen

  /** Algebraic Data Type for error response bodies.
    *
    * Members include, [[ErrorBody#FullErrorBody]] for when the entire error
    * body is smaller than the given max bytes limit,
    * [[ErrorBody#PartialErrorBody]] for when the error body is larger than
    * the given max bytes limit, and [[ErrorBody#EmptyErrorBody]] for when
    * there is no body or the body's size is 0 bytes.
    *
    * @note There are a few edge cases here to be aware of. If the error body
    *       is ''exactly'' the size of the `maxBodySize` value, then it will
    *       be a [[ErrorBody#PartialErrorBody]]. This is because it can't be
    *       known if there is another byte without reading another byte, which
    *       would break the contract of this type. If the `maxBodySize` is
    *       `0L` then the result will be a
    *       [[ErrorBody#PartialErrorBody]]. This is because we were
    *       ''technically'' able to read the specified max number of
    *       bytes. That being said, there is no reason to have `maxBodySize`
    *       be <= 0.
    */
  sealed trait ErrorBody extends Product with Serializable {
    import ErrorBody._

    final lazy val asText: Either[Throwable, Option[String]] =
      this match {
        case FullErrorBody(value) =>
          value.decodeUtf8.map(_.pure[Option]).widen
        case PartialErrorBody(value) =>
          value.decodeUtf8.map(_.pure[Option]).widen
        case EmptyErrorBody =>
          Right(None)
      }
  }

  object ErrorBody {

    /** Default max body size in bytes, 1MiB. */
    lazy val defaultMaxBodySizeBytes: Long = 1024L * 1024L // 1MiB

    final case class FullErrorBody(value: ByteVector)    extends ErrorBody
    final case class PartialErrorBody(value: ByteVector) extends ErrorBody
    case object EmptyErrorBody                           extends ErrorBody

    def fromResponse_[F[_]](
      maxBodySize: Long
    )(response: Response[F])(implicit C: Stream.Compiler[F, F], F: FlatMap[F]): F[ErrorBody] =
      response
        .body
        .take(maxBodySize)
        .compile
        .to(ByteVector)
        .map { (body: ByteVector) =>
          val size: Long = body.size
          if (size === maxBodySize || maxBodySize <= 0L) {
            PartialErrorBody(body)
          } else if (size <= 0) {
            EmptyErrorBody
          } else {
            FullErrorBody(body)
          }
        }

    def fromResponse[F[_]](response: Response[F])(implicit C: Stream.Compiler[F, F], F: FlatMap[F]): F[ErrorBody] =
      fromResponse_(defaultMaxBodySizeBytes)(response)
  }
}
