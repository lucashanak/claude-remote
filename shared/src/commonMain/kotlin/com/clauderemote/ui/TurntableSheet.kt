package com.clauderemote.ui

/**
 * A turntable: a model rendered from every angle into one PNG sprite sheet,
 * which the chat turns by dragging. Produced on the server by
 * `~/3D/.claude/skills/parametric-3d-printing/turntable.py`, whose docstring
 * carries the same contract — change both together.
 *
 * The shape lives in the file name, `<stem>.turntable-<A>x<E>c<C>.png`:
 * A azimuth frames per revolution, E elevation levels (low to high), C columns
 * in the sheet. Frames are square, index `e * A + a`, row-major in C columns.
 *
 * Why a sheet and not a 3D viewer: neither platform has GPU 3D in the app
 * without its own OpenGL stack, while a sheet is just an image — it reuses the
 * SSH fetch, the size caps and the cache, and it renders server-side with the
 * same lighting as the existing previews.
 */
internal data class TurntableSheet(val frames: Int, val elevations: Int, val columns: Int) {

    val total: Int get() = frames * elevations
    val rows: Int get() = (total + columns - 1) / columns

    /** Side of one square frame in a sheet [sheetWidth] pixels wide. */
    fun frameSize(sheetWidth: Int): Int = sheetWidth / columns

    /**
     * True when a decoded sheet of this size really holds every frame on whole
     * pixels. A width that does not divide by the columns (a hand-made sheet,
     * or one decoded down by a factor its frame side does not divide) would
     * slice with a creeping offset and show slivers of the neighbours — such a
     * sheet is shown as a plain image instead.
     */
    fun fits(sheetWidth: Int, sheetHeight: Int): Boolean {
        val s = frameSize(sheetWidth)
        return s > 0 && sheetWidth % columns == 0 && sheetHeight >= rows * s
    }

    /** Top-left pixel of frame ([azimuth], [elevation]); both wrap / clamp. */
    fun frameOrigin(azimuth: Int, elevation: Int, sheetWidth: Int): Pair<Int, Int> {
        val a = azimuth.mod(frames)
        val e = elevation.coerceIn(0, elevations - 1)
        val index = e * frames + a
        val s = frameSize(sheetWidth)
        return (index % columns) * s to (index / columns) * s
    }

    companion object {
        private val NAME = Regex("""\.turntable-(\d{1,3})x(\d{1,2})c(\d{1,3})\.png$""", RegexOption.IGNORE_CASE)

        /** The sheet shape encoded in [path], or null if it is an ordinary image. */
        fun parse(path: String): TurntableSheet? {
            val m = NAME.find(path) ?: return null
            val (a, e, c) = m.destructured
            val sheet = TurntableSheet(a.toInt(), e.toInt(), c.toInt())
            // Nonsense shapes are shown as a plain image rather than sliced wrong.
            return sheet.takeIf { it.frames in 2..360 && it.elevations in 1..12 && it.columns in 1..64 }
        }
    }
}
