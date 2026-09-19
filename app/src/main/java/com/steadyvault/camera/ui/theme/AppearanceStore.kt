package com.steadyvault.camera.ui.theme

import android.content.Context
import android.graphics.Color
import kotlin.math.roundToInt

object AppearanceStore {
    private const val PREF = "steadyvault_appearance"
    private const val KEY_THEME = "theme"
    private const val KEY_FONT = "font"
    private const val KEY_CORNERS = "corners"
    private const val KEY_BORDERS = "borders"
    private const val KEY_SURFACE = "surface_style"
    private const val KEY_CONTRAST = "contrast"
    private const val KEY_DENSITY = "density"

    const val THEME_EMERALD = "emerald"
    const val THEME_OCEAN = "ocean"
    const val THEME_VIOLET = "violet"
    const val THEME_AMBER = "amber"
    const val THEME_ICE = "ice"
    const val THEME_AMOLED = "amoled"
    const val THEME_GRAPHITE = "graphite"
    const val THEME_CYAN = "cyan"
    const val THEME_ROSE = "rose"
    const val THEME_RUBY = "ruby"
    const val THEME_MIDNIGHT = "midnight"
    const val THEME_COPPER = "copper"

    const val FONT_SYSTEM = "system"
    const val FONT_MODERN = "modern"
    const val FONT_SERIF = "serif"
    const val FONT_MONO = "mono"

    const val CORNERS_COMPACT = "compact"
    const val CORNERS_BALANCED = "balanced"
    const val CORNERS_SOFT = "soft"

    const val BORDERS_NONE = "none"
    const val BORDERS_SUBTLE = "subtle"
    const val BORDERS_STRONG = "strong"

    const val SURFACE_THEME = "theme"
    const val SURFACE_NEUTRAL = "neutral"
    const val SURFACE_BLACK = "black"

    const val CONTRAST_STANDARD = "standard"
    const val CONTRAST_STRONG = "strong"
    const val CONTRAST_MAXIMUM = "maximum"

    const val DENSITY_COMPACT = "compact"
    const val DENSITY_COMFORTABLE = "comfortable"
    const val DENSITY_SPACIOUS = "spacious"

    data class Palette(
        val id: String,
        val label: String,
        val accent: Int,
        val border: Int,
        val surface: Int = Color.rgb(15, 20, 29),
        val surfaceHigh: Int = Color.rgb(23, 30, 42),
        val background: Int = Color.rgb(5, 7, 11)
    )

    val themeValues = listOf(
        THEME_EMERALD, THEME_OCEAN, THEME_VIOLET, THEME_AMBER, THEME_ICE, THEME_AMOLED,
        THEME_GRAPHITE, THEME_CYAN, THEME_ROSE, THEME_RUBY, THEME_MIDNIGHT, THEME_COPPER
    )
    val fontValues = listOf(FONT_SYSTEM, FONT_MODERN, FONT_SERIF, FONT_MONO)
    val cornerValues = listOf(CORNERS_COMPACT, CORNERS_BALANCED, CORNERS_SOFT)
    val borderValues = listOf(BORDERS_NONE, BORDERS_SUBTLE, BORDERS_STRONG)
    val surfaceValues = listOf(SURFACE_THEME, SURFACE_NEUTRAL, SURFACE_BLACK)
    val contrastValues = listOf(CONTRAST_STANDARD, CONTRAST_STRONG, CONTRAST_MAXIMUM)
    val densityValues = listOf(DENSITY_COMPACT, DENSITY_COMFORTABLE, DENSITY_SPACIOUS)

    fun theme(context: Context): String = value(context, KEY_THEME, THEME_EMERALD, themeValues)
    fun font(context: Context): String = value(context, KEY_FONT, FONT_MODERN, fontValues)
    fun corners(context: Context): String = value(context, KEY_CORNERS, CORNERS_SOFT, cornerValues)
    fun borders(context: Context): String = value(context, KEY_BORDERS, BORDERS_SUBTLE, borderValues)
    fun surfaceStyle(context: Context): String = value(context, KEY_SURFACE, SURFACE_THEME, surfaceValues)
    fun contrast(context: Context): String = value(context, KEY_CONTRAST, CONTRAST_STRONG, contrastValues)
    fun density(context: Context): String = value(context, KEY_DENSITY, DENSITY_COMFORTABLE, densityValues)

