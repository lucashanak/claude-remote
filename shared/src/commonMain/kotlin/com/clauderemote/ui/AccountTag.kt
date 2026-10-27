package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeAccount
import com.clauderemote.model.ClaudeSession
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/** What a session row shows for the account it runs under: two glyphs and a fill. */
data class AccountTag(val label: String, val color: Color?)

/** Key into the tag map — a null slug IS the default `~/.claude` login. */
internal val ClaudeSession.accountKey: String
    get() = accountSlug?.ifBlank { null } ?: ClaudeAccount.DEFAULT_SLUG

/**
 * Tags for every account in play, keyed by slug. Empty while only ONE account
 * is involved: then every row would carry the same chip, which says nothing.
 *
 * [accounts] goes first in the slug list so the colours match the top bar's
 * chip, which assigns over that list alone; slugs only seen on sessions (a
 * session on another server, or before the probe returns) are appended after.
 */
internal fun buildAccountTags(
    accounts: List<ClaudeAccount>,
    sessions: List<ClaudeSession>,
    colorsFor: (List<String>) -> Map<String, Color>,
): Map<String, AccountTag> {
    val slugs = (accounts.map { it.slug } + sessions.map { it.accountKey }).distinct()
    if (slugs.size < 2) return emptyMap()
    val colors = colorsFor(slugs)
    val bySlug = accounts.associateBy { it.slug }
    return slugs.associateWith { slug ->
        AccountTag(
            label = (bySlug[slug] ?: ClaudeAccount(slug = slug)).initials,
            color = colors[slug],
        )
    }
}

/** The filled two-letter chip — same look as the account tag in the top bar. */
@Composable
internal fun AccountTagChip(tag: AccountTag, modifier: Modifier = Modifier) {
    val c = CRTheme.colors
    val fill = tag.color ?: c.surface2
    Text(
        tag.label,
        style = CRType.pill,
        color = if (tag.color != null) inkOn(fill) else c.accent,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
