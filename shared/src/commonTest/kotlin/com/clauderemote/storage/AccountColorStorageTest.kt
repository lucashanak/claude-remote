package com.clauderemote.storage

import com.clauderemote.ui.theme.CRAccent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountColorStorageTest {

    private fun store() = AccountColorStorage(FakeKeyValueStore())

    @Test
    fun unsetAccountStillGetsAColour() {
        // The point of deriving: two accounts must look different the moment they
        // exist, not only after someone configures them.
        val s = store()
        assertNull(s.chosen("work"))
        assertNotNull(s.colorFor("work"))
    }

    @Test
    fun derivedColourIsStableForTheSameSlug() {
        val a = AccountColorStorage.derivedColorFor("lukas@kontexta.cz")
        val b = AccountColorStorage.derivedColorFor("lukas@kontexta.cz")
        assertEquals(a, b)
    }

    @Test
    fun assignGivesDistinctColoursToRealAccounts() {
        // Per-slug hashing collided on exactly these three, which is why assign()
        // exists — two identically-coloured chips defeat the point.
        val slugs = listOf("default", "lukas@kontexta.cz", "hanakl@nekrachni.cz")
        val colors = store().assign(slugs)
        assertEquals(slugs.size, colors.values.toSet().size, "expected distinct colours, got $colors")
    }

    @Test
    fun assignHonoursPinnedChoicesAndFillsTheRest() {
        val prefs = FakeKeyValueStore()
        val s = AccountColorStorage(prefs)
        s.set("b", CRAccent.Rose)
        val colors = s.assign(listOf("a", "b", "c"))
        assertEquals(CRAccent.Rose, colors["b"])
        assertEquals(3, colors.values.toSet().size)
    }

    @Test
    fun assignStillReturnsEveryAccountBeyondThePaletteSize() {
        val slugs = (1..8).map { "acct$it" }
        val colors = store().assign(slugs)
        assertEquals(slugs.toSet(), colors.keys)
    }

    @Test
    fun derivedColourHandlesEmptyAndOddSlugs() {
        // Must never throw, and must never index out of range on a negative hash.
        assertNotNull(AccountColorStorage.derivedColorFor(""))
        assertNotNull(AccountColorStorage.derivedColorFor("@@@"))
        assertNotNull(AccountColorStorage.derivedColorFor("a".repeat(200)))
    }

    @Test
    fun chosenColourWinsAndRoundTrips() {
        val prefs = FakeKeyValueStore()
        AccountColorStorage(prefs).set("work", CRAccent.Rose)
        val reloaded = AccountColorStorage(prefs)
        assertEquals(CRAccent.Rose, reloaded.chosen("work"))
        assertEquals(CRAccent.Rose, reloaded.colorFor("work"))
    }

    @Test
    fun clearingFallsBackToTheDerivedColour() {
        val prefs = FakeKeyValueStore()
        val s = AccountColorStorage(prefs)
        s.set("work", CRAccent.Rose)
        s.set("work", null)
        assertNull(s.chosen("work"))
        assertEquals(AccountColorStorage.derivedColorFor("work"), s.colorFor("work"))
    }

    @Test
    fun accountsDoNotBleedIntoEachOther() {
        val prefs = FakeKeyValueStore()
        val s = AccountColorStorage(prefs)
        s.set("a@x.cz", CRAccent.Mint)
        s.set("b@y.cz", CRAccent.Amber)
        assertEquals(CRAccent.Mint, s.chosen("a@x.cz"))
        assertEquals(CRAccent.Amber, s.chosen("b@y.cz"))
    }

    @Test
    fun slugsWithSeparatorCharsSurviveStorage() {
        // Slugs contain '@' and '.', and the store joins pairs with '=' and '\n' —
        // check the key isn't split on the wrong character.
        val prefs = FakeKeyValueStore()
        val s = AccountColorStorage(prefs)
        s.set("hanakl@nekrachni.cz", CRAccent.Violet)
        assertEquals(CRAccent.Violet, AccountColorStorage(prefs).chosen("hanakl@nekrachni.cz"))
    }

    @Test
    fun corruptStoredValueDegradesToDerived() {
        val prefs = FakeKeyValueStore()
        prefs.putString("account_colors", "work=NotAColour\ngarbage-line-without-separator")
        val s = AccountColorStorage(prefs)
        assertNull(s.chosen("work"))
        assertTrue(s.colorFor("work") in CRAccent.entries)
    }
}
