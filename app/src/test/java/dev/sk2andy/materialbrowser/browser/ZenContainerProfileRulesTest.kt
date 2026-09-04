package dev.sk2andy.materialbrowser.browser

import dev.sk2andy.firefoxsync.ZenContainerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenContainerProfileRulesTest {
    private val work = ZenContainerRecord("builtin-2", "Work", "briefcase", "orange")
    private val custom = ZenContainerRecord("3f1c-guid", "Client X", "unknown-icon", "teal")

    @Test
    fun `icons and colors map to emoji and hues with safe defaults`() {
        assertEquals("💼", ZenContainerProfileRules.emojiFor("briefcase"))
        assertEquals("📦", ZenContainerProfileRules.emojiFor("unknown"))
        assertEquals(30, ZenContainerProfileRules.accentHueFor("Orange"))
        assertNull(ZenContainerProfileRules.accentHueFor("toolbar"))
    }

    @Test
    fun `new containers become isolated named profiles and re-syncs update in place`() {
        var counter = 0
        val first = ZenContainerProfileRules.reconcile(
            containers = listOf(custom, work),
            existingProfiles = listOf(DEFAULT_BROWSER_PROFILE),
            isolationSupported = true,
            newProfileId = { "profile-${++counter}" },
        )
        assertEquals(listOf("builtin-2", "3f1c-guid"), first.created.map { it.zenContainerGuid })
        val workProfile = first.created.first()
        assertEquals("Work", workProfile.name)
        assertEquals("💼", workProfile.emoji)
        assertEquals(30, workProfile.accentHue)
        assertTrue(workProfile.isolationEnabled)
        assertTrue(first.updated.isEmpty() && first.skippedForLimit.isEmpty())

        val again = ZenContainerProfileRules.reconcile(
            containers = listOf(work.copy(name = "Work stuff"), custom),
            existingProfiles = listOf(DEFAULT_BROWSER_PROFILE) + first.created,
            isolationSupported = true,
            newProfileId = { error("no new profiles expected") },
        )
        assertEquals(listOf("Work stuff"), again.updated.map { it.name })
        assertEquals(workProfile.id, again.updated.single().id)
        assertEquals(listOf(first.created[1].id), again.unchangedIds)
        assertFalse(again.created.isNotEmpty())
    }

    @Test
    fun `profile limit skips extra containers and synced device profiles never match`() {
        val existing = (1..MAX_PROFILES - 1).map { BrowserProfile("p$it", "🍬") }
        val synced = BrowserProfile("synced:1", "💻", syncedDeviceId = "d1", zenContainerGuid = "builtin-2")
        val result = ZenContainerProfileRules.reconcile(
            containers = listOf(work, custom),
            existingProfiles = existing + synced,
            isolationSupported = false,
            newProfileId = { "new-${it.id}" },
        )
        assertEquals(listOf("builtin-2"), result.created.map { it.zenContainerGuid })
        assertFalse(result.created.single().isolationEnabled)
        assertEquals(listOf("3f1c-guid"), result.skippedForLimit)
    }

    @Test
    fun `tabs resolve to the container profile or the fallback`() {
        val profiles = listOf(DEFAULT_BROWSER_PROFILE, BrowserProfile("p-work", "💼", zenContainerGuid = "builtin-2"))
        assertEquals("p-work", ZenContainerProfileRules.profileIdFor("builtin-2", profiles, "candy"))
        assertEquals("candy", ZenContainerProfileRules.profileIdFor("builtin-9", profiles, "candy"))
        assertEquals("candy", ZenContainerProfileRules.profileIdFor(null, profiles, "candy"))
    }
}
