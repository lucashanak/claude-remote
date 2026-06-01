package com.clauderemote.voice

/**
 * Converts assistant **markdown** into clean text for text-to-speech, so the
 * engine reads the words — not "hvězdička" / "zpětné lomítko" for `*`, `` ` ``,
 * `\`, `#`, `_` and friends. Pure and platform-agnostic; shared by
 * SpeakerButton, voice mode, and the settings test button.
 *
 * Intentionally lossy: fenced code blocks are dropped (reading code aloud is
 * noise), link/image syntax collapses to its visible text, and emphasis /
 * heading / list / table markers are stripped. Word content is preserved.
 */
fun speakableFromMarkdown(md: String): String {
    var t = md
    // Fenced code blocks → drop entirely.
    t = Regex("(?s)```.*?```").replace(t, " ")
    t = Regex("(?s)~~~.*?~~~").replace(t, " ")
    // Images ![alt](url) → alt; links [text](url) → text.
    t = Regex("!\\[([^\\]]*)]\\([^)]*\\)").replace(t) { it.groupValues[1] }
    t = Regex("\\[([^\\]]*)]\\([^)]*\\)").replace(t) { it.groupValues[1] }
    // Inline code backticks.
    t = t.replace("`", "")
    // Headings, blockquotes, list bullets, ordered-list markers (line starts).
    t = Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*").replace(t, "")
    t = Regex("(?m)^[ \\t]{0,3}>[ \\t]?").replace(t, "")
    t = Regex("(?m)^[ \\t]{0,3}[-*+][ \\t]+").replace(t, "")
    t = Regex("(?m)^[ \\t]{0,3}\\d+[.)][ \\t]+").replace(t, "")
    // Horizontal rules (---, ***, ___ on their own line).
    t = Regex("(?m)^[ \\t]{0,3}([-*_][ \\t]?){3,}$").replace(t, " ")
    // Emphasis / strikethrough markers anywhere.
    t = Regex("(\\*\\*|\\*|__|_|~~)").replace(t, "")
    // Table cell pipes and stray backslashes.
    t = t.replace("|", " ").replace("\\", "")
    // Tidy whitespace.
    t = Regex("[ \\t]+").replace(t, " ")
    t = Regex("\\n{3,}").replace(t, "\n\n")
    return t.trim()
}