    fun setTheme(context: Context, value: String) = put(context, KEY_THEME, value.takeIf { it in themeValues } ?: THEME_EMERALD)
    fun setFont(context: Context, value: String) = put(context, KEY_FONT, value.takeIf { it in fontValues } ?: FONT_MODERN)
    fun setCorners(context: Context, value: String) = put(context, KEY_CORNERS, value.takeIf { it in cornerValues } ?: CORNERS_SOFT)
    fun setBorders(context: Context, value: String) = put(context, KEY_BORDERS, value.takeIf { it in borderValues } ?: BORDERS_SUBTLE)
    fun setSurfaceStyle(context: Context, value: String) = put(context, KEY_SURFACE, value.takeIf { it in surfaceValues } ?: SURFACE_THEME)
    fun setContrast(context: Context, value: String) = put(context, KEY_CONTRAST, value.takeIf { it in contrastValues } ?: CONTRAST_STRONG)
    fun setDensity(context: Context, value: String) = put(context, KEY_DENSITY, value.takeIf { it in densityValues } ?: DENSITY_COMFORTABLE)

    fun palette(context: Context): Palette {
        val themed = palette(theme(context))
        val surfaced = when (surfaceStyle(context)) {
            SURFACE_NEUTRAL -> themed.copy(surface = Color.rgb(17, 19, 23), surfaceHigh = Color.rgb(28, 31, 37), background = Color.rgb(6, 7, 9))
            SURFACE_BLACK -> themed.copy(surface = Color.rgb(5, 5, 5), surfaceHigh = Color.rgb(14, 14, 14), background = Color.BLACK)
            else -> themed
        }
        return when (contrast(context)) {
            CONTRAST_MAXIMUM -> surfaced.copy(
                accent = mix(surfaced.accent, Color.WHITE, 0.08f),
                border = mix(surfaced.border, Color.WHITE, 0.38f),
                surfaceHigh = mix(surfaced.surfaceHigh, Color.WHITE, 0.06f)
            )
            CONTRAST_STRONG -> surfaced.copy(
                border = mix(surfaced.border, Color.WHITE, 0.18f),
                surfaceHigh = mix(surfaced.surfaceHigh, Color.WHITE, 0.025f)
            )
            else -> surfaced
        }
    }

    fun palette(value: String): Palette = when (value) {
        THEME_OCEAN -> Palette(value, "Oceano", Color.rgb(100, 174, 255), Color.rgb(70, 124, 183), Color.rgb(12, 20, 30), Color.rgb(20, 32, 46), Color.rgb(4, 8, 13))
        THEME_VIOLET -> Palette(value, "Violeta", Color.rgb(199, 148, 255), Color.rgb(125, 91, 164), Color.rgb(21, 16, 30), Color.rgb(33, 25, 45), Color.rgb(8, 5, 11))
        THEME_AMBER -> Palette(value, "Âmbar", Color.rgb(255, 198, 90), Color.rgb(156, 116, 49), Color.rgb(27, 21, 13), Color.rgb(41, 31, 18), Color.rgb(10, 7, 4))
        THEME_ICE -> Palette(value, "Gelo", Color.rgb(204, 226, 245), Color.rgb(101, 133, 161), Color.rgb(15, 21, 27), Color.rgb(24, 33, 42), Color.rgb(5, 8, 11))
        THEME_AMOLED -> Palette(value, "AMOLED", Color.rgb(105, 223, 196), Color.rgb(66, 75, 81), Color.BLACK, Color.rgb(10, 10, 10), Color.BLACK)
        THEME_GRAPHITE -> Palette(value, "Grafite", Color.rgb(220, 226, 235), Color.rgb(101, 110, 124), Color.rgb(17, 19, 23), Color.rgb(29, 32, 38), Color.rgb(7, 8, 10))
        THEME_CYAN -> Palette(value, "Ciano", Color.rgb(83, 223, 255), Color.rgb(43, 125, 145), Color.rgb(10, 22, 27), Color.rgb(17, 35, 42), Color.rgb(3, 9, 12))
        THEME_ROSE -> Palette(value, "Rosa", Color.rgb(255, 135, 186), Color.rgb(150, 71, 107), Color.rgb(29, 15, 22), Color.rgb(45, 23, 34), Color.rgb(11, 5, 8))
        THEME_RUBY -> Palette(value, "Rubi", Color.rgb(255, 104, 112), Color.rgb(158, 60, 67), Color.rgb(29, 13, 15), Color.rgb(44, 20, 23), Color.rgb(11, 4, 5))
        THEME_MIDNIGHT -> Palette(value, "Meia-noite", Color.rgb(126, 158, 255), Color.rgb(73, 91, 154), Color.rgb(13, 16, 31), Color.rgb(22, 26, 48), Color.rgb(4, 5, 12))
        THEME_COPPER -> Palette(value, "Cobre", Color.rgb(240, 160, 102), Color.rgb(143, 93, 57), Color.rgb(28, 18, 12), Color.rgb(42, 27, 18), Color.rgb(10, 6, 4))
        else -> Palette(THEME_EMERALD, "Liquid Glass", Color.rgb(139, 231, 244), Color.rgb(84, 149, 162), Color.rgb(18, 39, 48), Color.rgb(26, 49, 59), Color.rgb(6, 17, 22))
    }

