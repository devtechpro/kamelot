package io.devtech.integration.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.temporal.ChronoUnit

class DslFunctionsTest {

    @Nested
    inner class StringFunctionsTest {

        @Test
        fun `words splits camelCase`() =
            assertEquals(listOf("hello", "World"), words("helloWorld"))

        @Test
        fun `words splits kebab-case`() =
            assertEquals(listOf("hello", "world"), words("hello-world"))

        @Test
        fun `words splits snake_case`() =
            assertEquals(listOf("hello", "world"), words("hello_world"))

        @Test
        fun `words splits spaces`() =
            assertEquals(listOf("hello", "world"), words("hello world"))

        @Test
        fun `words splits mixed separators`() =
            assertEquals(listOf("hello", "World", "foo", "bar"), words("helloWorld_foo-bar"))

        @Test
        fun `words handles acronyms`() =
            assertEquals(listOf("HTTP", "Request"), words("HTTPRequest"))

        @Test
        fun `words handles empty string`() =
            assertEquals(emptyList<String>(), words(""))

        @Test
        fun `capitalize title cases words`() =
            assertEquals("Hello World", capitalize("hello world"))

        @Test
        fun `capitalize from camelCase`() =
            assertEquals("Hello World", capitalize("helloWorld"))

        @Test
        fun `camelize from kebab`() =
            assertEquals("helloWorld", camelize("hello-world"))

        @Test
        fun `camelize from snake`() =
            assertEquals("helloWorld", camelize("hello_world"))

        @Test
        fun `camelize from spaces`() =
            assertEquals("helloWorld", camelize("hello world"))

        @Test
        fun `camelize empty`() =
            assertEquals("", camelize(""))

        @Test
        fun `pascalize from kebab`() =
            assertEquals("HelloWorld", pascalize("hello-world"))

        @Test
        fun `pascalize from camelCase`() =
            assertEquals("HelloWorld", pascalize("helloWorld"))

        @Test
        fun `dasherize from camelCase`() =
            assertEquals("hello-world", dasherize("helloWorld"))

        @Test
        fun `dasherize from snake`() =
            assertEquals("hello-world", dasherize("hello_world"))

        @Test
        fun `underscore from camelCase`() =
            assertEquals("hello_world", underscore("helloWorld"))

        @Test
        fun `underscore from kebab`() =
            assertEquals("hello_world", underscore("hello-world"))

        @Test
        fun `truncate within limit returns original`() =
            assertEquals("short", truncate("short", 10))

        @Test
        fun `truncate beyond limit adds suffix`() =
            assertEquals("long ...", truncate("long text here", 8))

        @Test
        fun `truncate with custom suffix`() =
            assertEquals("hell~", truncate("hello world", 5, "~"))

        @Test
        fun `initials extracts first letters`() =
            assertEquals("JD", initials("John Doe"))

        @Test
        fun `initials from camelCase`() =
            assertEquals("HW", initials("helloWorld"))

        @Test
        fun `mask hides leading characters`() =
            assertEquals("*****t123", mask("secret123", 4))

        @Test
        fun `mask with custom char`() =
            assertEquals("##cret", mask("secret", 4, '#'))

        @Test
        fun `mask short text returns original`() =
            assertEquals("abc", mask("abc", 4))
    }

    @Nested
    inner class CryptoFunctionsTest {

        @Test
        fun `uuid returns valid format`() {
            val id = uuid()
            assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        }

        @Test
        fun `uuid generates unique values`() =
            assertNotEquals(uuid(), uuid())

        @Test
        fun `md5 produces known hash`() =
            assertEquals("5d41402abc4b2a76b9719d911017c592", md5("hello"))

        @Test
        fun `sha1 produces known hash`() =
            assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", sha1("hello"))

        @Test
        fun `sha256 produces known hash`() =
            assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha256("hello"))

        @Test
        fun `hmacSha256 produces known hash`() {
            val result = hmacSha256("hello", "secret")
            assertEquals(64, result.length) // 32 bytes = 64 hex chars
            // Known test vector
            assertEquals("88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b", result)
        }

        @Test
        fun `base64 round trip`() {
            val original = "Hello, World!"
            assertEquals(original, base64Decode(base64Encode(original)))
        }

        @Test
        fun `base64Encode known value`() =
            assertEquals("aGVsbG8=", base64Encode("hello"))

        @Test
        fun `base64Decode known value`() =
            assertEquals("hello", base64Decode("aGVsbG8="))

