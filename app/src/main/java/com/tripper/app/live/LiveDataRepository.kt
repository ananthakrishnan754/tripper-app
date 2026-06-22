package com.tripper.app.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NavInfo(
    val turnIcon: String = "⬆",
    val turnLabel: String = "Straight",
    val distance: String = "",
    val eta: String = "",
    val totalDistance: String = "",
    val streetName: String = "",
    val iconId: Int = -1,
    val distanceMeters: Int = 0,
    val etaHours: Int = 0,
    val etaMinutes: Int = 0,
    val etaSeconds: Int = 0,
    val etaFormat: Int = 0,
)

data class MusicInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
)

data class CallInfo(
    val callerName: String = "",
    val callerNumber: String = "",
    val isRinging: Boolean = false,
)

object LiveDataRepository {
    private val _nav = MutableStateFlow(NavInfo())
    val nav: StateFlow<NavInfo> = _nav

    private val _music = MutableStateFlow(MusicInfo())
    val music: StateFlow<MusicInfo> = _music

    private val _call = MutableStateFlow(CallInfo())
    val call: StateFlow<CallInfo> = _call

    fun updateNav(info: NavInfo) { _nav.value = info }
    fun updateMusic(info: MusicInfo) { _music.value = info }
    fun updateCall(info: CallInfo) { _call.value = info }
}
