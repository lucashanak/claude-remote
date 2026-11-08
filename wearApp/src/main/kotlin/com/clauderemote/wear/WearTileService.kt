package com.clauderemote.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The glanceable tile — swipe from the watch face and see, WITHOUT opening the
 * app, what Jarvis has for Lukáš (2026-11-08: "na hodinky jen Jarvise"):
 * "Jarvis · N čeká" (his pending alerts, the same N as his merged
 * notification), his newest news in one sentence (an alert's text: Czech, no
 * paths, no ids), and how many dialogs wait for an Ano/Ne. A tap opens the
 * app on its Čeká page. No session rows any more.
 *
 * ProtoLayout (Java-builder API), NOT Compose — Tiles render in the system's
 * process from a serialized layout, so Compose is not available here.
 * [SessionRepository] is process-global and shared with the running app +
 * [WearDataListenerService], so the tile reads live data for free — except on
 * a cold process the system spun up JUST for this request, where the repo is
 * still empty (hence the "Otevřít appku" fallback; the news sentence comes from
 * [JarvisRecentStore], which is persisted).
 */
class WearTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val layout = buildLayout(SessionRepository.sessions.value)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Periodic system refresh so the count doesn't sit stale when the
            // app isn't running to push a requestUpdate(); the Data Layer push
            // path ([WearDataListenerService]) still refreshes it instantly.
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        // No bitmaps — the tile is text + glyphs only. Still mandatory to
        // implement, and the version must match setResourcesVersion above or
        // the system treats the resource set as invalid.
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
        )

    private fun buildLayout(sessions: List<WearSessionInfo>): LayoutElement {
        val jarvis = SessionRepository.jarvis.value
        val recent = JarvisRecentStore.load(this)
        // Cold process woken only for this request → repo hasn't been synced
        // yet. Nothing to show; offer opening the app, which fetches on launch.
        if (!SessionRepository.hasLoaded.value && recent.isEmpty()) {
            return centeredMessage("Otevřít Claude Remote")
        }
        val pending = jarvis?.pending ?: 0
        val asks = WaitingRules.askCount(sessions)

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Text.Builder(this, "Jarvis · $pending čeká")
                    .setTypography(Typography.TYPOGRAPHY_TITLE2)
                    .setColor(ColorBuilders.argb(if (jarvis?.alive == true) COLOR_WAITING else COLOR_MUTED))
                    .build(),
            )
            .addContent(spacerHeight(6f))
            .addContent(
                Text.Builder(this, recent.firstOrNull()?.text ?: "Klid, pane.")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(COLOR_TEXT))
                    .setMaxLines(3)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build(),
            )
        if (asks > 0) {
            column.addContent(spacerHeight(6f))
            column.addContent(
                Text.Builder(this, "▲ $asks ke schválení")
                    .setTypography(Typography.TYPOGRAPHY_BUTTON)
                    .setColor(ColorBuilders.argb(COLOR_APPROVAL))
                    .build(),
            )
        }

        // The whole tile opens the app on its Čeká page.
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(clickModifiers("open_app", launchAction()))
            .addContent(column.build())
            .build()
    }

    private fun launchAction(): ActionBuilders.LaunchAction =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build(),
            )
            .build()

    private fun clickModifiers(id: String, action: ActionBuilders.LaunchAction): ModifiersBuilders.Modifiers =
        ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId(id)
                    .setOnClick(action)
                    .build(),
            )
            .build()

    private fun centeredMessage(text: String): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(clickModifiers("open_app", launchAction()))
            .addContent(
                Text.Builder(this, text)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(ColorBuilders.argb(COLOR_TEXT))
                    .build(),
            )
            .build()

    private fun spacerHeight(dp: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()

    companion object {
        // No bitmap resources, so the version never has to change; still must
        // be non-empty and consistent between tile + resources responses.
        private const val RESOURCES_VERSION = "1"
        private const val FRESHNESS_MS = 60_000L
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFFB0B0B0.toInt()
        private const val COLOR_APPROVAL = 0xFFFF5C5C.toInt()
        private const val COLOR_WAITING = 0xFF4E9CFF.toInt()
    }
}
