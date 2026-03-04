package io.devtech.integration.dsl

/**
 * DataWeave-inspired string transformation functions.
 *
 * All case transforms build on [words], which splits on camelCase boundaries,
 * hyphens, underscores, and spaces.
 *
 * ```kotlin
 * words("helloWorld")          // ["hello", "World"]
 * capitalize("hello world")   // "Hello World"
 * camelize("hello-world")     // "helloWorld"
 * dasherize("helloWorld")     // "hello-world"
 * underscore("helloWorld")    // "hello_world"
 * ```
 */

private val WORD_BOUNDARY = Regex(
    "(?<=[a-z])(?=[A-Z])"           // camelCase boundary: aB → a|B
    + "|(?<=[A-Z])(?=[A-Z][a-z])"   // acronym boundary: ABc → A|Bc
    + "|[-_\\s]+"                     // explicit separators
)

/** Split text into words on camelCase boundaries, hyphens, underscores, and spaces. */
fun words(text: String): List<String> =
    text.split(WORD_BOUNDARY).filter { it.isNotBlank() }

/** Title Case: "hello world" → "Hello World". */
fun capitalize(text: String): String =
    words(text).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** camelCase: "hello-world" / "hello_world" → "helloWorld". */
fun camelize(text: String): String {
    val w = words(text)
    if (w.isEmpty()) return ""
    return w.first().lowercase() + w.drop(1).joinToString("") {
        it.replaceFirstChar { c -> c.uppercase() }
    }
}

/** PascalCase: "hello-world" → "HelloWorld". */
fun pascalize(text: String): String =
    words(text).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

/** kebab-case: "helloWorld" / "hello_world" → "hello-world". */
fun dasherize(text: String): String =
    words(text).joinToString("-") { it.lowercase() }

/** snake_case: "helloWorld" / "hello-world" → "hello_world". */
fun underscore(text: String): String =
    words(text).joinToString("_") { it.lowercase() }

/** Truncate with suffix: truncate("long text here", 8) → "long ..." */
fun truncate(text: String, length: Int, suffix: String = "..."): String {
    if (text.length <= length) return text
    val cutoff = (length - suffix.length).coerceAtLeast(0)
    return text.take(cutoff) + suffix
}

/** Extract initials: "John Doe" → "JD". */
fun initials(text: String): String =
    words(text).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")

/** Mask a string, keeping the last [visible] characters: "secret123" → "*****t123". */
fun mask(text: String, visible: Int = 4, char: Char = '*'): String {
    if (text.length <= visible) return text
    val masked = char.toString().repeat(text.length - visible)
    return masked + text.takeLast(visible)
}
