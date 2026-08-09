package com.clauderemote.session.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extraction of the OAuth URL from a captured login pane. The terminal hard-wraps
 * the URL with no continuation marker, so a naive "take the line" would hand the
 * user a truncated link whose sign-in page 404s — which is exactly how the
 * add-account flow first failed.
 */
class AccountLoginUrlTest {

    private val realPane = """
        Welcome to Claude Code v2.1.220
         Browser didn't open? Use the url below to sign in (c to copy)
        https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88
        ed-5944d1962f5e&response_type=code&redirect_uri=https%3A%2F%2Fplatform.claude.co
        m%2Foauth%2Fcode%2Fcallback&scope=user%3Ainference&state=Ar5fH-ZQzIUTiVamlU35rKo
        scQxUht1D46GBDq20fsw
         Paste code here if prompted >
    """.trimIndent()

    @Test
    fun reassemblesAUrlWrappedAcrossLines() {
        val url = AccountService.extractLoginUrl(realPane)!!
        assertTrue(url.startsWith("https://claude.com/cai/oauth/authorize?"), url)
        // The tail must survive — a truncated state param makes the page reject it.
        assertTrue(url.endsWith("state=Ar5fH-ZQzIUTiVamlU35rKoscQxUht1D46GBDq20fsw"), url)
        assertTrue(!url.contains(" "), "URL must not contain whitespace: $url")
        assertTrue(!url.contains("Paste"), "prompt line must not be glued on: $url")
    }

    @Test
    fun stopsAtTheCodePromptAndAtProse() {
        val url = AccountService.extractLoginUrl(realPane)!!
        assertEquals(url.substringBefore("&state="), url.substringBefore("&state="))
        assertTrue(!url.contains("Browser"), url)
    }

    @Test
    fun handlesAUrlThatFitsOnOneLine() {
        val pane = "some header\nhttps://claude.com/cai/oauth/authorize?code=true&state=x\n\nPaste code here if prompted >"
        assertEquals("https://claude.com/cai/oauth/authorize?code=true&state=x", AccountService.extractLoginUrl(pane))
    }

    @Test
    fun returnsNullBeforeTheUrlRenders() {
        assertNull(AccountService.extractLoginUrl(""))
        assertNull(AccountService.extractLoginUrl("Welcome to Claude Code v2.1.220\nStarting…"))
        // A non-oauth link in the pane must not be mistaken for the sign-in URL.
        assertNull(AccountService.extractLoginUrl("see https://example.com/docs for help"))
    }

    // --- tmux session naming ---

    @Test
    fun loginPaneNameHasNoDots() {
        // tmux rewrites '.' in a session name (it's the window.pane separator), so
        // a dotted name creates a session the app can never target again — the
        // pane exists but every has-session/capture-pane says "can't find".
        val name = AccountService.loginTmuxName("hanakl@nekrachni.cz")
        assertEquals("claude-login-hanakl_nekrachni_cz", name)
        assertTrue(name.none { it == '.' || it == ':' || it == '@' }, name)
    }

    @Test
    fun loginPaneNameKeepsPlainSlugsIntact() {
        assertEquals("claude-login-lukas-kontexta-cz", AccountService.loginTmuxName("lukas-kontexta-cz"))
        assertEquals("claude-login-work", AccountService.loginTmuxName("work"))
    }

    // --- which account a running session is on ---

    @Test
    fun readsTheSlugOutOfAProbedConfigDir() {
        // Real probe output from a session running under the second account.
        assertEquals(
            "lukas-kontexta-cz",
            AccountService.parseSessionAccountSlug("/home/lucas/.claude-remote/accounts/lukas-kontexta-cz\n"),
        )
        assertEquals(
            "hanakl@nekrachni.cz",
            AccountService.parseSessionAccountSlug("/home/lucas/.claude-remote/accounts/hanakl@nekrachni.cz"),
        )
    }

    @Test
    fun defaultAccountProbesAsNull() {
        // No variable set at all is the DEFAULT login, not "unknown" — the chip
        // must show the default rather than going blank.
        assertNull(AccountService.parseSessionAccountSlug("__DEFAULT__"))
        assertNull(AccountService.parseSessionAccountSlug(""))
        assertNull(AccountService.parseSessionAccountSlug("\n  \n"))
    }

    @Test
    fun aConfigDirOutsideTheAccountsRootIsTreatedAsDefault() {
        // Someone running with CLAUDE_CONFIG_DIR pointed somewhere of their own
        // isn't on one of our accounts; claiming a slug there would be a lie.
        assertNull(AccountService.parseSessionAccountSlug("/home/lucas/.claude"))
        assertNull(AccountService.parseSessionAccountSlug("/tmp/scratch-config"))
    }

    @Test
    fun trailingPathSegmentsDoNotLeakIntoTheSlug() {
        assertEquals(
            "work",
            AccountService.parseSessionAccountSlug("/home/lucas/.claude-remote/accounts/work/projects"),
        )
    }

    @Test
    fun probeTargetsTheSessionWithASingleExactMatchQuote() {
        // Regression: the target was built single-quoted AND wrapped in double
        // quotes, so tmux read the apostrophes as part of the name ("can't find
        // window: 'claude-...'"). Every probe then fell through to __DEFAULT__ and
        // every session claimed the default account.
        val cmd = AccountService.sessionAccountCmd("claude-server-kontexta-yolo--files")
        assertTrue(cmd.contains("-t '=claude-server-kontexta-yolo--files'"), cmd)
        assertTrue(!cmd.contains("\"'="), "target must not be double-wrapped: $cmd")
    }

    @Test
    fun probeEscapesAnApostropheInTheSessionName() {
        val cmd = AccountService.sessionAccountCmd("weird'name")
        assertTrue(cmd.contains("""-t '=weird'\''name'"""), cmd)
    }
}

/**
 * The shared/private split is the whole isolation boundary, so it is pinned
 * rather than left to a comment.
 */
class AccountSharedEntriesTest {

    @Test
    fun identityAndLockStateIsNeverLinked() {
        // Sharing any of these would either merge two accounts' identity or undo
        // the per-config-dir locking that makes concurrent accounts safe.
        val linked = AccountService.LINKED_ENTRIES
        for (private in listOf(
            ".credentials.json", ".claude.json", ".storage-write",
            "daemon.lock", "sessions", "statsig",
        )) {
            assertTrue(private !in linked, "$private must not be shared")
        }
    }

    @Test
    fun userContentIsLinked() {
        val linked = AccountService.LINKED_ENTRIES
        // projects carries transcripts AND the memory dir, which is why switching
        // account keeps both; hud is what the OMC statusline resolves against.
        for (shared in listOf("projects", "plugins", "settings.json", "CLAUDE.md", "hud", "history.jsonl")) {
            assertTrue(shared in linked, "$shared should be shared")
        }
    }
}
