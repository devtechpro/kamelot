package io.devtech.integration.model

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize

/**
 * A value type that wraps a sensitive string. Hidden from `toString()`,
 * logging, and Jackson serialization. Access the actual value via [value].
 *
 * ```kotlin
 * val token = Secret("my-api-key")
 * println(token)        // → "***"
 * println(token.value)  // → "my-api-key"
 * ```
 *
 * Use [secret] DSL function to read from the configured [SecretProvider]:
 * ```kotlin
 * password = secret("POSTGRES_PASSWORD", "postgres")
 * auth = AuthConfig(type = AuthType.BEARER, secret = secret("API_KEY"))
 * ```
 */
@JsonSerialize(using = SecretSerializer::class)
class Secret(val value: String) {
    override fun toString(): String = "***"
    override fun equals(other: Any?): Boolean = other is Secret && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        val EMPTY = Secret("")
    }
}

/**
 * Jackson serializer that writes `"***"` instead of the actual secret value.
 * Applied automatically via `@JsonSerialize` on [Secret].
 */
class SecretSerializer : JsonSerializer<Secret>() {
    override fun serialize(value: Secret, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString("***")
    }
}
