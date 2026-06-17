package com.tripper.app.nav

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class GoogleMapsListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MapsListener"
        private const val GOOGLE_MAPS_PKG = "com.google.android.apps.maps"

        fun iconToEmoji(iconId: Int): String = when (iconId) {
            com.tripper.app.ble.PacketBuilder.Icons.DEPART -> "🚩"
            com.tripper.app.ble.PacketBuilder.Icons.DESTINATION -> "🏁"
            com.tripper.app.ble.PacketBuilder.Icons.STRAIGHT -> "⬆"
            com.tripper.app.ble.PacketBuilder.Icons.TURN_LEFT -> "⬅"
            com.tripper.app.ble.PacketBuilder.Icons.TURN_RIGHT -> "➡"
            com.tripper.app.ble.PacketBuilder.Icons.SLIGHT_LEFT -> "↖"
            com.tripper.app.ble.PacketBuilder.Icons.SLIGHT_RIGHT -> "↗"
            com.tripper.app.ble.PacketBuilder.Icons.SHARP_LEFT -> "↙"
            com.tripper.app.ble.PacketBuilder.Icons.SHARP_RIGHT -> "↘"
            com.tripper.app.ble.PacketBuilder.Icons.KEEP_LEFT -> "↰"
            com.tripper.app.ble.PacketBuilder.Icons.KEEP_RIGHT -> "↱"
            com.tripper.app.ble.PacketBuilder.Icons.U_TURN_CW -> "↩"
            com.tripper.app.ble.PacketBuilder.Icons.U_TURN_CCW -> "↪"
            com.tripper.app.ble.PacketBuilder.Icons.ROUNDABOUT -> "🔄"
            com.tripper.app.ble.PacketBuilder.Icons.MERGE -> "🔀"
            com.tripper.app.ble.PacketBuilder.Icons.FERRY_BOAT -> "⛴"
            else -> "⬆"
        }
    }

    private var lastTurnText = ""
    private var lastPacketHash = 0

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != GOOGLE_MAPS_PKG) return

        val notification = sbn.notification
        val extras = notification.extras ?: return

        val title = extras.getString(NotificationCompat.EXTRA_TITLE, "")
        val text = extras.getString(NotificationCompat.EXTRA_TEXT, "")
        val subText = extras.getString(NotificationCompat.EXTRA_SUB_TEXT, "")

        if (title.isBlank()) return

        Log.d(TAG, "GMaps notification: title='$title' text='$text' sub='$subText'")
        onNavigationUpdate(title, text, subText)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != GOOGLE_MAPS_PKG) return
    }

    private fun onNavigationUpdate(title: String, text: String, subText: String) {
        if (title == lastTurnText) return

        val turn = TurnParser.parseTitle(title)
        val distance = TurnParser.parseDistance(text)
        val eta = TurnParser.parseEta(text, subText)

        lastTurnText = title

        // Publish to live preview
        com.tripper.app.live.LiveDataRepository.updateNav(
            com.tripper.app.live.NavInfo(
                turnIcon = iconToEmoji(turn.iconId),
                turnLabel = turn.label,
                distance = distance.raw,
                eta = if (eta.hours > 0 || eta.minutes > 0) "${eta.hours}:${"%02d".format(eta.minutes)}" else "",
                totalDistance = "",
                streetName = "",
            )
        )

        val packet = buildNavPacket(turn, distance, eta)
        val hash = packet.contentHashCode()
        if (hash == lastPacketHash) return
        lastPacketHash = hash

        val intent = android.content.Intent("com.tripper.app.SEND_NAV").apply {
            putExtra("packet", packet)
        }
        sendBroadcast(intent)
    }

    private fun buildNavPacket(
        turn: TurnParser.TurnInfo,
        distance: TurnParser.DistanceInfo,
        eta: TurnParser.EtaInfo
    ): ByteArray {
        return com.tripper.app.ble.PacketBuilder.buildNavData(
            primaryIcon = turn.iconId,
            primaryDistance = distance.meters,
            secondaryIcon = -1,
            secondaryDistance = 0,
            secondaryType = 0,
            horizon = 0,
            totalDistance = 0,
            etaHours = eta.hours,
            etaMinutes = eta.minutes,
            etaSeconds = eta.seconds,
            etaFormat = if (eta.hours == 0 && eta.minutes == 0 && eta.seconds == 0) 0 else 10,
            mode = 0
        )
    }
}
