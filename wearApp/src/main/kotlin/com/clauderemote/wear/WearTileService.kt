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
 * app, exactly what Claude is blocked on: how many sessions need the user and
 * (up to three) which ones. This is the product thesis "co čeká na uživatele"
 * at its cheapest interaction cost; the app and the notifikace-first flow
 * ([WearNotifier]) are the tiers above it once the user decides to act.
 *
 * ProtoLayout (Java-builder API), NOT Compose — Tiles render in the system's
 * process from a serialized layout, so Compose is not available here.
 * [SessionRepository] is process-global and shared with the running app +
 * [WearDataListenerService], so the tile reads live data for free — except on
 * a cold process the system spun up JUST for this request, where the repo is
 * still empty (hence the "Otevřít appku" fallback).
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
        // Cold process woken only for this request → repo hasn't been synced
        // yet. Nothing to show; offer opening the app, which fetches on launch.
        if (sessions.isEmpty() && !SessionRepository.hasLoaded.value) {
            return centeredMessage("Otevřít Claude Remote")
        }

        // Needs-action = what the user is actually being waited on. APPROVAL
        // ranks above WAITING (it blocks Claude harder), then cap at 3 to fit
        // the watch face.
        val needsAction = sessions
            .filter { it.activity == "APPROVAL_NEEDED" || it.activity == "WAITING_FOR_INPUT" }
            .sortedBy { if (it.activity == "APPROVAL_NEEDED") 0 else 1 }
            .take(3)

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(headline(needsAction.size))

        if (needsAction.isEmpty()) {
            column.addContent(spacerHeight(8f))
            column.addContent(
                Text.Builder(this, "Nic nečeká")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(COLOR_MUTED))
                    .build(),
            )
        } else {
            for (session in needsAction) {
                column.addContent(spacerHeight(6f))
                column.addContent(sessionRow(session))
            }
        }

        // Whole-tile fallback click: tapping anywhere off a row opens the app
        // on the list (no specific session).
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(clickModifiers("open_list", launchAction(null)))
            .addContent(column.build())
            .build()
    }

    private fun headline(count: Int): LayoutElement {
        if (count == 0) {
            return Text.Builder(this, "Vše hotovo ✓")
                .setTypography(Typography.TYPOGRAPHY_TITLE2)
                .setColor(ColorBuilders.argb(COLOR_WAITING))
                .build()
        }
        return LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
            .addContent(
                Text.Builder(this, count.toString())
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY1)
                    .setColor(ColorBuilders.argb(COLOR_TEXT))
                    .build(),
            )
            .addContent(spacerWidth(4f))
            .addContent(
                Text.Builder(this, "čeká")
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(ColorBuilders.argb(COLOR_MUTED))
                    .build(),
            )
            .build()
    }

    private fun sessionRow(session: WearSessionInfo): LayoutElement {
        val approval = session.activity == "APPROVAL_NEEDED"
        // ▲ = approval (blocking yes/no), ✎ = waiting for typed input; colored
        // to match the notification urgency tiers (red vs. blue).
        val glyph = if (approval) "▲" else "✎"
        val glyphColor = if (approval) COLOR_APPROVAL else COLOR_WAITING

        return LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            // Per-row deep-link: same "session_id" extra WearNotifier uses, so
            // MainActivity routes straight to this session via NavRequest.
            .setModifiers(clickModifiers("open_${session.id}", launchAction(session.id)))
            .addContent(
                Text.Builder(this, glyph)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(ColorBuilders.argb(glyphColor))
                    .build(),
            )
            .addContent(spacerWidth(6f))
            .addContent(
                Text.Builder(this, session.title)
                    .setTypography(Typography.TYPOGRAPHY_BUTTON)
                    .setColor(ColorBuilders.argb(COLOR_TEXT))
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build(),
            )
            .build()
    }

    private fun launchAction(sessionId: String?): ActionBuilders.LaunchAction {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
        if (sessionId != null) {
            activity.addKeyToExtraMapping(
                WearNotifier.EXTRA_SESSION_ID,
                ActionBuilders.AndroidStringExtra.Builder().setValue(sessionId).build(),
            )
        }
        return ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(activity.build())
            .build()
    }

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
            .setModifiers(clickModifiers("open_list", launchAction(null)))
            .addContent(
                Text.Builder(this, text)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(ColorBuilders.argb(COLOR_TEXT))
                    .build(),
            )
            .build()

    private fun spacerHeight(dp: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()

    private fun spacerWidth(dp: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(dp)).build()

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
