package com.drobisch.tresor.vault

import com.softwaremill.sttp.HttpURLConnectionBackend

trait HttpSupport {
  protected implicit val backend = HttpURLConnectionBackend()
}
