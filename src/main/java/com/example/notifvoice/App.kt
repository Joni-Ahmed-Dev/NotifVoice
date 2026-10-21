package com.example.notifvoice

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

const val DEFAULT_KEY = ""
const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
const val DEFAULT_VOICE = "Kore"

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var appsSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }

        val keyBox = EditText(this).apply {
            hint = "Gemini API Key"
            setSingleLine()
            setText(prefs.getString("key", DEFAULT_KEY))
        }
        val modelBox = EditText(this).apply {
            setSingleLine()
            setText(prefs.getString("model", DEFAULT_MODEL))
        }
        val voices = listOf(
            "Kore", "Puck", "Charon", "Zephyr", "Fenrir", "Leda", "Orus", "Aoede",
            "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
            "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
            "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
        )
        val voiceBox = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                voices
            )
            val cur = prefs.getString("voice", DEFAULT_VOICE)
            setSelection(voices.indexOf(cur).coerceAtLeast(0))
        }
        appsSummary = TextView(this).apply { textSize = 14f }

        val accessBtn = Button(this).apply {
            text = "১. নোটিফিকেশন অ্যাক্সেস চালু করুন"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val pickBtn = Button(this).apply {
            text = "২. অ্যাপ বেছে নিন"
            setOnClickListener { pickApps() }
        }
        val saveBtn = Button(this).apply {
            text = "৩. সেটিংস সেভ করুন"
            setOnClickListener {
                prefs.edit()
                    .putString("key", keyBox.text.toString().trim())
                    .putString("model", modelBox.text.toString().trim())
                    .putString("voice", voiceBox.selectedItem.toString())
                    .apply()
                Toast.makeText(this@MainActivity, "সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
        val testBtn = Button(this).apply {
            text = "৪. ভয়েস পরীক্ষা করুন"
            setOnClickListener {
                saveBtn.performClick()
                Thread {
                    val err = Speaker.speakGemini(
                        this@MainActivity,
                        "আসসালামু আলাইকুম। এটি একটি পরীক্ষামূলক বার্তা।"
                    )
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            err ?: "পরীক্ষা সফল",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
        }

        root.addView(label("Gemini API Key (বদলাতে চাইলে ঘরের লেখা মুছে নতুন key বসিয়ে \"সেটিংস সেভ করুন\" চাপুন)"))
        root.addView(keyBox)
        root.addView(label("মডেল (সাধারণত বদলাতে হয় না)"))
        root.addView(modelBox)
        root.addView(label("ভয়েস বেছে নিন (বেছে সেভ করে \"ভয়েস পরীক্ষা করুন\" চাপলে শুনতে পাবেন)"))
        root.addView(voiceBox)
        root.addView(label("কোন অ্যাপের নোটিফিকেশন পড়া হবে"))
        root.addView(appsSummary)
        root.addView(accessBtn)
        root.addView(pickBtn)
        root.addView(saveBtn)
        root.addView(testBtn)

        setContentView(scroll)
        refreshSummary()
    }

    private fun refreshSummary() {
        val pm = packageManager
        val set = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        appsSummary.text = if (set.isEmpty()) {
            "কোনো অ্যাপ বাছা হয়নি"
        } else {
            set.joinToString(", ") { pkg ->
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
            }
        }
    }

    private fun pickApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val selected = (prefs.getStringSet("apps", emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(apps.size) { selected.contains(apps[it].first) }

        AlertDialog.Builder(this)
            .setTitle("অ্যাপ বেছে নিন")
            .setMultiChoiceItems(
                apps.map { it.second }.toTypedArray(), checked
            ) { _, i, isChecked ->
                if (isChecked) selected.add(apps[i].first) else selected.remove(apps[i].first)
            }
            .setPositiveButton("ঠিক আছে") { _, _ ->
                prefs.edit().putStringSet("apps", selected).apply()
                refreshSummary()
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }
}

object Speaker {

    private const val SAMPLE_RATE = 24000

    fun speakGemini(ctx: Context, text: String): String? {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val key = prefs.getString("key", DEFAULT_KEY).orEmpty().trim()
        val model = prefs.getString("model", DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        val voice = prefs.getString("voice", DEFAULT_VOICE).orEmpty().ifEmpty { DEFAULT_VOICE }
        if (key.isEmpty()) return "API key দেওয়া হয়নি"

        return try {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                        })
                    })
                })
            }

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(resp).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    resp.take(150)
                }
                return "ত্রুটি $code: $msg"
            }

            val b64 = JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getJSONObject("inlineData").getString("data")
            val pcm = Base64.decode(b64, Base64.DEFAULT)
            playPcm(pcm)
            null
        } catch (e: Exception) {
            "ত্রুটি: ${e.message}"
        }
    }

    private fun playPcm(pcm: ByteArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            track.write(pcm, 0, pcm.size)
            val totalFrames = pcm.size / 2
            val deadline = System.currentTimeMillis() + (totalFrames * 1000L / SAMPLE_RATE) + 3000
            while (track.playbackHeadPosition < totalFrames && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            track.stop()
        } finally {
            track.release()
        }
    }
}

class NotifVoiceService : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val apps = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        if (sbn.packageName !in apps) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val message = listOf(title, body).filter { it.isNotBlank() }.joinToString("। ")
        if (message.isBlank()) return

        executor.execute {
            val err = Speaker.speakGemini(this, message)
            if (err != null) fallbackSpeak(message)
        }
    }

    private fun fallbackSpeak(text: String) {
        val engine = tts
        if (engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "nv")
            return
        }
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("bn", "BD")
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nv")
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        executor.shutdown()
        super.onDestroy()
    }
}
