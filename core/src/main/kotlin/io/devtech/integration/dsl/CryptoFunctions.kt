package io.devtech.integration.dsl

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * DataWeave-inspired crypto and encoding functions.
 *
 * ```kotlin
 * uuid()                          // "550e8400-e29b-41d4-a716-446655440000"
 * md5("hello")                    // "5d41402abc4b2a76b9719d911017c592"
 * sha256("hello")                 // "2cf24dba..."
 * base64Encode("hello")           // "aGVsbG8="
 * base64Decode("aGVsbG8=")        // "hello"
 * hmacSha256("message", "secret") // hex HMAC
 * ```
 */

/** Generate a random UUID string. */
fun uuid(): String = UUID.randomUUID().toString()

/** MD5 hex digest. */
fun md5(text: String): String = hexDigest("MD5", text)

/** SHA-1 hex digest. */
fun sha1(text: String): String = hexDigest("SHA-1", text)

/** SHA-256 hex digest. */
fun sha256(text: String): String = hexDigest("SHA-256", text)

/** HMAC-SHA256 hex digest. */
fun hmacSha256(text: String, key: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return mac.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Base64 encode a string. */
fun base64Encode(text: String): String =
    java.util.Base64.getEncoder().encodeToString(text.toByteArray())

/** Base64 encode raw bytes. */
fun base64Encode(bytes: ByteArray): String =
    java.util.Base64.getEncoder().encodeToString(bytes)

/** Base64 decode to string. */
fun base64Decode(encoded: String): String =
    String(java.util.Base64.getDecoder().decode(encoded))

private fun hexDigest(algorithm: String, text: String): String =
    MessageDigest.getInstance(algorithm)
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
