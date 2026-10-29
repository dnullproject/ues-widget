package systems.dnull.ueswidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        col.addView(TextView(this).apply { text = "UES Widget"; textSize = 22f; setPadding(0, 0, 0, pad) })
        // Hints vanish once a field is filled, so each field gets a visible label.
        fun field(hint: String, value: String, type: Int): EditText {
            col.addView(TextView(this).apply { text = hint; setPadding(0, pad / 2, 0, 0) })
            return EditText(this).apply {
                this.hint = hint; setText(value); inputType = type; setSingleLine()
            }.also { col.addView(it) }
        }

        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val base = field("Memory API base URL", Settings.baseUrl(this), uri)
        val key = field("API key", Settings.apiKey(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val dash = field("Dashboard URL", Settings.dashboardUrl(this), uri)
        col.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                Settings.save(context, base.text.toString().trim(), key.text.toString().trim(), dash.text.toString().trim())
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, UesWidgetReceiver::class.java))
                sendBroadcast(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE, null, context, UesWidgetReceiver::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                )
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
        })
        // targetSdk 35 forces edge-to-edge; pad for system bars so nothing hides under them.
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
    }
}
