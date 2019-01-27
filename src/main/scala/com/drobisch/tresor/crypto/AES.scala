package com.drobisch.tresor.crypto

import cats.effect.Sync
import com.drobisch.tresor.Provider
import javax.crypto.{ Cipher, SecretKeyFactory }
import javax.crypto.spec.{ IvParameterSpec, PBEKeySpec, SecretKeySpec }

final case class AESContext(
  password: String,
  salt: String,
  input: Array[Byte],
  cipher: String = "AES/CBC/PKCS5Padding")

final case class EncryptedSecret(encrypted: Array[Byte], cipher: String, initVector: Array[Byte])

/**
 * Implements basic AES encryption using javax.crypto and follows
 * https://stackoverflow.com/questions/992019/java-256-bit-aes-password-based-encryption
 *
 * @tparam F the context to do the encryption
 */
class AES[F[_]] extends Provider[AES[F], F, AESContext, EncryptedSecret] {
  def encrypt(aes: AESContext)(implicit sync: Sync[F]): F[EncryptedSecret] = sync.delay {
    val secretKey = createSecretKey(aes.password, aes.salt)

    val cipher = Cipher.getInstance(aes.cipher)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val params = cipher.getParameters
    val iv: Array[Byte] = params.getParameterSpec(classOf[IvParameterSpec]).getIV
    EncryptedSecret(cipher.doFinal(aes.input), aes.cipher, iv)
  }

  def decrypt(password: String, salt: String, secret: EncryptedSecret): Array[Byte] = {
    val secretKey = createSecretKey(password, salt)

    val cipher = Cipher.getInstance(secret.cipher)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(secret.initVector))
    cipher.doFinal(secret.encrypted)
  }

  private def createSecretKey(password: String, salt: String): SecretKeySpec = {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = new PBEKeySpec(password.toCharArray, salt.getBytes, 65536, 256)
    val secretKey = factory.generateSecret(spec)
    new SecretKeySpec(secretKey.getEncoded, "AES")
  }

}

object AES {
  def apply[F[_]] = new AES[F]
}