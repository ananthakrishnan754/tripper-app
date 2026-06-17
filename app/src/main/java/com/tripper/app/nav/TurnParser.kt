package com.tripper.app.nav

import com.tripper.app.ble.PacketBuilder.Icons
import java.util.regex.Pattern

object TurnParser {

    data class TurnInfo(val iconId: Int, val label: String)
    data class DistanceInfo(val meters: Int, val raw: String)
    data class EtaInfo(val hours: Int, val minutes: Int, val seconds: Int)

    private val DISTANCE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(m|km|mi|ft|mile|miles|feet)", Pattern.CASE_INSENSITIVE)
    private val ETA_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})")

    fun parseTitle(title: String?): TurnInfo {
        val lower = (title ?: "").lowercase().trim()

        return when {
            lower.contains("uturn") || lower.contains("u-turn") || lower.contains("u turn") -> TurnInfo(Icons.U_TURN_CW, "u-turn")
            lower.contains("keep left") -> TurnInfo(Icons.KEEP_LEFT, "keep left")
            lower.contains("keep right") -> TurnInfo(Icons.KEEP_RIGHT, "keep right")
            lower.contains("sharp left") -> TurnInfo(Icons.SHARP_LEFT, "sharp left")
            lower.contains("sharp right") -> TurnInfo(Icons.SHARP_RIGHT, "sharp right")
            lower.contains("slight left") -> TurnInfo(Icons.SLIGHT_LEFT, "slight left")
            lower.contains("slight right") -> TurnInfo(Icons.SLIGHT_RIGHT, "slight right")
            lower.contains("turn left") -> TurnInfo(Icons.TURN_LEFT, "turn left")
            lower.contains("turn right") -> TurnInfo(Icons.TURN_RIGHT, "turn right")
            lower.contains("merge left") -> TurnInfo(Icons.MERGE_LEFT, "merge left")
            lower.contains("merge right") -> TurnInfo(Icons.MERGE_RIGHT, "merge right")
            lower.contains("fork left") -> TurnInfo(Icons.FORK_LEFT, "fork left")
            lower.contains("fork right") -> TurnInfo(Icons.FORK_RIGHT, "fork right")
            lower.contains("ramp left") || lower.contains("on ramp left") -> TurnInfo(Icons.ON_RAMP_LEFT, "ramp left")
            lower.contains("ramp right") || lower.contains("on ramp right") -> TurnInfo(Icons.ON_RAMP_RIGHT, "ramp right")
            lower.contains("arrive") || lower.contains("destination") -> TurnInfo(Icons.DESTINATION, "destination")
            lower.contains("depart") || lower.contains("start") -> TurnInfo(Icons.DEPART, "depart")
            lower.contains("ferry") -> TurnInfo(Icons.FERRY_BOAT, "ferry")
            lower.contains("roundabout") || lower.contains("circle") -> TurnInfo(Icons.ROUNDABOUT, "roundabout")
            lower.contains("straight") || lower.contains("continue") -> TurnInfo(Icons.STRAIGHT, "straight")
            lower.contains("exit left") || lower.contains("off ramp left") -> TurnInfo(Icons.OFF_RAMP_LEFT, "exit left")
            lower.contains("exit right") || lower.contains("off ramp right") -> TurnInfo(Icons.OFF_RAMP_RIGHT, "exit right")
            lower.contains("keep straight") -> TurnInfo(Icons.STRAIGHT, "straight")
            lower.contains("left") -> TurnInfo(Icons.TURN_LEFT, "left")
            lower.contains("right") -> TurnInfo(Icons.TURN_RIGHT, "right")
            else -> TurnInfo(Icons.STRAIGHT, "straight")
        }
    }

    fun parseDistance(text: String): DistanceInfo {
        val m = DISTANCE_PATTERN.matcher(text)
        if (m.find()) {
            val value = m.group(1)?.toDoubleOrNull() ?: 0.0
            val unit = (m.group(2) ?: "m").lowercase()
            val meters = when (unit) {
                "km" -> (value * 1000).toInt()
                "mi", "mile", "miles" -> (value * 1609.34).toInt()
                "ft", "feet" -> (value * 0.3048).toInt()
                else -> value.toInt()
            }
            return DistanceInfo(meters, text)
        }
        return DistanceInfo(0, text)
    }

    fun parseEta(text: String, subText: String): EtaInfo {
        val combined = "$text $subText"
        val m = ETA_PATTERN.matcher(combined)
        if (m.find()) {
            val h = m.group(1)?.toIntOrNull() ?: 0
            val min = m.group(2)?.toIntOrNull() ?: 0
            return EtaInfo(h, min, 0)
        }
        if (combined.contains("arrive", ignoreCase = true) || combined.contains("eta", ignoreCase = true)) {
            // Has ETA info but couldn't parse time
            return EtaInfo(0, 0, 0)
        }
        return EtaInfo(0, 0, 0)
    }
}
