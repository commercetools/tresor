package com.commercetools.tresor.vault

import cats.effect.Sync
import io.circe.Json
import sttp.client3._

trait HttpSupport {
  protected lazy val backend = HttpURLConnectionBackend()

  implicit class ResponseOps[F[_]](response: Response[Either[String, String]])(
      implicit sync: Sync[F]
  ) {
    def parseJson: F[Json] = sync.fromEither(
      response.body.left
        .map(httpError =>
          new RuntimeException(
            s"error during http request: $httpError ($response)"
          )
        )
        .flatMap(bodyString =>
          io.circe.parser
            .decode[Json](bodyString)
            .left
            .map(parseError =>
              new RuntimeException(
                s"unable to parse json from $bodyString: $parseError"
              )
            )
        )
    )
  }
}
