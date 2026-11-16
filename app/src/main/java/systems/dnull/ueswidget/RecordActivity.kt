package systems.dnull.ueswidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Dialog opened from the widget's microphone: records at once, "Відправити" uploads the note to the inbox. */
class RecordActivity : Activity() {
    private lateinit var label: TextView
    private lateinit var send: Button
    private val ui = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var durationS = 0
    private var uploading = false

    private val tick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
            label.text = "● Запис… %02d:%02d".format(s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Потрібен Android 10+", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        label = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad) }
        send = Button(this).apply { text = "Відправити"; textSize = 20f; setOnClickListener { sendNote() } }
        val cancel = Button(this).apply { text = "Скасувати"; setOnClickListener { cancelNote() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label)
            addView(send)
            addView(cancel)
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        else {
            Toast.makeText(this, "Потрібен дозвіл на мікрофон", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("NewApi") // onCreate bails out below API 29
    private fun startRecording() {
        val out = File(cacheDir, "note-${System.currentTimeMillis()}.ogg")
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(24000)
            r.setMaxDuration(5 * 60_000)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) sendNote()
            }
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) {
            out.delete()
            Toast.makeText(this, "Помилка: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        file = out
        startedAt = SystemClock.elapsedRealtime()
        tick.run()
    }

    /** Stops the recorder if running; the file stays. */
    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        ui.removeCallbacks(tick)
        durationS = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        try { r.stop() } catch (e: RuntimeException) { /* stopped too soon; the upload reports an empty file */ }
        r.release()
    }

    private fun sendNote() {
        val f = file ?: return
        if (uploading) return
        uploading = true
        stopRecording()
        send.isEnabled = false
        label.text = "Надсилаю…"
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val err = try {
                uploadVoiceNote(Settings.baseUrl(app), Settings.apiKey(app), f, durationS)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (err == null) {
                f.delete()
                refreshAll(app)
            }
            runOnUiThread {
                uploading = false
                if (err == null) {
                    Toast.makeText(app, "Нотатку надіслано", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(app, "Помилка: $err", Toast.LENGTH_LONG).show()
                    label.text = "Не надіслано, спробуй ще"
                    send.isEnabled = true // the recording is kept; the same button retries
                }
            }
        }
    }

    private fun cancelNote() {
        if (uploading) return
        stopRecording()
        file?.delete()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = cancelNote()

    override fun onDestroy() {
        stopRecording()
        if (!uploading) file?.delete()
        super.onDestroy()
    }
}
