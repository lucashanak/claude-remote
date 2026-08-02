package com.clauderemote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pure multi-account logic in ClaudeAccount.kt. [claudeConfigDirFor]
 * gets the most scrutiny: getting its null cases wrong makes the default account
 * run with `CLAUDE_CONFIG_DIR=$HOME/.claude`, which silently resolves the global
 * config to a file that doesn't exist, gets created empty, and loses the project
 * trust map — see the doc comment on the function itself.
 */
class ClaudeAccountTest {

    // --- claudeConfigDirFor ---

    @Test
    fun claudeConfigDirFor_nullSlug_isNull() {
        assertNull(claudeConfigDirFor(null))
    }

    @Test
    fun claudeConfigDirFor_blankSlug_isNull() {
        assertNull(claudeConfigDirFor(""))
        assertNull(claudeConfigDirFor("   "))
    }

    @Test
    fun claudeConfigDirFor_literalDefault_isNull() {
        // The load-bearing case: "default" must behave exactly like null, not
        // like a real slug named "default".
        assertNull(claudeConfigDirFor(ClaudeAccount.DEFAULT_SLUG))
        assertNull(claudeConfigDirFor("default"))
    }

    @Test
    fun claudeConfigDirFor_realSlug_returnsAccountsSubdir() {
        assertEquals("~/.claude-remote/accounts/work", claudeConfigDirFor("work"))
        assertEquals("~/.claude-remote/accounts/nekrachni-cz", claudeConfigDirFor("nekrachni-cz"))
    }

    // --- accountSlugFromEmail ---

    @Test
    fun accountSlugFromEmail_normalEmail() {
        assertEquals("lukashanak@nekrachni.cz", accountSlugFromEmail("lukashanak@nekrachni.cz"))
    }

    @Test
    fun accountSlugFromEmail_uppercaseIsLowercased() {
        assertEquals("lukashanak@nekrachni.cz", accountSlugFromEmail("LukasHanak@Nekrachni.CZ"))
    }

    @Test
    fun accountSlugFromEmail_plusAddressing() {
        // '+' isn't in the kept set, so it collapses; '@' survives.
        val slug = accountSlugFromEmail("lukas+work@nekrachni.cz")
        assertEquals("lukas-work@nekrachni.cz", slug)
    }

    @Test
    fun accountSlugFromEmail_unicodeAndDiacritics() {
        // Letters/digits (per Char.isLetterOrDigit, which is unicode-aware) pass
        // through as-is, and '.'/'@' are kept so the slug still reads as the email.
        val slug = accountSlugFromEmail("lukáš.hanák@example.com")
        assertEquals("lukáš.hanák@example.com", slug)
    }

    @Test
    fun accountSlugFromEmail_emptyString_fallsBackNotBlank() {
        val slug = accountSlugFromEmail("")
        assertEquals("account", slug)
        assertTrue(slug.isNotBlank())
    }

    @Test
    fun accountSlugFromEmail_onlySeparatorChars_fallsBackNotBlank() {
        // Punctuation-only survives the charset filter but carries no meaning, so
        // it must still fall back rather than name an account "@@@".
        val slug = accountSlugFromEmail("...@@@...")
        assertTrue(slug.isNotBlank())
        assertEquals("account", slug)
    }

    @Test
    fun accountSlugFromEmail_overlongInput_isCapped() {
        val longEmail = "a".repeat(100) + "@example.com"
        val slug = accountSlugFromEmail(longEmail)
        assertTrue(slug.length <= 48, "expected slug capped at 48 chars, got ${slug.length}")
    }

    @Test
    fun accountSlugFromEmail_neverProducesUnsafeDirectoryNameChars() {
        val inputs = listOf(
            "lukashanak@nekrachni.cz",
            "LukasHanak@Nekrachni.CZ",
            "lukas+work@nekrachni.cz",
            "lukáš.hanák@example.com",
            "",
            "...@@@...",
            "a".repeat(100) + "@example.com",
            "weird chars!#\$%^&*()/\\:;\"'<>|~`\n\t@x.com",
        )
        // Nothing that would need shell quoting or break a directory path: only
        // lowercase letters/digits/unicode-letters, '-' as separator.
        val unsafe = setOf(' ', '/', '\\', ':', ';', '"', '\'', '<', '>', '|', '~', '`', '\n', '\t', '$', '&', '*', '(', ')', '!', '#', '%', '^')
        for (input in inputs) {
            val slug = accountSlugFromEmail(input)
            assertTrue(slug.isNotBlank(), "slug for '$input' must not be blank")
            assertTrue(unsafe.none { it in slug }, "slug for '$input' contains an unsafe char: '$slug'")
        }
    }

    // --- allowedAccountsFor ---

    private val accA = ClaudeAccount(slug = "a", email = "a@x.com")
    private val accB = ClaudeAccount(slug = "b", email = "b@x.com")
    private val accC = ClaudeAccount(slug = "c", email = "c@x.com")
    private val allAccounts = listOf(accA, accB, accC)

    @Test
    fun allowedAccountsFor_emptyAllowSet_isUnrestricted() {
        val policy = FolderPolicy(allowedAccountSlugs = emptySet())
        assertEquals(allAccounts, allowedAccountsFor(policy, allAccounts))
    }

    @Test
    fun allowedAccountsFor_nullPolicy_isUnrestricted() {
        assertEquals(allAccounts, allowedAccountsFor(null, allAccounts))
    }

    @Test
    fun allowedAccountsFor_populatedSet_filters() {
        val policy = FolderPolicy(allowedAccountSlugs = setOf("a", "c"))
        assertEquals(listOf(accA, accC), allowedAccountsFor(policy, allAccounts))
    }

