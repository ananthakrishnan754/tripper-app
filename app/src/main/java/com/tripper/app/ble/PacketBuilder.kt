package com.tripper.app.ble

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

object PacketBuilder {

    const val CMD_NAV_DATA: Byte = 0x10
    const val CMD_SHOW_PIN: Byte = 0x21
    const val CMD_STATE_CHANGE: Byte = 0x40
    const val CMD_TIME_SYNC: Byte = 0x50

    // Turn icon IDs (from igj enum x3 values)
    object Icons {
        const val UNKNOWN = -1
        const val DEPART = 60
        const val DESTINATION = 0
        const val DESTINATION_LEFT = 1
        const val DESTINATION_RIGHT = 2
        const val STRAIGHT = 9
        const val TURN_LEFT = 20
        const val TURN_RIGHT = 21
        const val KEEP_LEFT = 24
        const val KEEP_RIGHT = 25
        const val SLIGHT_LEFT = 24
        const val SLIGHT_RIGHT = 25
        const val SHARP_LEFT = 22
        const val SHARP_RIGHT = 23
        const val U_TURN_CW = 26
        const val U_TURN_CCW = 61
        const val MERGE = 27
        const val MERGE_LEFT = 4
        const val MERGE_RIGHT = 3
        const val FORK_LEFT = 6
        const val FORK_RIGHT = 5
        const val ON_RAMP_LEFT = 30
        const val ON_RAMP_RIGHT = 29
        const val OFF_RAMP_LEFT = 8
        const val OFF_RAMP_RIGHT = 7
        const val ROUNDABOUT = 10
        const val ROUNDABOUT_CCW = 49
        const val FERRY_BOAT = 62
        const val FERRY_TRAIN = 63
        const val NAME_CHANGE = 9
        const val LOW_BATTERY = 68
        const val MOBILE_DATA = 66
        const val RE_ROUTE = 28
        const val KEEP_LEFT_SIGN = 43
        const val KEEP_RIGHT_SIGN = 44
    }

    // Distance unit codes
    const val UNIT_METERS = 1
    const val UNIT_KM_TENTHS = 2
    const val UNIT_MILES_TENTHS = 3
    const val UNIT_FEET = 4

    private fun crc16(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (b in data) {
            for (i in 0 until 8) {
                val bit = (b.toInt() shr (7 - i)) and 1
                val c15 = (crc shr 15) and 1
                crc = crc shl 1
                if (bit xor c15 == 1) {
                    crc = crc xor 0x1021
                }
            }
        }
        crc = crc and 0xFFFF
        val buf = ByteBuffer.allocate(4)
        buf.putInt(crc)
        val arr = buf.array()
        return byteArrayOf(arr[2], arr[3])
    }

    private fun packShort(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(4)
        buf.putInt(value)
        val arr = buf.array()
        return byteArrayOf(arr[2], arr[3])
    }

    fun buildNavData(
        primaryIcon: Int,
        primaryDistance: Int,
        secondaryIcon: Int,
        secondaryDistance: Int,
        secondaryType: Int = 0,
        horizon: Int = 0,
        totalDistance: Int = 0,
        etaHours: Int = 0,
        etaMinutes: Int = 0,
        etaSeconds: Int = 0,
        etaFormat: Int = 0,
        mode: Int = 0 // 0=riding, 1=walking
    ): ByteArray {
        val packet = ByteArray(20)

        packet[0] = CMD_NAV_DATA
        packet[1] = 0x11 // upper=1, lower=1
        packet[2] = (primaryIcon and 0xFF).toByte()

        val pd = packShort(primaryDistance)
        packet[3] = pd[0]
        packet[4] = pd[1]

        packet[5] = (secondaryType and 0xFF).toByte()

        val horizonNibble = (horizon and 0x0F) shl 4
        val modeNibble = (mode and 0x0F)
        packet[6] = ((horizonNibble or modeNibble) and 0xFF).toByte()

        packet[7] = (secondaryIcon and 0xFF).toByte()

        val sd = packShort(secondaryDistance)
        packet[8] = sd[0]
        packet[9] = sd[1]

        // secondary distance units in upper nibble, 0 in lower
        packet[10] = 0

        if (etaFormat == 0) {
            val hh = (etaHours and 0x03) shl 6
            val mm = (etaMinutes and 0x3F)
            packet[11] = ((hh or mm) and 0xFF).toByte()
            packet[12] = (etaSeconds and 0xFF).toByte()
        } else {
            val td = packShort(totalDistance)
            packet[11] = td[0]
            packet[12] = td[1]
        }

        packet[13] = (etaFormat and 0xFF).toByte()

        val crc = crc16(packet.copyOfRange(0, 18))
        packet[18] = crc[0]
        packet[19] = crc[1]

        return packet
    }

    fun buildStateChange(state: Int): ByteArray {
        val packet = ByteArray(20)
        packet[0] = CMD_STATE_CHANGE
        packet[1] = (state and 0xFF).toByte()
        val crc = crc16(packet.copyOfRange(0, 18))
        packet[18] = crc[0]
        packet[19] = crc[1]
        return packet
    }

    fun buildTimeSync(
        hours: Int,
        minutes: Int,
        is24HourFormat: Boolean = true,
        isAm: Boolean = true
    ): ByteArray {
        val packet = ByteArray(20)
        packet[0] = CMD_TIME_SYNC

        val format: Int
        var h = hours
        if (is24HourFormat) {
            format = 0
        } else {
            if (h == 0) { h = 12; format = 1 }
            else if (h >= 12) {
                if (h != 12) h -= 12
                format = 2
            } else {
                format = 1
            }
        }

        packet[1] = (((format and 0x03) shl 6) or (h and 0x3F)).toByte()
        packet[2] = (minutes and 0xFF).toByte()

        val crc = crc16(packet.copyOfRange(0, 18))
        packet[18] = crc[0]
        packet[19] = crc[1]
        return packet
    }

    fun buildShowPin(paired: Boolean): ByteArray {
        val packet = ByteArray(20)
        packet[0] = CMD_SHOW_PIN
        packet[1] = if (paired) 0 else 1
        val crc = crc16(packet.copyOfRange(0, 18))
        packet[18] = crc[0]
        packet[19] = crc[1]
        return packet
    }

    fun buildOffRoute(mode: Int = 0): ByteArray {
        return buildNavData(
            primaryIcon = Icons.MOBILE_DATA,
            primaryDistance = 0,
            secondaryIcon = -1,
            secondaryDistance = 0,
            secondaryType = 1,
            horizon = 0,
            mode = mode
        )
    }
}
