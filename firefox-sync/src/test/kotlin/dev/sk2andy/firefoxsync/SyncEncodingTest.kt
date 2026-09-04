package dev.sk2andy.firefoxsync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncEncodingTest {
    @Test
    fun `canonical JSON sorts keys recursively and formats numbers like JavaScript`() {
        val value = JSONObject()
            .put("z", JSONArray().put(JSONObject().put("b", 2.0).put("a", 1.5)))
            .put("a", JSONObject.NULL)
            .put("m", "quote\" back\\ nl\n ff\u000C ctl\u0001 slash/ <tag>")
            .put("n", 3)
            .put("t", true)
        assertEquals(
            "{\"a\":null,\"m\":\"quote\\\" back\\\\ nl\\n ff\\f ctl\\u0001 slash/ <tag>\",\"n\":3,\"t\":true,\"z\":[{\"a\":1.5,\"b\":2}]}",
            SyncEncoding.canonicalJson(value),
        )
        assertEquals("null", SyncEncoding.canonicalJson(null))
    }

    @Test
    fun `strict JSON parsing rejects trailing content and wrong roots`() {
        assertEquals("1", SyncEncoding.parseJsonObject("""{"a":1}""").get("a").toString())
        assertEquals(2, SyncEncoding.parseJsonArray("[1,2]").length())
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.parseJsonObject("""{"a":1} x""") }
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.parseJsonObject("[1]") }
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.parseJsonArray("{}") }
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.parseJsonObjectOrArray("\"text\"") }
    }

    @Test
    fun `base64 variants enforce alphabet padding and bounds`() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4)
        assertArrayEquals(bytes, SyncEncoding.decodeBase64(SyncEncoding.base64(bytes), expectedBytes = 5))
        assertArrayEquals(bytes, SyncEncoding.decodeBase64Url(SyncEncoding.base64Url(bytes), expectedBytes = 5))
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.decodeBase64("AAECAwQ") }
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.decodeBase64Url("AAECAwQ=") }
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.decodeBase64("AAECAwQ=", expectedBytes = 4) }
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.decodeBase64Url("AAECAwQ", maxBytes = 4) }
        assertEquals("00ff10", SyncEncoding.hex(byteArrayOf(0, -1, 16)))
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.decodeHex("00FF") }
        assertThrows(IllegalArgumentException::class.java) { SyncEncoding.decodeUtf8(byteArrayOf(-1, -2)) }
    }
}
