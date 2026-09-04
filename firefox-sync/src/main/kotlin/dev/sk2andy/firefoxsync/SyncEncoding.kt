package dev.sk2andy.firefoxsync

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Bounded encoding helpers shared by the Firefox Sync protocol layer. Sync 1.5 record fields use
 * padded standard base64 and lowercase hex; Mozilla account OAuth and JOSE values use unpadded
 * base64url.
 */
internal object SyncEncoding {
    const val MAX_DECODED_BYTES = 8 * 1024 * 1024

    private val standardEncoder = Base64.getEncoder()
    private val standardDecoder = Base64.getDecoder()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()
    private val standardPattern = Regex("[A-Za-z0-9+/]*={0,2}")
    private val urlPattern = Regex("[A-Za-z0-9_-]+")
    private val hexPattern = Regex("[0-9a-f]+")

    fun base64(value: ByteArray): String = standardEncoder.encodeToString(value)

    fun decodeBase64(
        value: String,
        expectedBytes: Int? = null,
        maxBytes: Int = MAX_DECODED_BYTES,
    ): ByteArray {
        require(value.isNotEmpty() && value.length % 4 == 0 && value.length <= maxEncodedLength(maxBytes) + 4) {
            "Invalid base64 length"
        }
        require(standardPattern.matches(value)) { "Invalid base64 alphabet" }
        return checkDecoded(runCatching { standardDecoder.decode(value) }, expectedBytes, maxBytes)
    }

    fun base64Url(value: ByteArray): String = urlEncoder.encodeToString(value)

    fun decodeBase64Url(
        value: String,
        expectedBytes: Int? = null,
        maxBytes: Int = MAX_DECODED_BYTES,
    ): ByteArray {
        require(value.isNotEmpty() && value.length <= maxEncodedLength(maxBytes)) { "Invalid base64url length" }
        require(urlPattern.matches(value)) { "Invalid base64url alphabet" }
        return checkDecoded(runCatching { urlDecoder.decode(value) }, expectedBytes, maxBytes)
    }

    fun hex(value: ByteArray): String = buildString(value.size * 2) {
        value.forEach { byte -> append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f]).append(HEX_DIGITS[byte.toInt() and 0x0f]) }
    }

    fun decodeHex(value: String, expectedBytes: Int? = null): ByteArray {
        require(value.isNotEmpty() && value.length % 2 == 0 && hexPattern.matches(value)) { "Invalid hex" }
        val decoded = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        if (expectedBytes != null) require(decoded.size == expectedBytes) { "Unexpected hex length" }
        return decoded
    }

    fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    fun decodeUtf8(value: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("Invalid UTF-8", e)
    }

    fun parseJsonObject(raw: String): JSONObject = parseJson(raw) as? JSONObject
        ?: throw IllegalArgumentException("Expected a JSON object")

    fun parseJsonArray(raw: String): JSONArray = parseJson(raw) as? JSONArray
        ?: throw IllegalArgumentException("Expected a JSON array")

    fun parseJsonObjectOrArray(raw: String): Any = parseJson(raw).also {
        require(it is JSONObject || it is JSONArray) { "Expected a JSON object or array" }
    }

    /**
     * Serializes a JSON value with recursively sorted object keys and JavaScript number formatting,
     * matching Zen's `canonicalJSON` so record digests agree across clients.
     */
    fun canonicalJson(value: Any?): String = buildString { appendCanonical(this, value) }

    private fun parseJson(raw: String): Any {
        require(raw.length <= MAX_DECODED_BYTES) { "JSON too large" }
        val tokener = JSONTokener(raw)
        val value = runCatching { tokener.nextValue() }
            .getOrElse { throw IllegalArgumentException("Malformed JSON", it) }
        require(runCatching { tokener.nextClean() }.getOrDefault('x') == '\u0000') { "Unexpected trailing JSON content" }
        return value
    }

    private fun appendCanonical(builder: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> builder.append("null")
            is Boolean -> builder.append(value)
            is Number -> builder.append(canonicalNumber(value))
            is String -> appendQuoted(builder, value)
            is JSONArray -> {
                builder.append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) builder.append(',')
                    appendCanonical(builder, value.opt(index))
                }
                builder.append(']')
            }
            is JSONObject -> {
                builder.append('{')
                value.keys().asSequence().sorted().forEachIndexed { index, key ->
                    if (index > 0) builder.append(',')
                    appendQuoted(builder, key)
                    builder.append(':')
                    appendCanonical(builder, value.opt(key))
                }
                builder.append('}')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value ${value::class.java.name}")
        }
    }

    private fun canonicalNumber(value: Number): String = when (value) {
        is Int, is Long, is Short, is Byte -> value.toString()
        else -> {
            val double = value.toDouble()
            require(double.isFinite()) { "Non-finite number" }
            if (double == Math.rint(double) && Math.abs(double) < 1e21) {
                double.toLong().toString()
            } else {
                double.toString()
            }
        }
    }

    private fun appendQuoted(builder: StringBuilder, value: String) {
        builder.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char.code < 0x20) {
                    builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
        builder.append('"')
    }

    private fun maxEncodedLength(maxBytes: Int): Int = ((maxBytes.toLong() * 4L + 2L) / 3L).toInt()

    private fun checkDecoded(result: Result<ByteArray>, expectedBytes: Int?, maxBytes: Int): ByteArray {
        val decoded = result.getOrElse { throw IllegalArgumentException("Invalid base64", it) }
        require(decoded.size <= maxBytes) { "Decoded value too large" }
        if (expectedBytes != null) require(decoded.size == expectedBytes) { "Unexpected decoded length" }
        return decoded
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}