    fun themeLabel(value: String): String = palette(value).label
    fun themeSubtitle(value: String): String = when (value) {
        THEME_EMERALD -> "Azul-petróleo translúcido, inspirado em Liquid Glass."
        THEME_OCEAN -> "Azul limpo com contraste frio."
        THEME_VIOLET -> "Roxo profundo e destaque luminoso."
        THEME_AMBER -> "Dourado quente com superfícies escuras."
        THEME_ICE -> "Cinza azulado claro e discreto."
        THEME_AMOLED -> "Preto puro com destaque esmeralda."
        THEME_GRAPHITE -> "Neutro, sóbrio e com alto contraste."
        THEME_CYAN -> "Ciano intenso com aparência tecnológica."
        THEME_ROSE -> "Rosa equilibrado em fundo profundo."
        THEME_RUBY -> "Vermelho rubi forte sem afetar ações de perigo."
        THEME_MIDNIGHT -> "Azul noturno com superfícies profundas."
        THEME_COPPER -> "Cobre quente com contraste elegante."
        else -> "Tema escuro do SteadyVault."
    }

    fun fontLabel(value: String): String = when (value) {
        FONT_SYSTEM -> "Sistema"
        FONT_SERIF -> "Serifada"
        FONT_MONO -> "Monoespaçada"
        else -> "Moderna"
    }

    fun cornerLabel(value: String): String = when (value) {
        CORNERS_COMPACT -> "Discretos"
        CORNERS_SOFT -> "Liquid Glass"
        else -> "Equilibrados"
    }

    fun borderLabel(value: String): String = when (value) {
        BORDERS_NONE -> "Sem contorno"
        BORDERS_STRONG -> "Marcados"
        else -> "Sutis"
    }

    fun surfaceLabel(value: String): String = when (value) {
        SURFACE_NEUTRAL -> "Neutro"
        SURFACE_BLACK -> "Preto puro"
        else -> "Vidro do tema"
    }

    fun contrastLabel(value: String): String = when (value) {
        CONTRAST_MAXIMUM -> "Máximo"
        CONTRAST_STRONG -> "Forte"
        else -> "Padrão"
    }

    fun densityLabel(value: String): String = when (value) {
        DENSITY_COMPACT -> "Compacta"
        DENSITY_SPACIOUS -> "Espaçosa"
        else -> "Confortável"
    }

    fun cornerRadiusDp(context: Context): Float = when (corners(context)) {
        CORNERS_COMPACT -> 16f
        CORNERS_SOFT -> 28f
        else -> 22f
    }

    fun borderWidthDp(context: Context): Float = when (borders(context)) {
        BORDERS_NONE -> 0f
        BORDERS_STRONG -> 2f
        else -> 1f
    }

    fun controlHeightDp(context: Context): Int = when (density(context)) {
        DENSITY_COMPACT -> 58
        DENSITY_SPACIOUS -> 76
        else -> 68
    }

    fun buttonHeightDp(context: Context): Int = when (density(context)) {
        DENSITY_COMPACT -> 48
        DENSITY_SPACIOUS -> 60
        else -> 54
    }

    fun controlVerticalPaddingDp(context: Context): Int = when (density(context)) {
        DENSITY_COMPACT -> 9
        DENSITY_SPACIOUS -> 15
        else -> 12
    }

    fun controlSpacingDp(context: Context): Int = when (density(context)) {
        DENSITY_COMPACT -> 7
        DENSITY_SPACIOUS -> 13
        else -> 10
    }

    fun summary(context: Context): String = listOf(
        themeLabel(theme(context)),
        surfaceLabel(surfaceStyle(context)),
        contrastLabel(contrast(context)),
        fontLabel(font(context)),
        cornerLabel(corners(context)),
        borderLabel(borders(context)),
        densityLabel(density(context))
    ).joinToString(" • ")

    private fun value(context: Context, key: String, fallback: String, allowed: List<String>): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, fallback).orEmpty().takeIf { it in allowed } ?: fallback

    private fun put(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun mix(first: Int, second: Int, secondWeight: Float): Int {
        val weight = secondWeight.coerceIn(0f, 1f)
        val firstWeight = 1f - weight
        return Color.rgb(
            (Color.red(first) * firstWeight + Color.red(second) * weight).roundToInt(),
            (Color.green(first) * firstWeight + Color.green(second) * weight).roundToInt(),
            (Color.blue(first) * firstWeight + Color.blue(second) * weight).roundToInt()
        )
    }
}
