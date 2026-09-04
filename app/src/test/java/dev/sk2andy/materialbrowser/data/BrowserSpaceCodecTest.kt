package dev.sk2andy.materialbrowser.data

import dev.sk2andy.materialbrowser.browser.BrowserSpace
import dev.sk2andy.materialbrowser.browser.BrowserSpaceRules
import dev.sk2andy.materialbrowser.browser.BrowserSpaceSnapshot
import dev.sk2andy.materialbrowser.browser.BrowserTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserSpaceCodecTest {
    private val snapshot = BrowserSpaceSnapshot(
        spaces = listOf(
            BrowserSpace("s-1", "candy", "Home", "🏠", accentHue = 200, zenSpaceId = "ws-1"),
            BrowserSpace("s-2", "candy", "Work", "💼"),
            BrowserSpace("s-3", "p-2", "Other", "🗂️"),
        ),
        activeSpaceIds = mapOf("candy" to "s-2"),
    )

    @Test
    fun `snapshot round trips and tolerates junk`() {
        assertEquals(snapshot, BrowserSpaceCodec.decode(BrowserSpaceCodec.encode(snapshot)))
        assertEquals(BrowserSpaceSnapshot.EMPTY, BrowserSpaceCodec.decode("not json"))
        assertEquals(BrowserSpaceSnapshot.EMPTY, BrowserSpaceCodec.decode("""{"version":9,"spaces":[]}"""))
        val partial = BrowserSpaceCodec.decode("""{"version":1,"spaces":[{"id":"a","profileId":"candy","name":"","emoji":""},{"id":"","profileId":"x"}],"activeSpaceIds":{"candy":"a"}}""")
        assertEquals(1, partial.spaces.size)
        assertEquals("🗂️", partial.spaces.single().emoji)
        assertEquals(mapOf("candy" to "a"), partial.activeSpaceIds)
    }

    @Test
    fun `rules resolve active space and membership with fallbacks`() {
        val spaces = snapshot.spaces
        assertEquals("s-2", BrowserSpaceRules.activeSpaceId(spaces, snapshot.activeSpaceIds, "candy"))
        assertEquals("s-3", BrowserSpaceRules.activeSpaceId(spaces, snapshot.activeSpaceIds, "p-2"))
        assertNull(BrowserSpaceRules.activeSpaceId(spaces, snapshot.activeSpaceIds, "p-none"))
        val untagged = BrowserTab("t1", 1L, profileId = "candy")
        val tagged = BrowserTab("t2", 1L, profileId = "candy", spaceId = "s-2")
        val stale = BrowserTab("t3", 1L, profileId = "candy", spaceId = "gone")
        assertEquals("s-1", BrowserSpaceRules.spaceIdFor(untagged, spaces))
        assertEquals("s-1", BrowserSpaceRules.spaceIdFor(stale, spaces))
        assertEquals(listOf("t2"), BrowserSpaceRules.tabsInSpace(listOf(untagged, tagged, stale), spaces, "candy", "s-2").map { it.id })
        assertEquals(3, BrowserSpaceRules.tabsInSpace(listOf(untagged, tagged, stale), spaces, "candy", null).size)
        val sanitized = BrowserSpaceRules.sanitize(snapshot.copy(spaces = spaces + spaces.first()), setOf("candy"))
        assertEquals(listOf("s-1", "s-2"), sanitized.spaces.map { it.id })
        assertEquals("Space 3", BrowserSpaceRules.nextDefaultName(sanitized.spaces, "candy"))
    }
}
