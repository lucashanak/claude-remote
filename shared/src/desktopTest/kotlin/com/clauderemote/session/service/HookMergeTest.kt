package com.clauderemote.session.service

import com.clauderemote.testsupport.safeDeleteTree
import com.clauderemote.testsupport.LiveFileCanary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The settings.json merge of hooks v2 ([HookCommands]), executed for real with python3
 * against a throw-away HOME. Every test runs inside [LiveFileCanary.guard]: the live
 * `~/.claude/settings.json`, the account settings and `~/.claude-remote` / `~/.cache/claude-remote`
 * are fingerprinted before and after, and a unique token that is embedded in the fixture
 * must not show up in any of them. The legacy Stop ensure is run with the TEST home too,
 * so its marker command carries the test path (with the live path it would look "stale").
 */
class HookMergeTest {
    private val canary = LiveFileCanary()
    private val token = LiveFileCanary.newToken()
    private lateinit var root: File
    private lateinit var home: File
    private val json = Json

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("hookmerge").toFile()
        home = File(root, "home").apply { mkdirs() }
    }

    @AfterTest fun tearDown() { safeDeleteTree(root) }

    private fun <T> guarded(block: (LiveFileCanary.GuardEnv) -> T): T = canary.guard(token, home, root, block)

    private class Out(val code: Int, val text: String)

    private fun exec(env: LiveFileCanary.GuardEnv, argv: List<String>, stdin: String? = null, timeoutS: Long = 60): Out {
        val pb = ProcessBuilder(argv).directory(root).redirectErrorStream(true)
        env.applyTo(pb)
        val p = pb.start()
        p.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout: ${argv.take(3)}") }
        return Out(p.exitValue(), text)
    }

    private fun merge(env: LiveFileCanary.GuardEnv, mode: String = "install", script: String = HookCommands.hooksV2Py(mode)): Out =
        exec(env, listOf("python3", "-I", "-"), stdin = script)

    private fun legacy(env: LiveFileCanary.GuardEnv): String = exec(env, listOf("bash", "-c", LEGACY_STOP_HOOK_COMMAND)).text.trim()

    private val settings get() = File(home, ".claude/settings.json")
    private fun read(f: File = settings): JsonObject = json.parseToJsonElement(f.readText()) as JsonObject
    private fun write(text: String, f: File = settings) { f.parentFile.mkdirs(); f.writeText(text) }

    /** The real box shape (matchers, timeouts, other top-level keys) per the survey of the live settings. */
    private fun realBoxShape(): String = """
        {
          "env": {"CRIT_TOKEN": "$token", "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"},
          "permissions": {"allow": ["Bash(ls:*)"], "defaultMode": "bypassPermissions"},
          "alwaysThinkingEnabled": true,
          "effortLevel": "high",
          "enabledPlugins": {"serena@x": true},
          "statusLine": {"type": "command", "command": "echo ä"},
          "theme": "dark",
          "hooks": {
            "PreToolUse": [
              {"matcher": "", "hooks": [{"type": "command", "command": "serena-hooks remind --client=claude-code"}]},
              {"matcher": "mcp__serena__*", "hooks": [{"type": "command", "command": "serena-hooks auto-approve --client=claude-code"}]},
              {"matcher": "Bash", "hooks": [{"type": "command", "command": "python3 ${'$'}HOME/.claude/hooks/rm-hola-promenna.py", "timeout": 10}]}
            ],
            "SessionStart": [
              {"matcher": "", "hooks": [{"type": "command", "command": "serena-hooks activate --client=claude-code"}]},
              {"matcher": "", "hooks": [{"type": "command", "command": "node ${'$'}{CLAUDE_CONFIG_DIR:-${'$'}HOME/.claude}/hooks/verified-ledger.mjs", "timeout": 5}]}
            ]
          }
        }
    """.trimIndent()

    /** [realBoxShape] plus the legacy claude-remote Stop entry generated with the test HOME. */
    private fun realBoxWithLegacyStop(env: LiveFileCanary.GuardEnv) {
        write(realBoxShape())
        assertEquals("HOOK_FIXED", legacy(env))
    }

    private fun events(name: String, f: File = settings): List<JsonElement> = ((read(f)["hooks"] as JsonObject)[name] as? JsonArray)?.toList().orEmpty()
    private fun isV2(e: JsonElement) = e.toString().contains("claude-remote-ev")
    private fun foreign(o: JsonObject): Map<String, List<String>> =
        ((o["hooks"] as? JsonObject).orEmpty()).mapValues { (_, v) -> (v as JsonArray).filterNot(::isV2).map { it.toString() }.sorted() }
    private fun nonHooks(o: JsonObject): Map<String, JsonElement> = o.filterKeys { it != "hooks" }

    private fun text(e: JsonElement?): String = (e as kotlinx.serialization.json.JsonPrimitive).content
    private fun result(o: Out) = o.text.lineSequence().first { it.startsWith("HOOKS_V2_") && !it.startsWith("HOOKS_V2_DETAIL") }.trim()

    // ---- the happy paths ---------------------------------------------------

    @Test fun anEmptyHomeGetsAllSevenEntriesAndTheMarker() = guarded { env ->
        val o = merge(env)
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        val hooks = read()["hooks"] as JsonObject
        assertEquals(HookCommands.WANTED.map { it.first }.toSet(), hooks.keys)
        for ((event, matcher, ev) in HookCommands.WANTED) {
            val entries = events(event)
            assertEquals(1, entries.size, event)
            val entry = entries.single() as JsonObject
            assertEquals(matcher, text(entry["matcher"]), "matcher of $event")
            val h = (entry["hooks"] as JsonArray).single() as JsonObject
            assertEquals(HookCommands.entryCommand(ev), text(h["command"]), "exact command for $ev")
            assertEquals("5", text(h["timeout"]), "timeout of $event")
            assertEquals("command", text(h["type"]))
        }
        val marker = File(home, ".claude/${HookCapContract.MARKER_FILE_NAME}")
        assertTrue(marker.isFile, "marker written after the replace")
        assertTrue(HookCapContract.parseInstalled(marker.readText()) is HookCapContract.Installed.Epoch, marker.readText())
        assertTrue(File(home, ".claude/settings.json.claude-remote.lock").isFile, "the legacy lock file is the one used")
    }

    @Test fun theRealBoxShapeKeepsEveryForeignEntryAndKey() = guarded { env ->
        realBoxWithLegacyStop(env)
        val before = read()
        val o = merge(env)
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        val after = read()
        assertEquals(nonHooks(before), nonHooks(after), "every other top-level key is semantically unchanged")
        assertEquals(foreign(before), foreign(after).filterKeys { it in foreign(before).keys }, "every entry without the marker is unchanged")
        assertEquals(setOf("PreToolUse", "SessionStart", "Stop", "UserPromptSubmit", "PostToolUse", "PermissionRequest", "SessionEnd"), (after["hooks"] as JsonObject).keys)
        assertEquals(3 + 1, events("PreToolUse").size, "3 foreign + the AskUserQuestion entry")
        assertEquals(2 + 1, events("SessionStart").size)
        assertEquals(1 + 1, events("Stop").size, "the legacy Stop entry and the separate v2 Stop entry")
        assertTrue(events("Stop").count { it.toString().contains("claude-remote-notify") } == 1)
        assertTrue(events("Stop").count { isV2(it) } == 1)
        assertTrue(settings.readText().contains("ä") || settings.readText().contains("\\u00e4"), "non-ASCII survives")
    }

    @Test fun aSecondRunReportsExistsAndTouchesNothing() = guarded { env ->
        realBoxWithLegacyStop(env)
        merge(env)
        val bytes = settings.readBytes()
        val marker = File(home, ".claude/${HookCapContract.MARKER_FILE_NAME}")
        val markerBytes = marker.readBytes()
        val inode = Files.getAttribute(settings.toPath(), "unix:ino")
        val o = merge(env)
        assertEquals("HOOKS_V2_EXISTS", result(o), o.text)
        assertTrue(bytes.contentEquals(settings.readBytes()), "byte-identical")
        assertEquals(inode, Files.getAttribute(settings.toPath(), "unix:ino"), "not even rewritten")
        assertTrue(markerBytes.contentEquals(marker.readBytes()))
        assertEquals(1, settings.parentFile.listFiles { f -> f.name.contains(".claude-remote.bak.") }!!.size, "one backup, from the first run only")
    }

    @Test fun anExistsRunRestoresAMissingMarker() = guarded { env ->
        merge(env)
        File(home, ".claude/${HookCapContract.MARKER_FILE_NAME}").delete()
        assertEquals("HOOKS_V2_EXISTS", result(merge(env)))
        assertTrue(File(home, ".claude/${HookCapContract.MARKER_FILE_NAME}").isFile)
    }

    // ---- mixed fleet -------------------------------------------------------

    @Test fun oldThenV2ThenOldClientLeavesTheFileByteIdentical() = guarded { env ->
        realBoxWithLegacyStop(env)
        assertEquals("HOOKS_V2_FIXED", result(merge(env)))
        val afterV2 = settings.readBytes()
        val inode = Files.getAttribute(settings.toPath(), "unix:ino")
        assertEquals("HOOK_EXISTS", legacy(env), "the legacy ensure sees its canonical Stop entry and the v2 one is no stale marker")
        assertTrue(afterV2.contentEquals(settings.readBytes()), "byte-identical after the old client ran")
        assertEquals(inode, Files.getAttribute(settings.toPath(), "unix:ino"))
    }

    @Test fun buildNThenNPlus1ThenNLeavesTheFileByteIdentical() = guarded { env ->
        realBoxWithLegacyStop(env)
        assertEquals("HOOKS_V2_FIXED", result(merge(env)))
        val v2 = HookCommands.hooksV2Py("install").replace("VER = ${HookCommands.ENTRY_VERSION}\n", "VER = ${HookCommands.ENTRY_VERSION + 1}\n")
        assertNotEquals(HookCommands.hooksV2Py("install"), v2, "the version bump was applied to the script")
        assertEquals("HOOKS_V2_FIXED", result(merge(env, script = v2)), "the newer build replaces the older entries")
        val bytes = settings.readBytes()
        val o = merge(env)
        assertEquals("HOOKS_V2_NEWER", result(o), o.text)
        assertTrue(bytes.contentEquals(settings.readBytes()), "the older build must not rewrite (no flapping between builds)")
        assertEquals("HOOKS_V2_EXISTS", result(merge(env, script = v2)))
    }

    @Test fun anOlderEntryIsReplacedByTheCanonicalOneAndDuplicatesCollapse() = guarded { env ->
        write("""{"hooks":{"Stop":[{"matcher":"","hooks":[{"type":"command","command":": claude-remote-ev v0; old"}]},
            {"matcher":"","hooks":[{"type":"command","command":": claude-remote-ev v1; stale duplicate"}]}],
            "Notification":[{"matcher":"","hooks":[{"type":"command","command":": claude-remote-ev v1; removed event"}]}]}}""")
        assertEquals("HOOKS_V2_FIXED", result(merge(env)))
        assertEquals(1, events("Stop").size)
        val only = (((events("Stop").single() as JsonObject)["hooks"] as JsonArray).single() as JsonObject)
        assertEquals(HookCommands.entryCommand("stop"), text(only["command"]))
        assertFalse((read()["hooks"] as JsonObject).containsKey("Notification"), "a v2 entry under an event we no longer use is dropped with its list")
    }

    // ---- refusals ----------------------------------------------------------

    @Test fun unparseableJsonIsFailedAndNothingIsWritten() = guarded { env ->
        write("{ not json")
        val o = merge(env)
        assertEquals("HOOKS_V2_FAILED", result(o), o.text)
        assertEquals("{ not json", settings.readText())
        assertFalse(File(home, ".claude/${HookCapContract.MARKER_FILE_NAME}").exists())
        assertEquals(0, settings.parentFile.listFiles { f -> f.name.contains(".bak.") }!!.size)
    }

    @Test fun unexpectedShapesAreFailedAndUntouched() = guarded { env ->
        for (shape in listOf("[1,2]", "\"str\"", """{"hooks":[]}""", """{"hooks":{"Stop":{}}}""", """{"hooks":{"Stop":"x"}}""")) {
            write(shape)
            val o = merge(env)
            assertEquals("HOOKS_V2_FAILED", result(o), "$shape: ${o.text}")
            assertEquals(shape, settings.readText(), "$shape untouched")
        }
    }

    // ---- foreign hooks inside an entry of ours -----------------------------

    /** A Stop entry that carries our (older) hook AND a foreign hook, as left by `/hooks` or a hand edit. */
    private fun sharedEntry(): String {
        val (event, matcher, ev) = HookCommands.WANTED.first()
        val ours = HookCommands.entryCommand(ev).replace("claude-remote-ev v${HookCommands.ENTRY_VERSION}", "claude-remote-ev v0")
        return """{"hooks":{"$event":[{"matcher":"$matcher","hooks":[
            {"type":"command","command":"foreign-in-the-same-entry"},
            {"type":"command","command":${kotlinx.serialization.json.JsonPrimitive(ours)}}]}]}}"""
    }

    @Test fun aForeignHookSharingAnEntryWithOursSurvivesTheInstallAndTheRemoval() = guarded { env ->
        write(sharedEntry())
        val o = merge(env)
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        val after = settings.readText()
        assertTrue(after.contains("foreign-in-the-same-entry"), "only OUR items are taken out of the entry:\n$after")
        val ofFirstEvent = events(HookCommands.WANTED.first().first).joinToString("")
        assertEquals(1, Regex("claude-remote-ev v${HookCommands.ENTRY_VERSION}").findAll(ofFirstEvent).count(), "exactly one of ours, canonical: $ofFirstEvent")
        assertFalse(ofFirstEvent.contains("claude-remote-ev v0"), "the old one is gone")
        assertEquals("HOOKS_V2_REMOVE_FIXED", result(merge(env, "remove")))
        val removed = settings.readText()
        assertTrue(removed.contains("foreign-in-the-same-entry"), "the removal keeps it too:\n$removed")
        assertFalse(removed.contains("claude-remote-ev"))
    }

    @Test fun theVerificationAlsoComparesForeignHooksInsideEntriesThatCarryOurMarker() = guarded { env ->
        write(sharedEntry())
        val original = settings.readBytes()
        val keep = "        others = strip_list(lst)\n        hooks[name] = others + [want]\n"
        val script = HookCommands.hooksV2Py("install")
        assertTrue(script.contains(keep), "injection point exists")
        // a merge bug that drops the whole entry that carries our marker (and the foreign hook in it)
        // must be caught after the write: the check compares foreign hooks inside such entries too
        val o = merge(env, script = script.replace(keep, "        others = [e for e in lst if not is_ev(e)]\n        hooks[name] = others + [want]\n"))
        assertEquals("HOOKS_V2_FAILED", result(o), o.text)
        assertTrue(original.contentEquals(settings.readBytes()), "rolled back")
    }

    @Test fun theRollbackLeavesAFileAloneThatSomebodyElseRewroteAfterOurWrite() = guarded { env ->
        write(realBoxShape())
        val point = "        os.replace(tmp, rp)\n"
        val script = HookCommands.hooksV2Py("install")
        assertTrue(script.contains(point), "injection point exists")
        // Claude rewrites the file right after our replace and its version fails the comparison
        val theirs = """{"theme":"written-by-claude"}"""
        val o = merge(env, script = script.replace(point, point + "        open(rp, 'w').write('" + theirs + "')\n"))
        assertEquals("HOOKS_V2_FAILED", result(o), o.text)
        assertEquals(theirs, settings.readText(), "their newer write is not destroyed by a rollback")
    }

    @Test fun anEntryWhoseHooksIsNotAListIsLeftAloneAndDoesNotCrashTheMerge() = guarded { env ->
        write("""{"hooks":{"Notification":[{"matcher":"","hooks":5,"command":"foreign-cmd"}]}}""")
        val o = merge(env)
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        assertTrue(settings.readText().contains("foreign-cmd"), settings.readText())
        assertEquals("HOOKS_V2_REMOVE_FIXED", result(merge(env, "remove")))
        assertTrue(settings.readText().contains("foreign-cmd"))
    }

    @Test fun nonAsciiTextIsWrittenAsUtf8NotAsEscapes() = guarded { env ->
        write("""{"statusLine":{"type":"command","command":"echo Příliš žluťoučký kůň"},"hooks":{}}""")
        merge(env)
        val text = settings.readText()
        assertTrue(text.contains("Příliš žluťoučký kůň"), "the user's own text is byte-for-byte what they wrote:\n$text")
        assertFalse(text.contains("\\u"), "no \\u escapes appear")
        merge(env, "remove")
        assertTrue(settings.readText().contains("Příliš žluťoučký kůň"))
    }

    @Test fun aFileInTheStandardLayoutComesBackByteIdenticalAfterInstallAndRemoveCzechTextIncluded() = guarded { env ->
        val original = "{\n  \"statusLine\": {\n    \"type\": \"command\",\n    \"command\": \"echo Příliš žluťoučký kůň\"\n  },\n  \"theme\": \"dark\"\n}\n"
        write(original)
        assertEquals("HOOKS_V2_FIXED", result(merge(env)))
        val installed = settings.readText()
        assertTrue(installed.contains("\n  \"hooks\": {\n    \"") , "two-space layout kept: ${installed.take(300)}")
        assertEquals("HOOKS_V2_REMOVE_FIXED", result(merge(env, "remove")))
        assertEquals(original, settings.readText(), "byte for byte, indentation and non-ASCII text included")
    }

    // ---- targets: symlinks and accounts -----------------------------------

    @Test fun accountSymlinksAreNeverReplacedByAFileAndGetTheirMarker() = guarded { env ->
        write(realBoxShape())
        val acct = File(home, ".claude-remote/accounts/work").apply { mkdirs() }
        Files.createSymbolicLink(File(acct, "settings.json").toPath(), settings.toPath())
        val o = merge(env)
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        assertTrue(Files.isSymbolicLink(File(acct, "settings.json").toPath()), "os.replace went onto the real file, not onto the symlink")
        assertEquals(settings.toPath().toRealPath(), File(acct, "settings.json").toPath().toRealPath())
        assertTrue(File(acct, "settings.json").readText().contains("claude-remote-ev v"), "the account sees the hooks through its link")
        assertTrue(File(acct, HookCapContract.MARKER_FILE_NAME).isFile, "the account's own config dir gets its marker")
        assertTrue(o.text.contains("ACCOUNTS_MERGED=0"), "a symlink to the main file is not a second merge: ${o.text}")
    }

    @Test fun aSymlinkedMainSettingsFileIsUpdatedThroughTheLinkAndStaysALink() = guarded { env ->
        val dotfiles = File(root, "dotfiles").apply { mkdirs() }
        val real = File(dotfiles, "settings.json").apply { writeText(realBoxShape()) }
        File(home, ".claude").mkdirs()
        Files.createSymbolicLink(settings.toPath(), real.toPath())
        val o = merge(env)
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        assertTrue(Files.isSymbolicLink(settings.toPath()), "the link was not replaced by a regular file")
        assertTrue(real.readText().contains("claude-remote-ev v"), "the real file behind the link carries the hooks")
        assertEquals("HOOKS_V2_REMOVE_FIXED", result(merge(env, "remove")))
        assertTrue(Files.isSymbolicLink(settings.toPath()), "removal keeps the link too")
    }

    @Test fun aRegularAccountFileIsMergedToo() = guarded { env ->
        write(realBoxShape())
        val acct = File(home, ".claude-remote/accounts/other").apply { mkdirs() }
        File(acct, "settings.json").writeText("""{"hooks":{"Stop":[{"matcher":"","hooks":[{"type":"command","command":"acct-own"}]}]},"theme":"x"}""")
        val o = merge(env)
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        assertTrue(o.text.contains("ACCOUNTS_MERGED=1"), o.text)
        val own = read(File(acct, "settings.json"))
        assertTrue(own.toString().contains("acct-own") && own.toString().contains("claude-remote-ev v"))
        assertEquals("x", (own["theme"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(File(acct, HookCapContract.MARKER_FILE_NAME).isFile)
    }

    @Test fun anAccountWithoutASettingsFileIsLeftWithoutOne() = guarded { env ->
        write(realBoxShape())
        val acct = File(home, ".claude-remote/accounts/bare").apply { mkdirs() }
        merge(env)
        assertFalse(File(acct, "settings.json").exists(), "nothing is created in an account dir")
        assertFalse(File(acct, HookCapContract.MARKER_FILE_NAME).exists())
    }

    // ---- safety nets -------------------------------------------------------

    @Test fun aBackupIsKeptBeforeTheWriteAndOnlyTheNewestFiveSurvive() = guarded { env ->
        write(realBoxShape())
        val original = settings.readBytes()
        val dir = settings.parentFile
        for (i in 1..7) File(dir, "settings.json.claude-remote.bak.100000000$i").writeText("old $i")
        merge(env)
        val baks = dir.listFiles { f -> f.name.startsWith("settings.json.claude-remote.bak.") }!!.map { it.name }.sorted()
        assertEquals(5, baks.size, baks.toString())
        val newest = dir.listFiles { f -> f.name.startsWith("settings.json.claude-remote.bak.") }!!.maxByOrNull { it.name.substringAfterLast('.').toLong() }!!
        assertTrue(original.contentEquals(newest.readBytes()), "the newest backup is the pre-write state")
        assertTrue(PosixFilePermissions.toString(Files.getPosixFilePermissions(newest.toPath())) == "rw-------", "backups are private")
    }

    @Test fun aConcurrentWriteBetweenReadAndReplaceIsNotLost() = guarded { env ->
        write(realBoxShape())
        val hook = "        bak = backup(rp, raw) if raw is not None else None\n"
        val injected = hook + "        if attempt == 0:\n" +
            "            other = json.loads(open(rp).read()); other['added_by_claude'] = True\n" +
            "            t = rp + '.x'; open(t, 'w').write(json.dumps(other)); os.replace(t, rp)\n"
        val script = HookCommands.hooksV2Py("install")
        assertTrue(script.contains(hook), "injection point exists")
        val o = merge(env, script = script.replace(hook, injected))
        assertEquals("HOOKS_V2_FIXED", result(o), o.text)
        assertTrue(read().containsKey("added_by_claude"), "the compare-and-swap noticed the change and merged on top of it")
        assertTrue(settings.readText().contains("claude-remote-ev v"))
    }

    @Test fun aMergeThatWouldDropAForeignEntryIsCaughtAfterTheWriteAndRolledBack() = guarded { env ->
        write(realBoxShape())
        val original = settings.readBytes()
        val bug = "        hooks[name] = others + [want]\n"
        val script = HookCommands.hooksV2Py("install")
        assertTrue(script.contains(bug), "injection point exists")
        val o = merge(env, script = script.replace(bug, "        hooks[name] = [want]\n"))
        assertEquals("HOOKS_V2_FAILED", result(o), o.text)
        assertTrue(original.contentEquals(settings.readBytes()), "the backup was put back")
        assertFalse(File(home, ".claude/${HookCapContract.MARKER_FILE_NAME}").exists(), "no marker for a failed install")
    }

    @Test fun theMergeWaitsForTheLegacyLock() = guarded { env ->
        write(realBoxShape())
        val lockFile = File(home, ".claude/settings.json.claude-remote.lock")
        val holder = ProcessBuilder("bash", "-c", "exec 9>>\"${lockFile.absolutePath}\"; flock -x 9; echo locked; sleep 2")
            .redirectErrorStream(true).start()
        try {
            assertEquals("locked", holder.inputStream.bufferedReader().readLine())
            val t0 = System.nanoTime()
            val o = merge(env)
            val waitedMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals("HOOKS_V2_FIXED", result(o), o.text)
            assertTrue(waitedMs >= 1_200, "the merge must have waited for the lock holder (waited ${waitedMs}ms)")
        } finally {
            holder.destroyForcibly()
        }
    }

    @Test fun theFileModeIsPreserved() = guarded { env ->
        write(realBoxShape())
        Files.setPosixFilePermissions(settings.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        merge(env)
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(settings.toPath())))
    }

    // ---- remove and status ------------------------------------------------

    @Test fun removalRestoresTheForeignContentAndDropsTheMarkers() = guarded { env ->
        realBoxWithLegacyStop(env)
        val before = read()
        merge(env)
        val o = merge(env, "remove")
        assertEquals("HOOKS_V2_REMOVE_FIXED", result(o), o.text)
        val after = read()
        assertEquals(before, after, "back to the semantic state before the install, legacy Stop entry included")
        assertFalse(File(home, ".claude/${HookCapContract.MARKER_FILE_NAME}").exists())
        assertEquals("HOOKS_V2_REMOVE_EXISTS", result(merge(env, "remove")), "nothing left to remove")
    }

    @Test fun removalOfAFileThatOnlyHadV2EntriesRemovesTheHooksKey() = guarded { env ->
        merge(env)
        assertEquals("HOOKS_V2_REMOVE_FIXED", result(merge(env, "remove")))
        assertFalse(read().containsKey("hooks"))
    }

    @Test fun removalAlsoRemovesANewerVersionOnTheUsersOrder() = guarded { env ->
        val v2 = HookCommands.hooksV2Py("install").replace("VER = ${HookCommands.ENTRY_VERSION}\n", "VER = 9\n")
        merge(env, script = v2)
        assertEquals("HOOKS_V2_REMOVE_FIXED", result(merge(env, "remove")))
        assertFalse(settings.readText().contains("claude-remote-ev"))
    }

    @Test fun statusIsReadOnlyAndSaysWhatIsThere() = guarded { env ->
        fun status(): Pair<String, String> {
            val o = merge(env, "status")
            return o.text.lineSequence().first { it.startsWith("HOOKS_V2_STATE=") }.substringAfter('=').trim() to
                o.text.lineSequence().first { it.startsWith("HOOKEV_OFF=") }.substringAfter('=').trim()
        }
        assertEquals("ABSENT" to "0", status(), "no settings file at all")
        assertFalse(File(home, ".claude").exists(), "status creates nothing")
        write(realBoxShape())
        assertEquals("ABSENT" to "0", status())
        merge(env)
        assertEquals("INSTALLED" to "0", status())
        File(home, ".claude-remote").mkdirs()
        File(home, ".claude-remote/hookev.off").writeText("")
        assertEquals("INSTALLED" to "1", status())
        write(settings.readText().replace("\"timeout\": 5", "\"timeout\": 7"))
        assertEquals("PARTIAL", status().first, "an entry that is not the canonical one")
        write("{ nope")
        assertEquals("UNREADABLE", status().first)
        val bytes = settings.readBytes()
        status()
        assertTrue(bytes.contentEquals(settings.readBytes()))
        val newer = HookCommands.hooksV2Py("install").replace("VER = ${HookCommands.ENTRY_VERSION}\n", "VER = 9\n")
        write("{}")
        merge(env, script = newer)
        assertEquals("NEWER", status().first)
    }

    @Test fun theTokenInTheFixtureNeverLeavesTheTestHome() = guarded { env ->
        write(realBoxShape())
        merge(env)
        assertTrue(settings.readText().contains(token), "the token is in the TEST file (the canary then proves it is nowhere live)")
    }
}