    @Test
    fun allowedAccountsFor_setMatchingNothing_fallsBackToFullList() {
        // The accounts referenced by the policy were removed since it was set;
        // this must not lock the user out of their own folder.
        val policy = FolderPolicy(allowedAccountSlugs = setOf("removed-account"))
        assertEquals(allAccounts, allowedAccountsFor(policy, allAccounts))
    }

    // --- defaultAccountFor ---

    @Test
    fun defaultAccountFor_returnsPolicyDefaultWhenOfferable() {
        val policy = FolderPolicy(defaultAccountSlug = "b")
        assertEquals(accB, defaultAccountFor(policy, allAccounts))
    }

    @Test
    fun defaultAccountFor_fallsBackToFirstOfferable_whenDefaultRemoved() {
        val policy = FolderPolicy(defaultAccountSlug = "removed-account")
        assertEquals(accA, defaultAccountFor(policy, allAccounts))
    }

    @Test
    fun defaultAccountFor_fallsBackToFirstOfferable_whenDefaultDisallowed() {
        // Default is a real account, but the allow-set excludes it.
        val policy = FolderPolicy(defaultAccountSlug = "a", allowedAccountSlugs = setOf("b", "c"))
        assertEquals(accB, defaultAccountFor(policy, allAccounts))
    }

    @Test
    fun defaultAccountFor_nullOnEmptyAccountList() {
        assertNull(defaultAccountFor(FolderPolicy(defaultAccountSlug = "a"), emptyList()))
        assertNull(defaultAccountFor(null, emptyList()))
    }

    @Test
    fun defaultAccountFor_nullPolicy_returnsFirstAccount() {
        assertEquals(accA, defaultAccountFor(null, allAccounts))
    }

    // --- ClaudeAccount.label / .subtitle ---

    @Test
    fun label_usesEmailWhenPresent() {
        assertEquals("a@x.com", accA.label)
    }

    @Test
    fun label_fallsBackToSlugWhenEmailBlank() {
        val account = ClaudeAccount(slug = "unprobed", email = "")
        assertEquals("unprobed", account.label)
    }

    @Test
    fun subtitle_joinsOrgAndSubscriptionType() {
        val account = ClaudeAccount(slug = "a", orgName = "Nekrachni", subscriptionType = "team")
        assertEquals("Nekrachni · team", account.subtitle)
    }

    @Test
    fun subtitle_omitsBlankParts_withoutDanglingSeparator() {
        val onlyOrg = ClaudeAccount(slug = "a", orgName = "Nekrachni", subscriptionType = "")
        assertEquals("Nekrachni", onlyOrg.subtitle)
        assertFalse(onlyOrg.subtitle.contains("·"))

        val onlySubscription = ClaudeAccount(slug = "a", orgName = "", subscriptionType = "team")
        assertEquals("team", onlySubscription.subtitle)

        val neither = ClaudeAccount(slug = "a", orgName = "", subscriptionType = "")
        assertEquals("", neither.subtitle)
    }

    // --- FolderPolicy.isEmpty ---

    @Test
    fun folderPolicy_isEmpty_trueOnlyWhenBothFieldsAreDefault() {
        assertTrue(FolderPolicy().isEmpty)
        assertFalse(FolderPolicy(defaultAccountSlug = "a").isEmpty)
        assertFalse(FolderPolicy(allowedAccountSlugs = setOf("a")).isEmpty)
        assertNotNull(FolderPolicy(defaultAccountSlug = "a", allowedAccountSlugs = setOf("a")))
    }

    // --- initials (status-bar tag) ---

    @Test
    fun initialsAreFirstLetterEachSideOfTheAt() {
        assertEquals("LN", ClaudeAccount("s", email = "lukashanak@nekrachni.cz").initials)
        assertEquals("LK", ClaudeAccount("s", email = "lukas@kontexta.cz").initials)
    }

    @Test
    fun initialsSkipLeadingPunctuationAndUppercase() {
        assertEquals("AB", ClaudeAccount("s", email = ".a.b@b-corp.io").initials)
        assertEquals("XY", ClaudeAccount("s", email = "X@yolo.dev").initials)
    }

    @Test
    fun initialsFallBackToSlugWhenEmailUnusable() {
        // A probe that came back without an email must still yield a readable tag
        // rather than a blank chip the user can't interpret.
        assertEquals("WO", ClaudeAccount("work-seat").initials)
        assertEquals("AC", ClaudeAccount("acme", email = "no-at-sign").initials)
        assertEquals("?", ClaudeAccount("---").initials)
    }

    // --- isAccountPreferred (soft folder guard) ---

    @Test
    fun everythingIsPreferredWithoutAPolicy() {
        assertTrue(isAccountPreferred(null, null))
        assertTrue(isAccountPreferred(null, "anything"))
        // An empty allow-set means unrestricted, so it must not warn either.
        assertTrue(isAccountPreferred(FolderPolicy(), "anything"))
        assertTrue(isAccountPreferred(FolderPolicy(defaultAccountSlug = "a"), "b"))
    }

    @Test
    fun preferenceFollowsTheAllowSetAndTreatsNullAsDefault() {
        val p = FolderPolicy(allowedAccountSlugs = setOf("work"))
        assertTrue(isAccountPreferred(p, "work"))
        assertTrue(!isAccountPreferred(p, "personal"))
        // null slug means the default account — it is NOT implicitly allowed when
        // the folder restricts to a specific seat.
        assertTrue(!isAccountPreferred(p, null))
        assertTrue(isAccountPreferred(FolderPolicy(allowedAccountSlugs = setOf(ClaudeAccount.DEFAULT_SLUG)), null))
    }
}
