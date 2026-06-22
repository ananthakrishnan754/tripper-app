package com.tripper.app.live

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class MusicTracker : NotificationListenerService() {

    companion object {
        private const val TAG = "MusicTracker"
        private val MUSIC_PACKAGES = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.sonos.acr",
            "com.apple.android.music",
            "com.amazon.mp3",
            "com.jio.media.jiobeats",
            "com.gaana",
            "com.saavn.android",
            "com.wynk.music",
        )
        private val KNOWN_PLAY_ACTIONS = setOf(
            "android.intent.action.MEDIA_BUTTON",
            "android.media.action.MEDIA_PLAY",
            "android.media.action.MEDIA_PAUSE",
            "android.media.action.MEDIA_NEXT",
            "android.media.action.MEDIA_PREVIOUS",
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MUSIC_PACKAGES) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(NotificationCompat.EXTRA_TITLE) ?: ""
        val text = extras.getString(NotificationCompat.EXTRA_TEXT) ?: ""
        val subText = extras.getString(NotificationCompat.EXTRA_SUB_TEXT) ?: ""

        if (title.isBlank() && text.isBlank()) return

        Log.i(TAG, "Music: '$title' / '$text' / '$subText' from ${sbn.packageName}")

        val artist: String
        val song: String
        when {
            text.contains(" - ") -> {
                val parts = text.split(" - ", limit = 2)
                artist = parts[0].trim()
                song = parts[1].trim()
            }
            text.contains(" · ") -> {
                val parts = text.split(" · ", limit = 2)
                artist = parts[0].trim()
                song = title
            }
            else -> {
                song = title
                artist = text
            }
        }

        LiveDataRepository.updateMusic(
            MusicInfo(
                title = song.ifBlank { title },
                artist = artist.ifBlank { subText },
                isPlaying = true,
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in MUSIC_PACKAGES) {
            LiveDataRepository.updateMusic(MusicInfo())
        }
    }
}
