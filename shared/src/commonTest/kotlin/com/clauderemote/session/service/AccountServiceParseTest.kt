package com.clauderemote.session.service

import com.clauderemote.model.ClaudeAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing of the server-side account probe. Worth pinning because a silent
 * failure here is invisible: the accounts screen would just render blank or
 * missing rows, and nothing else in the stack would complain.
 *
 * Input shape is one `===<slug>` marker per account followed by that account's
 * `claude auth status --json` output, which is what the probe script emits.
 */
class AccountServiceParseTest {

    private fun parse(out: String) = AccountService.parseAccounts(out)

    private fun statusJson(email: String, org: String, plan: String = "team") = """
        {
          "loggedIn": true,
          "authMethod": "claude.ai",
          "apiProvider": "firstParty",
          "email": "$email",
          "orgId": "9044c9b4-bf0b-468e-b456-ec80d8690e8c",
          "orgName": "$org",
          "subscriptionType": "$plan"
        }
    """.trimIndent()

    @Test
    fun parsesDefaultAndNamedAccounts() {
        val out = buildString {
            append("===default\n").append(statusJson("lukashanak@nekrachni.cz", "Nekrachni")).append('\n')
            append("===lukas-kontexta-cz\n").append(statusJson("lukas@kontexta.cz", "Kontexta")).append('\n')
        }
        val accounts = parse(out)
        assertEquals(2, accounts.size)
        assertEquals("lukashanak@nekrachni.cz", accounts[0].email)
        assertEquals("Nekrachni", accounts[0].orgName)
        assertEquals("team", accounts[0].subscriptionType)
        assertTrue(accounts[0].isDefault)
        assertEquals("lukas-kontexta-cz", accounts[1].slug)
        assertEquals("lukas@kontexta.cz", accounts[1].email)
        assertTrue(!accounts[1].isDefault)
    }

    @Test
    fun defaultAccountComesFirstRegardlessOfProbeOrder() {
        val out = buildString {
            append("===zeta-account\n").append(statusJson("z@example.com", "Zeta")).append('\n')
            append("===default\n").append(statusJson("me@example.com", "Mine")).append('\n')
        }
        assertTrue(parse(out).first().isDefault)
    }

    @Test
    fun handlesThreePlusAccounts() {
        // The feature is specified for 3+ accounts, so nothing may assume two.
        val out = buildString {
            append("===default\n").append(statusJson("a@x.com", "AOrg")).append('\n')
            append("===b-y-com\n").append(statusJson("b@y.com", "BOrg")).append('\n')
            append("===c-z-com\n").append(statusJson("c@z.com", "COrg")).append('\n')
            append("===d-w-com\n").append(statusJson("d@w.com", "DOrg")).append('\n')
        }
        val accounts = parse(out)
        assertEquals(4, accounts.size)
        assertEquals(listOf("a@x.com", "b@y.com", "c@z.com", "d@w.com"), accounts.map { it.email })
    }

    @Test
    fun accountWhoseProbeFailedStillAppears() {
        // A dir we can't probe (not logged in, claude missing, timeout) must NOT
        // vanish from the list — otherwise the user sees an account silently
        // disappear and has no way to repair it from the UI.
        val out = "===default\n" + statusJson("me@example.com", "Mine") + "\n" +
            "===broken-account\n\n"
        val accounts = parse(out)
        assertEquals(2, accounts.size)
        val broken = accounts.single { it.slug == "broken-account" }
        assertEquals("", broken.email)
        assertEquals("", broken.orgName)
        // label falls back to the slug so the row is still identifiable
        assertEquals("broken-account", broken.label)
    }

