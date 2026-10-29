package systems.dnull.ueswidget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.glance.unit.ColorProvider

/** User-tunable look of the widget; defaults are the dashboard's Dracula theme. */
data class WidgetStyle(
    val bgHex: String = "#282A36",
    val bgOpacity: Int = 94,
    val fgHex: String = "#F8F8F2",
    val mutedHex: String = "#6272A4",
    val accentHex: String = "#BD93F9",
    val textScale: Int = 100,
    val corner: Int = 16,
    val showStatus: Boolean = true,
    val showFooter: Boolean = true,
    val showSp: Boolean = true,
) {
    val bg get() = ColorProvider(Color(withAlpha(parseHex(bgHex), bgOpacity)))
    val fg get() = ColorProvider(Color(parseHex(fgHex)))
    val muted get() = ColorProvider(Color(parseHex(mutedHex)))
    val accent get() = ColorProvider(Color(parseHex(accentHex)))
    fun sp(base: Int): TextUnit = (base * textScale / 100f).sp

    fun save(c: Context) {
        prefs(c).edit()
            .putString("bg", bgHex).putInt("bg_opacity", bgOpacity).putString("fg", fgHex)
            .putString("muted", mutedHex).putString("accent", accentHex).putInt("text_scale", textScale)
            .putInt("corner", corner).putBoolean("show_status", showStatus)
            .putBoolean("show_footer", showFooter).putBoolean("show_sp", showSp)
            .apply()
    }

    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("ues_widget_style", Context.MODE_PRIVATE)

        fun load(c: Context): WidgetStyle {
            val p = prefs(c)
            val d = WidgetStyle()
            return WidgetStyle(
                bgHex = p.getString("bg", null) ?: d.bgHex,
                bgOpacity = p.getInt("bg_opacity", d.bgOpacity),
                fgHex = p.getString("fg", null) ?: d.fgHex,
                mutedHex = p.getString("muted", null) ?: d.mutedHex,
                accentHex = p.getString("accent", null) ?: d.accentHex,
                textScale = p.getInt("text_scale", d.textScale),
                corner = p.getInt("corner", d.corner),
                showStatus = p.getBoolean("show_status", d.showStatus),
                showFooter = p.getBoolean("show_footer", d.showFooter),
                showSp = p.getBoolean("show_sp", d.showSp),
            )
        }
    }
}

/** "#RRGGBB" or "RRGGBB" -> opaque ARGB int; null if not a 6-digit hex colour. */
fun parseHexOrNull(s: String): Int? {
    val h = s.trim().removePrefix("#")
    if (h.length != 6 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return (0xFF000000 or h.toLong(16)).toInt()
}

/** Normalises user input to "#RRGGBB", or null when invalid. */
fun normalizeHex(s: String): String? =
    parseHexOrNull(s)?.let { "#%06X".format(it and 0xFFFFFF) }

private fun parseHex(s: String): Int = parseHexOrNull(s) ?: 0xFFFF00FF.toInt()

fun withAlpha(argb: Int, opacityPct: Int): Int =
    (argb and 0xFFFFFF) or ((opacityPct.coerceIn(0, 100) * 255 / 100) shl 24)
