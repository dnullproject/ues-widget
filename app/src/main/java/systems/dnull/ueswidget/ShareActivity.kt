package systems.dnull.ueswidget

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Transparent activity that receives Android Share intents and posts text or images to /api/inbox. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val streamUris = extractStreamUris(intent)
        val payload = resolveSharePayload(
            action = intent?.action,
            mimeType = intent?.type,
            subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            text = intent?.getStringExtra(Intent.EXTRA_TEXT),
            streamUris = streamUris.map { it.toString() },
        )

        if (payload == null) {
            Toast.makeText(this, "Нічого надсилати", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val err = try {
                val baseUrl = Settings.baseUrl(app)
                val apiKey = Settings.apiKey(app)
                when (payload) {
                    is SharePayload.Text -> postInboxText(baseUrl, apiKey, payload.text)
                    is SharePayload.Images -> {
                        for (uriStr in payload.uris) {
                            val jpegBytes = decodeAndDownscaleJpeg(Uri.parse(uriStr))
                            postInboxImage(baseUrl, apiKey, payload.text, jpegBytes)
                        }
                    }
                }
                refreshAll(app)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }

            runOnUiThread {
                if (err == null) {
                    Toast.makeText(app, "Додано у вхідні", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(app, "Помилка: $err", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    private fun extractStreamUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris.orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun decodeAndDownscaleJpeg(uri: Uri): ByteArray {
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не вдалося прочитати зображення")
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: error("Непідтримуваний формат зображення")
        val (targetW, targetH) = scaledDimensions(decoded.width, decoded.height, 2048)
        val bitmap = if (targetW != decoded.width || targetH != decoded.height) {
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