        @Test
        fun `base64Encode bytes`() =
            assertEquals("aGVsbG8=", base64Encode("hello".toByteArray()))
    }

    @Nested
    inner class DateFunctionsTest {

        @Test
        fun `now with pattern returns formatted string`() {
            val result = now("yyyy")
            assertTrue(result.matches(Regex("\\d{4}")))
        }

        @Test
        fun `today returns date format`() {
            val result = today()
            assertTrue(result.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        }

        @Test
        fun `epochMs returns reasonable value`() {
            val ms = epochMs()
            assertTrue(ms > 1_700_000_000_000) // after 2023
        }

        @Test
        fun `formatDate reformats ISO to pattern`() =
            assertEquals("25/02/2026", formatDate("2026-02-25T10:30:00Z", "dd/MM/yyyy"))

        @Test
        fun `formatDate extracts time`() =
            assertEquals("10:30", formatDate("2026-02-25T10:30:00Z", "HH:mm"))

        @Test
        fun `parseDate from custom pattern to ISO`() {
            val result = parseDate("25/02/2026", "dd/MM/yyyy")
            assertEquals("2026-02-25T00:00:00Z", result)
        }

        @Test
        fun `dateAdd days`() =
            assertEquals("2026-03-04T10:00:00Z", dateAdd("2026-02-25T10:00:00Z", 7, ChronoUnit.DAYS))

        @Test
        fun `dateAdd negative hours`() =
            assertEquals("2026-02-25T09:00:00Z", dateAdd("2026-02-25T10:00:00Z", -1, ChronoUnit.HOURS))

        @Test
        fun `dateDiff days`() =
            assertEquals(7, dateDiff("2026-02-25T10:00:00Z", "2026-03-04T10:00:00Z", ChronoUnit.DAYS))

        @Test
        fun `dateDiff hours`() =
            assertEquals(24, dateDiff("2026-02-25T00:00:00Z", "2026-02-26T00:00:00Z", ChronoUnit.HOURS))
    }

    @Nested
    inner class CollectionFunctionsTest {

        private val sample = mapOf(
            "name" to "John",
            "email" to "john@example.com",
            "password" to "secret",
            "age" to 30,
            "active" to true,
            "city" to null,
        )

        @Test
        fun `pick keeps only specified keys`() {
            val result = sample.pick("name", "email")
            assertEquals(mapOf("name" to "John", "email" to "john@example.com"), result)
        }

        @Test
        fun `pick with missing key omits it`() {
            val result = sample.pick("name", "nonexistent")
            assertEquals(mapOf("name" to "John"), result)
        }

        @Test
        fun `omit removes specified keys`() {
            val result = sample.omit("password", "city")
            assertFalse(result.containsKey("password"))
            assertFalse(result.containsKey("city"))
            assertEquals("John", result["name"])
        }

        @Test
        fun `pickNonNull keeps non-null values only`() {
            val result = sample.pickNonNull("name", "city", "email")
            assertEquals(mapOf("name" to "John", "email" to "john@example.com"), result)
        }

        @Test
        fun `rename changes key names`() {
            val result = sample.rename("name" to "fullName", "email" to "emailAddress")
            assertEquals("John", result["fullName"])
            assertEquals("john@example.com", result["emailAddress"])
            assertFalse(result.containsKey("name"))
            assertFalse(result.containsKey("email"))
        }

        @Test
        fun `rename preserves unrenamed keys`() {
            val result = sample.rename("name" to "fullName")
            assertEquals("secret", result["password"])
        }

        @Test
        fun `list extracts list of maps`() {
            val data = mapOf("items" to listOf(mapOf("id" to 1), mapOf("id" to 2)))
            val items = data.list("items")
            assertEquals(2, items.size)
            assertEquals(1, items[0]["id"])
        }

        @Test
        fun `list returns empty for missing key`() =
            assertEquals(emptyList<Map<String, Any?>>(), sample.list("items"))

        @Test
        fun `list returns empty for wrong type`() {
            val data = mapOf("items" to "not a list")
            assertEquals(emptyList<Map<String, Any?>>(), data.list("items"))
        }

        @Test
        fun `stringList extracts list of strings`() {
            val data = mapOf("tags" to listOf("a", "b", "c"))
            assertEquals(listOf("a", "b", "c"), data.stringList("tags"))
        }

        @Test
        fun `stringList returns empty for missing key`() =
            assertEquals(emptyList<String>(), sample.stringList("tags"))

        @Test
        fun `nestedString returns nested value`() {
            val data = mapOf("address" to mapOf("city" to "London"))
            assertEquals("London", data.nestedString("address.city"))
        }

        @Test
        fun `nestedString returns empty for missing path`() =
            assertEquals("", sample.nestedString("address.city"))

        @Test
        fun `nestedInt returns nested value`() {
            val data = mapOf("stats" to mapOf("count" to 42))
            assertEquals(42, data.nestedInt("stats.count"))
        }

        @Test
        fun `nestedInt returns null for missing path`() =
            assertNull(sample.nestedInt("stats.count"))

        @Test
        fun `nestedDouble returns nested value`() {
            val data = mapOf("stats" to mapOf("rate" to 0.95))
            assertEquals(0.95, data.nestedDouble("stats.rate"))
        }

        @Test
        fun `mapIf transforms when true`() {
            val result = sample.mapIf(true) { it + ("extra" to "yes") }
            assertEquals("yes", result["extra"])
        }

        @Test
        fun `mapIf returns original when false`() {
            val result = sample.mapIf(false) { it + ("extra" to "yes") }
            assertFalse(result.containsKey("extra"))
        }
    }
}
