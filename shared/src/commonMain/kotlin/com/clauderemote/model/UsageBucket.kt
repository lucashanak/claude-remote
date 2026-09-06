package com.clauderemote.model

/**
 * One rate-limit window as Anthropic's OAuth usage endpoint reports it.
 *
 * Kept KEY-AGNOSTIC on purpose. Measured payload (default team seat,
 * 2026-09-06) — top-level keys were:
 *
 *   five_hour, seven_day, seven_day_oauth_apps, seven_day_opus,
 *   seven_day_sonnet, seven_day_cowork, seven_day_omelette, tangelo,
 *   iguana_necktie, omelette_promotional, nimbus_quill, cinder_cove,
 *   copper_kite, amber_ladder, juniper_tide, extra_usage, limits, spend,
 *   member_dashboard_available
 *
 * …of which only `five_hour`, `seven_day` and `nimbus_quill` actually carried a
 * `utilization` number; the `seven_day_<model>` keys were present but empty on
 * that plan. So the per-model caps come and go, several are codenames rather
 * than model names, and hardcoding "the third bucket" would have shown either
 * nothing or the wrong thing. Reporting every `{utilization, resets_at}` object
 * the payload contains means a cap appears as soon as the account has one, and
 * [label] falls back to the key itself so it is at least identifiable.
 */
data class UsageBucket(
    /** Raw API key, e.g. `five_hour`, `seven_day`, `seven_day_fable`. */
    val key: String,
    /** Utilization percent, 0..100. */
    val percent: Int,
    /** Minutes until this window resets; null when the payload carried no `resets_at`. */
    val resetMin: Int? = null,
) {
    /** Short human label for a chip or a bar caption. */
    val label: String
        get() = KNOWN_LABELS[key] ?: run {
            key
                .removePrefix("seven_day_")
                .removePrefix("five_hour_")
                .replace('_', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
                .ifBlank { key }
        }

    /**
     * True for the per-model / per-feature caps — everything that is neither the
     * rolling 5h window nor the overall weekly one. Lets the UI order the
     * well-known pair first and append the rest, and lets the log name the raw
     * keys so a renamed cap can be spotted in the field.
     */
    val isModelCap: Boolean get() = key != KEY_FIVE_HOUR && key != KEY_SEVEN_DAY

    companion object {
        const val KEY_FIVE_HOUR = "five_hour"
        const val KEY_SEVEN_DAY = "seven_day"

        /**
         * Nicer casing for the keys we have actually seen. Everything else falls
         * through to the generic transform, which is what keeps a newly
         * introduced cap readable without a code change.
         */
        private val KNOWN_LABELS = mapOf(
            KEY_FIVE_HOUR to "5h",
            KEY_SEVEN_DAY to "Week",
            "seven_day_fable" to "Fable (7d)",
            "seven_day_opus" to "Opus (7d)",
            "seven_day_sonnet" to "Sonnet (7d)",
            "seven_day_haiku" to "Haiku (7d)",
            "seven_day_cowork" to "Cowork (7d)",
            "seven_day_oauth_apps" to "OAuth apps (7d)",
        )

        /** 5h first, week second, model caps after, each group alphabetical. */
        fun order(buckets: List<UsageBucket>): List<UsageBucket> =
            buckets.sortedWith(
                compareBy(
                    { if (it.key == KEY_FIVE_HOUR) 0 else if (it.key == KEY_SEVEN_DAY) 1 else 2 },
                    { it.key },
                )
            )
    }
}

/**
 * One row of the all-accounts usage page: a login, and which server it lives on.
 *
 * The numbers are NOT carried here — they are read live out of
 * `UsageService.usageBuckets` by usage key, so a poll landing while the page is
 * open updates it without rebuilding the list.
 */
data class AccountUsage(
    val serverId: String,
    val serverName: String,
    val account: ClaudeAccount,
)