internal fun JSONObject.strictString(key: String, maxLength: Int): String {
    val value = opt(key) as? String ?: throw IllegalArgumentException("Expected string $key")
    require(value.length <= maxLength) { "$key too long" }
    return value
}

internal fun JSONObject.optionalString(key: String, maxLength: Int): String? {
    val value = opt(key) ?: return null
    if (value === JSONObject.NULL) return null
    require(value is String) { "Expected string or null $key" }
    require(value.length <= maxLength) { "$key too long" }
    return value
}

internal fun JSONObject.strictBoolean(key: String): Boolean =
    opt(key) as? Boolean ?: throw IllegalArgumentException("Expected boolean $key")

internal fun JSONObject.optionalBoolean(key: String): Boolean {
    val value = opt(key) ?: return false
    if (value === JSONObject.NULL) return false
    return value as? Boolean ?: throw IllegalArgumentException("Expected boolean $key")
}

internal fun JSONObject.strictInt(key: String): Int = when (val value = opt(key)) {
    is Int -> value
    is Long -> value.toInt().also { require(it.toLong() == value) { "$key out of range" } }
    is Number -> {
        val double = value.toDouble()
        require(double == Math.rint(double) && Math.abs(double) <= Int.MAX_VALUE) { "$key not an integer" }
        double.toInt()
    }
    else -> throw IllegalArgumentException("Expected integer $key")
}

internal fun JSONObject.strictLong(key: String): Long = when (val value = opt(key)) {
    is Int -> value.toLong()
    is Long -> value
    is Number -> {
        val double = value.toDouble()
        require(double == Math.rint(double) && Math.abs(double) < 9.007199254740992E15) { "$key not an integer" }
        double.toLong()
    }
    else -> throw IllegalArgumentException("Expected integer $key")
}

internal fun JSONObject.strictDecimal(key: String): Double {
    val value = opt(key) as? Number ?: throw IllegalArgumentException("Expected number $key")
    val double = value.toDouble()
    require(double.isFinite() && double >= 0.0) { "$key out of range" }
    return double
}

internal fun JSONObject.stringList(key: String, maxItems: Int, maxLength: Int): List<String> {
    val array = opt(key)
    if (array == null || array === JSONObject.NULL) return emptyList()
    require(array is JSONArray) { "Expected array $key" }
    return array.stringList(maxItems, maxLength, key)
}

internal fun JSONArray.stringList(maxItems: Int, maxLength: Int, label: String = "array"): List<String> {
    require(length() <= maxItems) { "$label has too many items" }
    return List(length()) { index ->
        val item = opt(index) as? String ?: throw IllegalArgumentException("Expected string item in $label")
        require(item.length <= maxLength) { "$label item too long" }
        item
    }
}

internal fun JSONObject.requireKeys(vararg allowed: String) {
    val unexpected = keys().asSequence().filterNot { it in allowed }.toList()
    require(unexpected.isEmpty()) { "Unexpected keys $unexpected" }
}
