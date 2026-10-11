package com.clauderemote.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TurntableSheet] slices the sprite sheet turntable.py writes. A wrong slice
 * shows the wrong angle or half of two frames, so the arithmetic is pinned
 * against the generator's documented layout.
 */
class TurntableSheetTest {

    private val default = TurntableSheet(frames = 36, elevations = 3, columns = 12)

    @Test
    fun theShapeIsReadFromTheFileName() {
        assertEquals(default, TurntableSheet.parse("/home/lucas/3D/benchy.turntable-36x3c12.png"))
        assertEquals(TurntableSheet(24, 1, 8), TurntableSheet.parse("x.TURNTABLE-24x1c8.PNG"))
    }

    @Test
    fun anOrdinaryImageIsNotATurntable() {
        assertNull(TurntableSheet.parse("/tmp/shot.png"))
        assertNull(TurntableSheet.parse("/tmp/benchy_preview.png"))
        assertNull(TurntableSheet.parse("/tmp/x.turntable-36x3c12.jpg"))
    }

    @Test
    fun nonsenseShapesFallBackToAPlainImage() {
        assertNull(TurntableSheet.parse("a.turntable-1x3c12.png"))
        assertNull(TurntableSheet.parse("a.turntable-36x0c12.png"))
        assertNull(TurntableSheet.parse("a.turntable-36x3c0.png"))
    }

    /** The real sheet: 108 frames of 256 px in 12 columns = 3072 x 2304. */
    @Test
    fun theDefaultSheetFitsItsRenderedSize() {
        assertEquals(9, default.rows)
        assertEquals(256, default.frameSize(3072))
        assertTrue(default.fits(3072, 2304))
        assertFalse(default.fits(3072, 2000), "a truncated sheet must not be sliced")
        // Android halves it with inSampleSize: still whole 128 px frames.
        assertTrue(default.fits(1536, 1152))
        assertFalse(default.fits(2048, 1536 + 500), "2048 / 12 is not a whole frame side")
    }

    @Test
    fun framesAreRowMajorWithElevationsStackedLowToHigh() {
        assertEquals(0 to 0, default.frameOrigin(0, 0, 3072))
        assertEquals(11 * 256 to 0, default.frameOrigin(11, 0, 3072))
        assertEquals(0 to 256, default.frameOrigin(12, 0, 3072))
        // Elevation 1 starts at index 36 = row 3, column 0.
        assertEquals(0 to 3 * 256, default.frameOrigin(0, 1, 3072))
        assertEquals(11 * 256 to 8 * 256, default.frameOrigin(35, 2, 3072))
    }

    /** Spinning past the last frame comes round to the first; tilt clamps. */
    @Test
    fun azimuthWrapsBothWaysAndElevationClamps() {
        assertEquals(default.frameOrigin(0, 1, 3072), default.frameOrigin(36, 1, 3072))
        assertEquals(default.frameOrigin(35, 1, 3072), default.frameOrigin(-1, 1, 3072))
        assertEquals(default.frameOrigin(5, 2, 3072), default.frameOrigin(5, 9, 3072))
        assertEquals(default.frameOrigin(5, 0, 3072), default.frameOrigin(5, -3, 3072))
    }

    /** Detail sheets: 36 frames of 512 px in 6 columns = 3072 x 3072, one per elevation. */
    @Test
    fun detailSheetsSitNextToTheMainSheet() {
        assertEquals("/r/deck.turntable-36x3c12.hd2.png", TurntableSheet.detailPath("/r/deck.turntable-36x3c12.png", 2))
        assertNull(TurntableSheet.parse(TurntableSheet.detailPath("/r/deck.turntable-36x3c12.png", 0)), "a detail sheet is not shown inline")
        val d = default.detail()
        assertEquals(TurntableSheet(36, 1, 6), d)
        assertTrue(d.fits(3072, 3072))
        assertEquals(5 * 512 to 5 * 512, d.frameOrigin(35, 0, 3072))
        assertEquals(TurntableSheet(24, 1, 5), TurntableSheet(24, 3, 12).detail())
    }
}
