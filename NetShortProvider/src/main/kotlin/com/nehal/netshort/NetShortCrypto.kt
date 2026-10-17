package com.nehal.netshort

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object NetShortCrypto {
    private const val RSA_PUB_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2poXMstZ8NCWE7915MXzDWC5/t+oB2waGfskPqSZwLqxd4ZBR0H1cb1tAZRZcV7P+LmOd6SYNxhnELaWuKTD+D3xkz8Tt1L5j/ynGqVt1MDbiQIEzXQKUkNDSH6T0A+Xzo/67/8QOQXlVJfW06resbaeNvibfx6Qc78j96bCIPlxPrtieilVTBHUFOXjirxK/ki/mO8P2smRbpt73fsQWdGmTGMfYGvfPApGyxbxLkL/qrBjU25XpM8a0MBqzFWUAchHmqSBJ6Mbfam1SSgf3b2U28s67nOW+JiOrhd6iVLcsLFxXA54HX+Zbej3AbOB6jKaEmp/bz1amneE1NYXwwIDAQAB"

    private const val RSA_PRIV_KEY =
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCK0Tl1pd7bjTRU93bWoHW1hLCDj2+9bg1MgY8j5C7xXaw6bJfToXhWbH1fXNbnFFVqxyYNErcuOUwJZxyDgcxUXM4yWnRseb2GF97GOicAQ2keDzVYmwky4lrSRwvcXutJRLPUCRQNfc6upfk2G5TKh6/CcP4TV1eXTF7+vdEw2SHxAOITKbSfcaZXr/hVs6a1aRHsBF+7RG99ebwZIP6/AgIyqX9RbDVN6ixi1v2G3/bwAULHLSqGdSaqij/ca17fbFGITaeCeEaZ6d/P4ZuOK+PEPdbPQt6SbY4lZaYwRvdrpH73kigPITgDzIDONFybJ1m7wRKlq1wxWHwbimptAgMBAAECggEAPz3cYJXFtt5YphDrahJGLgEabYVOUc2ub1li/eX54OpdCWzpqneYnD7myyg/m5zu4SuDUVdibsOZuXrpSZw7m3+ATP5apgS8bDe5vTNHC16qqBAjrI9NHIp09/F4HNh9dq6/Am10XkUfgP+KTrU4DyDL2NijV+pltD8N1B5kDE1igokVcsavhnu2INoMRXYE78Wq6urNECuFWw9hldv81M9m2w56t1CQOUukpo4mfmLjZRe2s+kwtcBVefGHP8Cj0OeH2dGltjl2YSQMRBFUCVoixYpOrcjIHoqzWri8IfUZ2tW+nUvHl5IZ9RVxefnFaLGnxiXd2sk6Sn4aD/l9YQKBgQDVv3HaOZxHRqlNSPrNGqplGhE066HnDsq6MlPukiovxE43CRBmpTnk9zDCqrDh9t2HbJuao7nSq5WlBERWgwqXU/qDpH43W7Y/lJfHkDv6A2m0viJa0a9x8+CJpNnCDu1ATo4/IQKwoXYice6JKnUyXgkGKn+HipiN6tO0EtWHlQKBgQCmQfklKFtXtm/FZ6NIMs+d+EyvaE5xNLKGYQxmiCR10WGYd8ZV+K0Q6qXHS+a32TirWB9F3TqPOklTytMrfPZB3BCXj4weEldb8W716G8FYf7LLhaT+MdpF7KDcruObwoQAvKV3N4eX6tUEMmdrx9hpCmmIU5EeXUkhGdmwk7BeQKBgAIXMkThJV8pGMTRvuo8pYgBnkN3PoklAuSZU2rU8Sawc9dj9k4atZtAs7BjvQEoyffmHwt/KHUgCoGnrgdulq7uOlgJRtbBxeGPUYC5L2z9lY4YAfwDawThTsPp4dtdDAMCAbAqYX1axu4FUUD0MltAwjPWPJMVzvIsZs+vE3mVAoGAJPja3OaCmZjadj2709xoyypic0dw2j/ry3JdfZec9A5h87P/CTNJ2U81GoLIhe3qakAohDLUSPGfSOD74NnjMXYswmeLs0xE3Q9tq4XK2pmWPby8DJ/wSHCapByplN0gkbr2E1mQk5SW1xT8oPJGukH1eRpC+3s/D6XaEMH5HZECgYEAigoX5l39LDsCgeaUcI4S9grkaas/WsKv37eqo3oD9Qk6VFiMM5L5Zig6aXJxuAPLVjb38caJRPmPmOXLT2kEP1E1h6OJOhEhETwVIUtcBzsK25ju9LqL89bC+W0uS7BPvk6Tcws/tXHCkQCTgb9jVXceZ2ox+6axvlW/5WgHt5Q="

    private val rsaPublicKey by lazy {
        val keySpec = X509EncodedKeySpec(base64Decode(RSA_PUB_KEY))
        KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    private val rsaPrivateKey by lazy {
        val keySpec = PKCS8EncodedKeySpec(base64Decode(RSA_PRIV_KEY))
        KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private val CHAR_POOL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun base64Encode(bytes: ByteArray): String =
        try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }

    fun base64Decode(str: String): ByteArray =
        try {
            android.util.Base64.decode(str, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }

    /**
     * Replicates Hutool AES/ECB/PKCS5Padding + RSA/ECB/PKCS1Padding request encryption.
     * Returns Pair<encrypt-key header value, encrypted base64 request body>.
     */
    fun encryptPayload(json: String): Pair<String, String> {
        val randomStr = (1..32)
            .map { CHAR_POOL[Random.nextInt(CHAR_POOL.length)] }
            .joinToString("")

        val randomBytes = randomStr.toByteArray(Charsets.UTF_8)

        // 1. AES/ECB/PKCS5Padding encrypt json
        val aesCipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        aesCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(randomBytes, "AES"))
        val encBodyBytes = aesCipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val encryptedBodyB64 = base64Encode(encBodyBytes)

        // 2. Base64 encode the 32-char string, then RSA encrypt with public key
        val encodedRandomStr = base64Encode(randomBytes)
        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
        val encryptedKeyBytes = rsaCipher.doFinal(encodedRandomStr.toByteArray(Charsets.UTF_8))
        val encryptedKeyB64 = base64Encode(encryptedKeyBytes)

        return Pair(encryptedKeyB64, encryptedBodyB64)
    }

    /**
     * Decrypts NetShort's encrypted response payload using the private key and returned encrypt-key header.
     */
    fun decryptPayload(bodyBase64: String, encryptKeyHeader: String?): String {
        if (encryptKeyHeader.isNullOrBlank()) return bodyBase64

        // 1. RSA decrypt encrypt-key header using private key
        val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsaCipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey)
        val decryptedKeyBytes = rsaCipher.doFinal(base64Decode(encryptKeyHeader))

        // 2. Base64 decode to get the 32-character AES key string
        val aesKeyStr = String(base64Decode(String(decryptedKeyBytes, Charsets.UTF_8)), Charsets.UTF_8)

        // 3. AES decrypt body
        val aesCipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        aesCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyStr.toByteArray(Charsets.UTF_8), "AES"))
        val decryptedBodyBytes = aesCipher.doFinal(base64Decode(bodyBase64))

        return String(decryptedBodyBytes, Charsets.UTF_8)
    }
}
