package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.model.AccountUsage
import com.clauderemote.model.ClaudeAccount
import com.clauderemote.model.SshServer
import com.clauderemote.model.UsageBucket
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/**
 * Every login's rate-limit standing in one place: the 5h window, the weekly
 * limit and the per-model caps (Fable), per account, per server.
 *
 * Why a screen of its own rather than more chips: the chips can only ever show
 * the ACTIVE session's login, so the other seats — the whole point of running
 * several — were invisible. Deciding which account to start work under is
 * exactly the moment you want all of them side by side.
 *
 * Fetch policy: one shot per account on open and on explicit refresh, never a
 * poll loop. The usage endpoint's quota belongs to the account and is shared
 * with the OMC statusline, so putting every listed login on a timer would trip
 * 429s for the seat the user is actually working under (see
 * UsageService.fetchRateLimitsOnce). Between fetches this renders whatever the
 * running pollers have published.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountUsageScreen(
    servers: List<SshServer>,
    sessionOrchestrator: SessionOrchestrator,
    accountColorStorage: com.clauderemote.storage.AccountColorStorage,
    onBack: () -> Unit,
    /** Opens the accounts screen, where a lapsed login can be renewed. */
    onManageAccounts: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val buckets by sessionOrchestrator.usageBuckets.collectAsState()
    val fiveHourFallback by sessionOrchestrator.sessionUsagePercents.collectAsState()
    val weekFallback by sessionOrchestrator.weekUsagePercents.collectAsState()

    var rows by remember { mutableStateOf<List<AccountUsage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshedAt by remember { mutableStateOf<Long?>(null) }
    var partial by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey, servers.map { it.id }) {
        loading = true
        partial = false
        val collected = mutableListOf<AccountUsage>()
        var anyFetchFailed = false
        for (server in servers) {
            // listClaudeAccounts needs a live connection; a server the app isn't
            // connected to contributes nothing rather than blocking the page.
            val accounts = try {
                sessionOrchestrator.listClaudeAccounts(server.id)
            } catch (e: Exception) {
                emptyList()
            }
            if (accounts.isEmpty()) continue
            collected += accounts.map { AccountUsage(server.id, server.name, it) }
            // Publish the identities immediately, then fill numbers in as each
            // account answers — a slow seat must not hide the fast ones.
            rows = collected.toList()
            for (account in accounts) {
                val slug = if (account.isDefault) null else account.slug
                if (!sessionOrchestrator.refreshAccountUsage(server.id, slug)) anyFetchFailed = true
            }
        }
        rows = collected.toList()
        partial = anyFetchFailed
        refreshedAt = System.currentTimeMillis()
        loading = false
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    titleContentColor = c.text,
                    navigationIconContentColor = c.textDim,
                ),
                title = { Text("Usage podle účtů", style = CRType.cardTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět", tint = c.textDim)
                    }
                },
                actions = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = c.accent,
                        )
                    } else {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(Icons.Default.Refresh, "Obnovit", tint = c.textDim)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = m.sectionPad, vertical = m.sectionTopGap),
            verticalArrangement = Arrangement.spacedBy(m.cardGap),
        ) {
            if (rows.isEmpty() && !loading) {
                CRCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Žádné účty k zobrazení.", style = CRType.cardTitle, color = c.text)
                        Text(
                            "Účty se čtou přes živé připojení k serveru — připoj se a zkus obnovit.",
                            style = CRType.bodyDim, color = c.textDim,
                        )
                    }
                }
            }

            // Group by server so a multi-server setup stays readable; a single
            // server (the common case) just renders one flat list.
            rows.groupBy { it.serverId }.forEach { (serverId, serverRows) ->
                if (rows.map { it.serverId }.distinct().size > 1) {
                    Text(
                        serverRows.first().serverName.uppercase(),
                        style = CRType.sectionH,
                        color = c.textDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                serverRows.forEach { row ->
                    val key = sessionOrchestrator.usageKeyFor(
                        serverId,
                        if (row.account.isDefault) null else row.account.slug,
                    )
                    AccountUsageCard(
                        account = row.account,
                        accentColor = accountColorStorage.colorFor(row.account.slug),
                        buckets = buckets[key].orEmpty(),
                        fiveHourFallback = fiveHourFallback[key],
                        weekFallback = weekFallback[key],
                        onManageAccounts = onManageAccounts,
                    )
                }
            }

            if (rows.isNotEmpty()) {
                Text(
                    buildString {
                        append(refreshedAt?.let { "Aktualizováno " + clockTime(it) } ?: "Načítám…")
                        if (partial) {
                            // Say so out loud: a silently stale number here is
                            // worse than no number, because it drives a decision
                            // about which seat to burn.
                            append(" · některé účty se nepodařilo obnovit (limit endpointu nebo offline server) — " +
                                "zobrazené hodnoty mohou být starší")
                        }
                    },
                    style = CRType.bodyDim,
                    color = if (partial) c.working else c.textDim,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountUsageCard(
    account: ClaudeAccount,
    accentColor: com.clauderemote.ui.theme.CRAccent?,
    buckets: List<UsageBucket>,
    fiveHourFallback: Int?,
    weekFallback: Int?,
    onManageAccounts: (() -> Unit)?,
    nowMs: Long = System.currentTimeMillis(),
) {
    val c = CRTheme.colors
    val expiryDays = account.loginExpiresInDays(nowMs)
    // The endpoint is the better source; the chips' values (statusline scrape or
    // an earlier poll) stand in until it answers, so the card is never blank for
    // an account that has been used this session.
    val shown = if (buckets.isNotEmpty()) buckets else listOfNotNull(
        fiveHourFallback?.let { UsageBucket(UsageBucket.KEY_FIVE_HOUR, it) },
        weekFallback?.let { UsageBucket(UsageBucket.KEY_SEVEN_DAY, it) },
    )

    CRCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Same two-letter tag and colour the session chips use, so the
                // row is recognisable as "the seat that session runs under".
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background((accentColor?.color ?: c.accent).copy(alpha = 0.20f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        account.initials,
                        style = CRType.pill,
                        color = accentColor?.color ?: c.accent,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        account.label, style = CRType.cardTitle, color = c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (account.subtitle.isNotBlank()) {
                        Text(
                            account.subtitle, style = CRType.bodyDim, color = c.textDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (account.isDefault) {
                    Pill(text = "DEFAULT", background = c.tintAccent, foreground = c.accent)
                }
            }

            if (expiryDays != null && expiryDays <= ClaudeAccount.LOGIN_EXPIRY_WARN_DAYS) {
                val expired = expiryDays < 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            expired -> "Přihlášení vypršelo"
                            expiryDays == 0 -> "Přihlášení vyprší dnes"
                            expiryDays == 1 -> "Přihlášení vyprší za 1 den"
                            expiryDays in 2..4 -> "Přihlášení vyprší za $expiryDays dny"
                            else -> "Přihlášení vyprší za $expiryDays dní"
                        },
                        style = CRType.bodyDim,
                        color = if (expired) c.disconnected else c.working,
                        modifier = Modifier.weight(1f),
                    )
                    if (onManageAccounts != null) {
                        TextButton(onClick = onManageAccounts) {
                            Text("Obnovit", style = CRType.pill, color = c.accent)
                        }
                    }
                }
            }

            if (shown.isEmpty()) {
                Text(
                    "Bez dat — účet se ještě nepodařilo dotázat.",
                    style = CRType.bodyDim, color = c.textDim,
                )
            } else {
                shown.forEach { bucket ->
                    UsageBucketBar(bucket)
                }
            }
        }
    }
}

@Composable
private fun UsageBucketBar(bucket: UsageBucket) {
    val c = CRTheme.colors
    val pct = bucket.percent.coerceIn(0, 100)
    val barColor = when {
        pct < 50 -> c.ready
        pct < 80 -> c.working
        else -> c.disconnected
    }
    val shape = RoundedCornerShape(999.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(bucket.label, style = CRType.bodyDim, color = c.text)
            Text(
                buildString {
                    append("$pct %")
                    // null reset = the payload didn't say; 0 = resets now. Only
                    // the former is silent.
                    bucket.resetMin?.let { append(" · reset za ${formatResetMinutes(it)}") }
                },
                style = CRType.bodyDim,
                color = c.textDim,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(shape).background(c.surface2),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct / 100f)
                    .fillMaxHeight()
                    .background(barColor, shape),
            )
        }
    }
}

/** Local wall-clock `HH:mm` for the "refreshed at" line. */
private fun clockTime(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))

/** "45 min", "2 h 15 min", "3 d 4 h" — short enough for the end of a bar caption. */
internal fun formatResetMinutes(min: Int): String {
    if (min <= 0) return "chvilku"
    val days = min / (60 * 24)
    val hours = (min % (60 * 24)) / 60
    val mins = min % 60
    return when {
        days > 0 -> if (hours > 0) "$days d $hours h" else "$days d"
        hours > 0 -> if (mins > 0) "$hours h $mins min" else "$hours h"
        else -> "$mins min"
    }
}
