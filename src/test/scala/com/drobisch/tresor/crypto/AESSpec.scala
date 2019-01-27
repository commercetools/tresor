package com.drobisch.tresor.crypto

import cats.effect.IO
import org.scalatest.{ FlatSpec, Matchers }

class AESSpec extends FlatSpec with Matchers {
  "AES" should "encrypt and decrypt" in {
    val inputBytes = "Treasure!".getBytes("UTF-8")
    val aesContext = AESContext(password = "password", salt = "salt", input = inputBytes)
    val encryptedSecret: EncryptedSecret = AES[IO].encrypt(aesContext).unsafeRunSync()

    AES[IO].decrypt(password = "password", salt = "salt", encryptedSecret) should be(inputBytes)
  }
}
