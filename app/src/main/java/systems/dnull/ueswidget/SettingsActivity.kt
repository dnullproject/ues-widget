package systems.dnull.ueswidget

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : Activity() {
    private lateinit var col: LinearLayout
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        col.addView(TextView(this).apply { text = "UES Widget"; textSize = 22f; setPadding(0, 0, 0, pad) })

        section("Підключення")
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val base = field("Memory API base URL", Settings.baseUrl(this), uri)
        val key = field("API key", Settings.apiKey(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dash = field("Dashboard URL", Settings.dashboardUrl(this), uri)

        section("Вигляд")
        val s = WidgetStyle.load(this)
        val bg = colorField("Фон", s.bgHex)
        val opacity = slider("Непрозорість фону", s.bgOpacity, 0, 100, "%")
        val fg = colorField("Текст", s.fgHex)
        val muted = colorField("Другорядний текст", s.mutedHex)
        val accent = colorField("Акцент (заголовок)", s.accentHex)
        val scale = slider("Розмір тексту", s.textScale, 70, 160, "%")
        val corner = slider("Заокруглення кутів", s.corner, 0, 32, "dp")
        val showStatus = check("Подія / «день виграно»", s.showStatus)
        val showFooter = check("Нижній рядок: сон, кроки, звички, inbox", s.showFooter)
        val showSp = check("SP біля задач", s.showSp)
        val showSystem = check("Система: алерти, uptime, помилки, Win", s.showSystem)
        val showAgents = check("Агенти: квоти Claude/Gemini, воркери", s.showAgents)

        col.addView(Button(this).apply {
            text = "Зберегти"
            setOnClickListener {
                val hexes = listOf(bg, fg, muted, accent).map { normalizeHex(it.text.toString()) }
                if (hexes.any { it == null }) {
                    Toast.makeText(context, "Колір має бути у форматі #RRGGBB", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Settings.save(context, base.text.toString().trim(), key.text.toString().trim(), dash.text.toString().trim())
                WidgetStyle(
                    bgHex = hexes[0]!!, bgOpacity = opacity.value(), fgHex = hexes[1]!!, mutedHex = hexes[2]!!,
                    accentHex = hexes[3]!!, textScale = scale.value(), corner = corner.value(),
                    showStatus = showStatus.isChecked, showFooter = showFooter.isChecked, showSp = showSp.isChecked,
                    showSystem = showSystem.isChecked, showAgents = showAgents.isChecked,
                ).save(context)
                refreshWidgets()
                Toast.makeText(context, "Збережено", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(Button(this).apply {
            text = "Скинути вигляд до Dracula"
            setOnClickListener {
                WidgetStyle().save(context)
                refreshWidgets()
                recreate()
            }
        })
        // targetSdk 35 forces edge-to-edge; pad for system bars so nothing hides under them.
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
    }

    // A bare APPWIDGET_UPDATE broadcast only recomposes a live Glance session with its old data.
    private fun refreshWidgets() {
        val app = applicationContext
        CoroutineScope(Dispatchers.Default).launch { refreshAll(app) }
    }

    private fun section(title: String) {
        col.addView(TextView(this).apply { text = title; textSize = 18f; setPadding(0, pad, 0, pad / 4) })
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; setPadding(0, pad / 2, 0, 0) }

    // Hints vanish once a field is filled, so each field gets a visible label.
    private fun field(hint: String, value: String, type: Int): EditText {
        col.addView(label(hint))
        return EditText(this).apply { this.hint = hint; setText(value); inputType = type; setSingleLine() }
            .also { col.addView(it) }
    }

    /** Hex input with a swatch that follows what is typed. */
    private fun colorField(name: String, value: String): EditText {
        col.addView(label(name))
        val size = (32 * resources.displayMetrics.density).toInt()
        val swatch = View(this).apply { layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = pad / 2 } }
        fun paint(text: String) {
            swatch.background = GradientDrawable().apply {
                cornerRadius = size / 4f
                setColor(parseHexOrNull(text) ?: 0)
                setStroke(2, 0xFF6272A4.toInt())
            }
        }
        val edit = EditText(this).apply {
            setText(value); hint = "#RRGGBB"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) = paint(e.toString())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        paint(value)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(swatch); addView(edit)
        })
        return edit
    }

    private class Slider(val bar: SeekBar, val min: Int) {
        fun value() = bar.progress + min
    }

    private fun slider(name: String, value: Int, min: Int, max: Int, unit: String): Slider {
        val title = label("")
        fun show(v: Int) { title.text = "$name: $v$unit" }
        col.addView(title)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) = show(p + min)
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        show(value.coerceIn(min, max))
        col.addView(bar)
        return Slider(bar, min)
    }

    private fun check(name: String, value: Boolean) =
        CheckBox(this).apply { text = name; isChecked = value }.also { col.addView(it) }
}
