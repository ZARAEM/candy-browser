package dev.sk2andy.firefoxsync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenSpacesCodecTest {
    @Test
    fun `decodes every Zen record kind with the projected field names`() {
        val container = ZenSpacesCodec.decode(JSONObject("""{"id":"builtin-2","kind":"container","data":{"guid":"builtin-2","name":"Work","icon":"briefcase","color":"orange"}}"""))
        assertEquals(ZenContainerRecord("builtin-2", "Work", "briefcase", "orange"), container)

        val space = ZenSpacesCodec.decode(
            JSONObject(
                """{"id":"ws-1","kind":"space","data":{"uuid":"ws-1","name":"Research","icon":"🔬","theme":{"type":"gradient","opacity":0.5,"gradientColors":[{"c":[1,2,3]}]},"containerGuid":"builtin-2","children":["tab-1","folder-1","split-1"]}}""",
            ),
        ) as ZenSpaceRecord
        assertEquals("Research", space.name)
        assertEquals("""{"gradientColors":[{"c":[1,2,3]}],"opacity":0.5,"type":"gradient"}""", space.themeJson)
        assertEquals(listOf("tab-1", "folder-1", "split-1"), space.children)

        val tab = ZenSpacesCodec.decode(
            JSONObject(
                """{"id":"tab-1","kind":"tab","data":{"tabId":"tab-1","url":"https://example.org/","title":"Example","icon":"data:image/svg+xml;base64,PHN2Zy8+","containerGuid":null,"essential":false,"workspaceUuid":"ws-1","folderId":null,"staticLabel":null,"hasStaticIcon":false,"defaultContainer":false}}""",
            ),
        ) as ZenTabRecord
        assertEquals("https://example.org/", tab.url)
        assertNull(tab.containerGuid)
        assertFalse(tab.essential)
        assertEquals("ws-1", tab.workspaceUuid)

        val folder = ZenSpacesCodec.decode(
            JSONObject("""{"id":"folder-1","kind":"folder","data":{"folderId":"folder-1","name":"Docs","icon":null,"workspaceUuid":"ws-1","parentFolderId":null,"live":null,"children":["tab-2"]}}"""),
        ) as ZenFolderRecord
        assertEquals("Docs", folder.name)
        assertNull(folder.liveJson)

        val split = ZenSpacesCodec.decode(
            JSONObject("""{"id":"split-1","kind":"split","data":{"splitId":"split-1","gridType":"vsep","tabs":["tab-3","tab-4"],"workspaceUuid":"ws-1","folderId":null}}"""),
        ) as ZenSplitRecord
        assertEquals(listOf("tab-3", "tab-4"), split.tabs)

        val layout = ZenSpacesCodec.decode(
            JSONObject("""{"id":"layout","kind":"layout","data":{"spaces":["ws-1","ws-2"],"essentials":{"default":["tab-9"],"builtin-2":["tab-8"]}}}"""),
        ) as ZenLayoutRecord
        assertEquals(listOf("ws-1", "ws-2"), layout.spaces)
        assertEquals(listOf("tab-8"), layout.essentials["builtin-2"])

        assertEquals(ZenTombstoneRecord("tab-7"), ZenSpacesCodec.decode(JSONObject("""{"id":"tab-7","deleted":true}""")))
    }

    @Test
    fun `unknown kinds mismatched ids and malformed data decode to null`() {
        assertNull(ZenSpacesCodec.decode(JSONObject("""{"id":"x","kind":"widget","data":{}}""")))
        assertNull(ZenSpacesCodec.decode(JSONObject("""{"id":"ws-1","kind":"space","data":{"uuid":"ws-2","name":"n"}}""")))
        assertNull(ZenSpacesCodec.decode(JSONObject("""{"id":"tab-1","kind":"tab","data":{"tabId":"tab-1","url":"about:blank"}}""")))
        assertNull(ZenSpacesCodec.decode(JSONObject("""{"id":"split-1","kind":"split","data":{"splitId":"split-1","tabs":["only"]}}""")))
        assertNull(ZenSpacesCodec.decode(JSONObject("""{"id":"not layout","kind":"layout","data":{}}""")))
        assertNull(ZenSpacesCodec.decode(JSONObject("""{"id":"ws-1","kind":"space","data":{"uuid":"ws-1","children":[1]}}""")))
        assertNull(ZenSpacesCodec.decode(JSONObject("""{"kind":"space","data":{}}""")))
    }

    @Test
    fun `encode produces Zen cleartext that decodes back and digests canonically`() {
        val records = listOf(
            ZenContainerRecord("c-1", "Banking", "dollar", "green"),
            ZenSpaceRecord("ws-1", "Home", null, """{"opacity":1,"type":"gradient"}""", "c-1", listOf("tab-1")),
            ZenTabRecord("tab-1", "https://example.org/", "Example", "", "c-1", false, "ws-1", null, "Pinned", true, true),
            ZenFolderRecord("folder-1", "Reading", "📚", "ws-1", null, """{"provider":"rss"}""", emptyList()),
            ZenSplitRecord("split-1", "grid", listOf("tab-1", "tab-2"), "ws-1", null),
            ZenLayoutRecord(listOf("ws-1"), mapOf("default" to listOf("tab-5"))),
            ZenTombstoneRecord("tab-6"),
        )
        records.forEach { record ->
            assertEquals(record, ZenSpacesCodec.decode(ZenSpacesCodec.encode(record)))
        }
        assertEquals("""{"deleted":true,"id":"tab-6"}""", SyncEncoding.canonicalJson(ZenSpacesCodec.encode(ZenTombstoneRecord("tab-6"))))
        val encoded = ZenSpacesCodec.encode(records[0])
        assertEquals("container", encoded.getString("kind"))
        assertEquals("c-1", encoded.getJSONObject("data").getString("guid"))
        assertNull(ZenSpacesCodec.digest(ZenTombstoneRecord("x")))
        assertEquals(44, requireNotNull(ZenSpacesCodec.digest(records[1])).length)
        assertEquals(ZenSpacesCodec.digest(records[1]), ZenSpacesCodec.digest((records[1] as ZenSpaceRecord).copy()))
    }

    @Test
    fun `snapshot assembly follows layout order and child sequences`() {
        val snapshot = ZenSpacesCodec.assemble(
            listOf(
                ZenSpaceRecord("ws-b", "Beta", null, null, null, listOf("tab-2", "tab-e", "missing")),
                ZenSpaceRecord("ws-a", "Alpha", null, null, null, emptyList()),
                ZenSpaceRecord("ws-c", "Gamma", null, null, null, emptyList()),
                ZenTabRecord("tab-2", "https://b.example/", "", "", null, false, "ws-b", null, null, false, false),
                ZenTabRecord("tab-e", "https://e.example/", "", "", "builtin-1", true, null, null, null, false, false),
                ZenLayoutRecord(listOf("ws-b", "ws-a"), mapOf("builtin-1" to listOf("tab-e"))),
                ZenTombstoneRecord("gone"),
            ),
        )
        assertEquals(listOf("ws-b", "ws-a", "ws-c"), snapshot.orderedSpaces().map(ZenSpaceRecord::id))
        assertEquals(listOf("tab-2"), snapshot.pinnedTabs("ws-b").map(ZenTabRecord::id))
        assertEquals(listOf("tab-e"), snapshot.essentialTabs("builtin-1").map(ZenTabRecord::id))
        assertTrue(snapshot.essentialTabs("default").isEmpty())
        assertTrue(ZenSpacesCodec.isBuiltinContainerGuid("builtin-4"))
        assertFalse(ZenSpacesCodec.isBuiltinContainerGuid("builtin-5"))
        assertFalse(ZenSpacesCodec.isBuiltinContainerGuid("builtin-"))
    }
}
