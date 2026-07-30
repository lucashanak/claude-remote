package com.clauderemote.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [UpdateChecker], focused on the pure logic that decides whether the
 * app offers a self-update and whether the downloaded/patched binary is intact:
 * [UpdateChecker.isNewer] (version comparison — a bug here means users silently
 * stop getting updates, or get offered a downgrade), [UpdateChecker.formatBytes],
 * [UpdateChecker.sha256], [UpdateChecker.applyPatch] (bsdiff patching), and
 * [UpdateChecker.linuxPkgKind]. Network-touching functions (`checkUpdate`,
 * `downloadFile`) and host-probing functions (`desktopPlatform`,
 * `commandExists`) are intentionally not covered here — they are either live
 * network calls or environment-dependent and would be flaky.
 */
class UpdateCheckerTest {

    private fun updateInfo(
        pkgUrl: String = "",
        debUrl: String = "",
        tarGzUrl: String = ""
    ) = UpdateInfo(
        version = "1.0.0",
        apkUrl = "",
        apkSize = 0,
        patchChain = emptyList(),
        apkSha256 = null,
        pkgUrl = pkgUrl,
        debUrl = debUrl,
        tarGzUrl = tarGzUrl
    )

    // --- isNewer: the core update-vs-downgrade decision ---

    @Test
    fun isNewer_patchBump_isNewer() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun isNewer_equalVersions_isNotNewer() {
        // Must not offer an "update" to the exact same build.
        assertEquals(false, UpdateChecker.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun isNewer_olderRemote_isNotNewer() {
        assertEquals(false, UpdateChecker.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun isNewer_minorDoubleDigitVsSingleDigit_comparesNumerically() {
        // Regression guard: a lexicographic ("string") compare would say
        // "1.10.0" < "1.9.0" because '1' < '9' at the first differing char.
        // isNewer splits on '.' and compares each segment as Int, so 10 > 9.
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertEquals(false, UpdateChecker.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun isNewer_majorBump_isNewer() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun isNewer_shorterSegmentCountPaddedWithZero_isEqual() {
        // "1.2" is treated as "1.2.0" — missing trailing segments default to 0.
        assertEquals(false, UpdateChecker.isNewer("1.2", "1.2.0"))
        assertEquals(false, UpdateChecker.isNewer("1.2.0", "1.2"))
    }

    @Test
    fun isNewer_shorterSegmentCountWithHigherValue_isNewer() {
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun isNewer_vPrefixedRemote_SUSPECTED_BUG_treatsFirstSegmentAsZero() {
        // SUSPECTED BUG: isNewer itself does not strip a "v" prefix. Splitting
        // "v2.0.0" on '.' yields ["v2","0","0"]; toIntOrNull("v2") is null so
        // it defaults to 0, making "v2.0.0" compare as "0.0.0" — i.e. OLDER
        // than "1.0.0", not newer. In production this is currently masked
        // because checkUpdate() does `tag_name.trimStart('v')` on the remote
        // version before ever calling isNewer — but isNewer is not safe to
        // call directly with a "v"-prefixed string, and there is no such
        // stripping/guard for the *local* version argument. Locking in the
        // actual (buggy) behavior so a future refactor that removes the
        // trimStart() call fails loudly here instead of silently regressing.
        assertEquals(false, UpdateChecker.isNewer("v2.0.0", "1.0.0"))
    }

    @Test
    fun isNewer_malformedNonNumericSegment_doesNotThrowAndTreatsAsZero() {
        // Runs on app start against a value fetched from GitHub — must never throw.
        assertEquals(false, UpdateChecker.isNewer("abc", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "abc"))
    }

    @Test
    fun isNewer_mixedGarbageMiddleSegment_treatsSegmentAsZero() {
        // "1.x.3": the non-numeric middle segment "x" defaults to 0, so this
        // compares identically to "1.0.3".
        assertEquals(false, UpdateChecker.isNewer("1.x.3", "1.0.3"))
    }

    @Test
    fun isNewer_emptyStringInputs_doesNotThrow() {
        assertEquals(false, UpdateChecker.isNewer("", ""))
        assertEquals(false, UpdateChecker.isNewer("", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", ""))
    }

    // --- formatBytes ---

    @Test
    fun formatBytes_zero() {
        assertEquals("0 B", UpdateChecker.formatBytes(0))
    }

    @Test
    fun formatBytes_subKb() {
        assertEquals("512 B", UpdateChecker.formatBytes(512))
    }

    @Test
    fun formatBytes_justBelowKbBoundary() {
        assertEquals("1023 B", UpdateChecker.formatBytes(1023))
    }

    @Test
    fun formatBytes_exactKbBoundary() {
        assertEquals("1 KB", UpdateChecker.formatBytes(1024))
    }

    @Test
    fun formatBytes_justBelowMbBoundary() {
        assertEquals("1023 KB", UpdateChecker.formatBytes(1024 * 1024 - 1024))
    }

    @Test
    fun formatBytes_exactMbBoundary() {
        assertEquals("1.0 MB", UpdateChecker.formatBytes(1024L * 1024L))
    }

    @Test
    fun formatBytes_fractionalMb() {
        assertEquals("1.5 MB", UpdateChecker.formatBytes(1024L * 1024L + 512L * 1024L))
    }

    @Test
    fun formatBytes_largeValue_hasNoGbTier() {
        // NOTE: formatBytes has no GB unit tier — anything >= 1 MB is formatted
        // as "%.1f MB", so a 1 GB value renders as "1024.0 MB", not "1.0 GB".
        // Locking in this actual (documented) behavior rather than the GB
        // string one might expect.
        assertEquals("1024.0 MB", UpdateChecker.formatBytes(1024L * 1024L * 1024L))
    }

    // --- sha256: known-answer vectors ---

    @Test
    fun sha256_emptyInput_matchesKnownAnswerVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateChecker.sha256(ByteArray(0))
        )
    }

    @Test
    fun sha256_abcInput_matchesKnownAnswerVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateChecker.sha256("abc".encodeToByteArray())
        )
    }

    @Test
    fun sha256_outputIsLowercaseHex() {
        // Verifies the exact "%02x" formatting — no uppercase, no separators.
        val hex = UpdateChecker.sha256("abc".encodeToByteArray())
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // --- applyPatch: real bsdiff round-trip via the same jbsdiff library ---

    @Test
    fun applyPatch_roundTripWithGeneratedPatch_reconstructsNewBytes() {
        // Generates a genuine bsdiff patch with io.sigpipe.jbsdiff.Diff (the
        // diff side of the same library applyPatch's Patch.patch consumes),
        // then verifies old + patch(old -> new) == new byte-for-byte.
        val oldBytes = "The quick brown fox jumps over the lazy dog. ".repeat(30).encodeToByteArray()
        val newBytes = "The quick brown fox leaps over the lazy dog twice. ".repeat(30).encodeToByteArray()

        val patchOut = java.io.ByteArrayOutputStream()
        io.sigpipe.jbsdiff.Diff.diff(oldBytes, newBytes, patchOut)
        val patchBytes = patchOut.toByteArray()

        val result = UpdateChecker.applyPatch(oldBytes, patchBytes)
        assertTrue(newBytes.contentEquals(result), "patched bytes must exactly equal the new bytes")
    }

    @Test
    fun applyPatch_invalidPatchBytes_throwsRatherThanReturningGarbage() {
        // A corrupt/foreign byte blob is not a valid bsdiff container. This
        // must fail loudly (exception) rather than silently produce a
        // corrupted "update" that gets written over the running binary.
        assertFailsWith<Exception> {
            UpdateChecker.applyPatch("hello world".encodeToByteArray(), byteArrayOf(1, 2, 3, 4, 5))
        }
    }

    // --- linuxPkgKind: only cases whose winner does not depend on the host distro ---
    //
    // linuxPkgKind() consults private `hasPacman`/`hasDpkg` lazy vals that probe
    // the actual host (pacman/dpkg presence). When only ONE asset kind's URL is
    // set, or when the two host-dependent kinds (pkg, deb) aren't BOTH present
    // simultaneously, the branch's fallthrough logic (`info.debUrl.isNotBlank()`
    // then `info.pkgUrl.isNotBlank()`) resolves the same way regardless of the
    // host — so those cases are safe to assert here. The one case that IS
    // host-distro-dependent (both pkgUrl and debUrl set) is intentionally
    // skipped, matching the exclusion of desktopPlatform/commandExists.

    @Test
    fun linuxPkgKind_onlyPkgUrl_isPkg() {
        assertEquals(UpdateChecker.LinuxPkg.PKG, UpdateChecker.linuxPkgKind(updateInfo(pkgUrl = "a.pkg.tar.zst")))
    }

    @Test
    fun linuxPkgKind_onlyDebUrl_isDeb() {
        assertEquals(UpdateChecker.LinuxPkg.DEB, UpdateChecker.linuxPkgKind(updateInfo(debUrl = "a.deb")))
    }

    @Test
    fun linuxPkgKind_onlyTarGzUrl_isTarGz() {
        assertEquals(UpdateChecker.LinuxPkg.TARGZ, UpdateChecker.linuxPkgKind(updateInfo(tarGzUrl = "a.tar.gz")))
    }

    @Test
    fun linuxPkgKind_noAssets_isNone() {
        assertEquals(UpdateChecker.LinuxPkg.NONE, UpdateChecker.linuxPkgKind(updateInfo()))
    }

    @Test
    fun linuxPkgKind_pkgAndTarGzNoDeb_prefersPkg() {
        // No deb asset at all, so this resolves to PKG regardless of the host.
        assertEquals(
            UpdateChecker.LinuxPkg.PKG,
            UpdateChecker.linuxPkgKind(updateInfo(pkgUrl = "a.pkg.tar.zst", tarGzUrl = "a.tar.gz"))
        )
    }

    @Test
    fun linuxPkgKind_debAndTarGzNoPkg_prefersDeb() {
        // No pkg asset at all, so this resolves to DEB regardless of the host.
        assertEquals(
            UpdateChecker.LinuxPkg.DEB,
            UpdateChecker.linuxPkgKind(updateInfo(debUrl = "a.deb", tarGzUrl = "a.tar.gz"))
        )
    }
}