    @Test
    fun readsTheRenewalDeadlineAppendedByTheProbe() {
        // The probe greps ONE field out of .credentials.json and appends it to the
        // block; `auth status` itself never reports it. Shape is exactly what
        // `grep -o '"refreshTokenExpiresAt"[[:space:]]*:[[:space:]]*[0-9]*'` emits.
        val out = "===default\n" + statusJson("me@example.com", "Mine") + "\n" +
            "\"refreshTokenExpiresAt\":1789693630335\n"
        val a = parse(out).single()
        assertEquals(1789693630335L, a.loginExpiresAtMs)
        // 10 days out ⇒ 10 whole days left, and not yet worth nagging about.
        val tenDaysEarlier = 1789693630335L - 10L * 24 * 60 * 60 * 1000
        assertEquals(10, a.loginExpiresInDays(tenDaysEarlier))
        assertTrue(!a.loginNeedsRenewal(tenDaysEarlier))
        // Inside the warn window, and past it.
        val twoDaysEarlier = 1789693630335L - 2L * 24 * 60 * 60 * 1000
        assertTrue(a.loginNeedsRenewal(twoDaysEarlier))
        assertTrue(a.loginNeedsRenewal(1789693630335L + 1))
    }

    @Test
    fun aLoginWithNoReadableDeadlineIsUnknownNotExpired() {
        // No credentials file (never logged in, or unreadable) must not render as
        // "expired" — that would nag the user to re-login on every listing.
        val a = parse("===default\n" + statusJson("me@example.com", "Mine") + "\n").single()
        assertEquals(0L, a.loginExpiresAtMs)
        assertEquals(null, a.loginExpiresInDays(1789693630335L))
        assertTrue(!a.loginNeedsRenewal(1789693630335L))
    }

    @Test
    fun anExpiredLoginReportsNegativeDays() {
        val out = "===old\n\"refreshTokenExpiresAt\":1000000000000\n"
        val a = parse(out).single()
        // Exactly at the deadline is still "today", one ms past it is -1.
        assertEquals(0, a.loginExpiresInDays(1000000000000L))
        assertEquals(-1, a.loginExpiresInDays(1000000000001L))
        assertEquals(-2, a.loginExpiresInDays(1000000000000L + 24L * 60 * 60 * 1000 + 1))
    }

    @Test
    fun loggedOutAccountKeepsItsSlugAndHasNoLabels() {
        val out = "===stale-account\n" +
            """{"loggedIn": false, "authMethod": "none", "apiProvider": "firstParty"}""" + "\n"
        val a = parse(out).single()
        assertEquals("stale-account", a.slug)
        assertEquals("", a.email)
        assertEquals("stale-account", a.label)
    }

    @Test
    fun deduplicatesAStrayDefaultDir() {
        // `accounts/default` on disk would be dead weight (the default account is
        // ~/.claude and gets no CLAUDE_CONFIG_DIR), and must not double the row.
        val out = buildString {
            append("===default\n").append(statusJson("me@example.com", "Mine")).append('\n')
            append("===default\n").append(statusJson("me@example.com", "Mine")).append('\n')
        }
        assertEquals(1, parse(out).size)
    }

    @Test
    fun emptyAndMarkerlessOutputYieldNoAccounts() {
        assertEquals(emptyList(), parse(""))
        assertEquals(emptyList(), parse("\n\n"))
        // Output with no marker at all (e.g. ssh printed only a warning).
        assertEquals(emptyList(), parse("bash: claude: command not found\n"))
    }

    @Test
    fun toleratesMissingFieldsWithoutThrowing() {
        val out = "===partial\n{\"loggedIn\": true, \"email\": \"only@email.com\"}\n"
        val a = parse(out).single()
        assertEquals("only@email.com", a.email)
        assertEquals("", a.orgName)
        assertEquals("", a.subscriptionType)
        // subtitle must not render a dangling separator when both parts are blank
        assertEquals("", a.subtitle)
    }

    @Test
    fun defaultSlugMarksIsDefault() {
        val a = AccountService.toAccount(ClaudeAccount.DEFAULT_SLUG, statusJson("me@x.com", "Org"))
        assertTrue(a.isDefault)
        assertTrue(!AccountService.toAccount("other", statusJson("me@x.com", "Org")).isDefault)
    }
}
