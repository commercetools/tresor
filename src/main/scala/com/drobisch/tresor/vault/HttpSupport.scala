package com.drobisch.tresor.vault

import sttp.client3._

trait HttpSupport {
  protected lazy val backend = HttpURLConnectionBackend()
}
