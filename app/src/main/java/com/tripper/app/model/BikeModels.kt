package com.tripper.app.model

import androidx.compose.ui.graphics.Color

data class BikeColorOption(
    val name: String,
    val color: Color,
    val paintHex: String, // approximate hex for the paint swatch
)

data class BikeModel(
    val name: String,
    val colors: List<BikeColorOption>,
)

fun Color.Companion.fromHex(hex: String): Color {
    val clean = hex.removePrefix("#")
    val longHex = when (clean.length) {
        6 -> "FF$clean"
        8 -> clean
        else -> "FF000000"
    }
    return Color(longHex.toLong(16))
}

val royalEnfieldModels = listOf(
    BikeModel("Hunter 350", listOf(
        BikeColorOption("Dapper Grey", Color(0xFF6B6B6B), "#6B6B6B"),
        BikeColorOption("Dapper White", Color(0xFFE8E8E8), "#E8E8E8"),
        BikeColorOption("Dapper Black", Color(0xFF1A1A1A), "#1A1A1A"),
        BikeColorOption("Rebel Black", Color(0xFF0D0D0D), "#0D0D0D"),
        BikeColorOption("Rebel Red", Color(0xFF8B0000), "#8B0000"),
        BikeColorOption("Rebel Blue", Color(0xFF1B3A6B), "#1B3A6B"),
        BikeColorOption("Rebel Green", Color(0xFF2D5A27), "#2D5A27"),
        BikeColorOption("Metro Yellow", Color(0xFFE8B800), "#E8B800"),
    )),
    BikeModel("Classic 350", listOf(
        BikeColorOption("Halcyon Black", Color(0xFF1A1A1A), "#1A1A1A"),
        BikeColorOption("Halcyon Green", Color(0xFF2D5A27), "#2D5A27"),
        BikeColorOption("Halcyon Grey", Color(0xFF7A7A7A), "#7A7A7A"),
        BikeColorOption("Dark Stealth Black", Color(0xFF0A0A0A), "#0A0A0A"),
        BikeColorOption("Commando Sand", Color(0xFFC4A882), "#C4A882"),
        BikeColorOption("Signals Marsh Grey", Color(0xFF6B7059), "#6B7059"),
        BikeColorOption("Signals Desert Fox", Color(0xFFB8860B), "#B8860B"),
    )),
    BikeModel("Bullet 350", listOf(
        BikeColorOption("Military Black", Color(0xFF1C1C1C), "#1C1C1C"),
        BikeColorOption("Military Silver", Color(0xFFC0C0C0), "#C0C0C0"),
        BikeColorOption("Standard Black", Color(0xFF000000), "#000000"),
        BikeColorOption("Standard Red", Color(0xFF8B1A1A), "#8B1A1A"),
    )),
    BikeModel("Meteor 350", listOf(
        BikeColorOption("Fireball Red", Color(0xFFCC0000), "#CC0000"),
        BikeColorOption("Fireball Blue", Color(0xFF1A4B8C), "#1A4B8C"),
        BikeColorOption("Fireball Green", Color(0xFF2D6B3E), "#2D6B3E"),
        BikeColorOption("Supernova Blue", Color(0xFF1A3A6A), "#1A3A6A"),
        BikeColorOption("Supernova Red", Color(0xFF8B1A1A), "#8B1A1A"),
        BikeColorOption("Supernova Brown", Color(0xFF6B4423), "#6B4423"),
        BikeColorOption("Astral Blue", Color(0xFF4A7BB5), "#4A7BB5"),
        BikeColorOption("Astral Green", Color(0xFF3D7A4A), "#3D7A4A"),
        BikeColorOption("Astral Black", Color(0xFF1A1A1A), "#1A1A1A"),
    )),
    BikeModel("Super Meteor 650", listOf(
        BikeColorOption("Astral Black", Color(0xFF1A1A1A), "#1A1A1A"),
        BikeColorOption("Astral Blue", Color(0xFF2A5A8A), "#2A5A8A"),
        BikeColorOption("Astral Green", Color(0xFF3A7A4A), "#3A7A4A"),
        BikeColorOption("Celestial Red", Color(0xFFB22222), "#B22222"),
        BikeColorOption("Celestial Blue", Color(0xFF2A4A7A), "#2A4A7A"),
    )),
    BikeModel("Interceptor 650", listOf(
        BikeColorOption("Barcelona Blue", Color(0xFF1A3A6A), "#1A3A6A"),
        BikeColorOption("Canyon Red", Color(0xFF8B2500), "#8B2500"),
        BikeColorOption("Orange Crush", Color(0xFFE87200), "#E87200"),
        BikeColorOption("Mark 2 Orange", Color(0xFFCC6600), "#CC6600"),
        BikeColorOption("Chrome Red", Color(0xFFB22222), "#B22222"),
        BikeColorOption("Chrome Blue", Color(0xFF1A4B8C), "#1A4B8C"),
        BikeColorOption("Black", Color(0xFF1A1A1A), "#1A1A1A"),
    )),
    BikeModel("Continental GT 650", listOf(
        BikeColorOption("British Racing Green", Color(0xFF004225), "#004225"),
        BikeColorOption("Slipstream Blue", Color(0xFF4169E1), "#4169E1"),
        BikeColorOption("Apex Grey", Color(0xFF6A6A6A), "#6A6A6A"),
        BikeColorOption("Mark 2 Orange", Color(0xFFCC6600), "#CC6600"),
        BikeColorOption("Chrome Red", Color(0xFFB22222), "#B22222"),
        BikeColorOption("Chrome Blue", Color(0xFF1A4B8C), "#1A4B8C"),
        BikeColorOption("Black", Color(0xFF1A1A1A), "#1A1A1A"),
    )),
    BikeModel("Himalayan 450", listOf(
        BikeColorOption("Kaza Brown", Color(0xFF6B4423), "#6B4423"),
        BikeColorOption("Slate Poppy Blue", Color(0xFF4A6B8A), "#4A6B8A"),
        BikeColorOption("Slate Himalayan Salt", Color(0xFFD4D4D4), "#D4D4D4"),
        BikeColorOption("Camo Green", Color(0xFF4A5A3A), "#4A5A3A"),
        BikeColorOption("Hanle Black", Color(0xFF1A1A1A), "#1A1A1A"),
    )),
    BikeModel("Shotgun 650", listOf(
        BikeColorOption("Stencil White", Color(0xFFF0F0F0), "#F0F0F0"),
        BikeColorOption("Plasma Red", Color(0xFFCC2222), "#CC2222"),
        BikeColorOption("Stencil Black", Color(0xFF1A1A1A), "#1A1A1A"),
        BikeColorOption("Sheet Metal Grey", Color(0xFF808080), "#808080"),
        BikeColorOption("Green Drill", Color(0xFF2D6B3E), "#2D6B3E"),
    )),
    BikeModel("Guerrilla 450", listOf(
        BikeColorOption("Smoke Silver", Color(0xFFC0C0C0), "#C0C0C0"),
        BikeColorOption("Blazing Black", Color(0xFF0A0A0A), "#0A0A0A"),
        BikeColorOption("Shutter Green", Color(0xFF3A6B3A), "#3A6B3A"),
        BikeColorOption("Fireball Orange", Color(0xFFE87200), "#E87200"),
        BikeColorOption("Golden Chrome", Color(0xFFD4A017), "#D4A017"),
    )),
    BikeModel("Goan 350", listOf(
        BikeColorOption("Party Orange", Color(0xFFFF6600), "#FF6600"),
        BikeColorOption("Party Green", Color(0xFF00AA44), "#00AA44"),
        BikeColorOption("Party White", Color(0xFFFFFFFF), "#FFFFFF"),
        BikeColorOption("Party Purple", Color(0xFF6A0DAD), "#6A0DAD"),
    )),
    BikeModel("Scram 440", listOf(
        BikeColorOption("Matt Green", Color(0xFF3A5A3A), "#3A5A3A"),
        BikeColorOption("Matt Red", Color(0xFF8B2500), "#8B2500"),
        BikeColorOption("Matt Grey", Color(0xFF7A7A7A), "#7A7A7A"),
        BikeColorOption("Blazing Black", Color(0xFF0A0A0A), "#0A0A0A"),
    )),
)
