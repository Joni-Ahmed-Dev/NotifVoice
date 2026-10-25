package com.example.notifvoice

import android.app.Activity
import android.graphics.Color
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val DEFAULT_KEY = ""
const val DEFAULT_MODEL = "gemini-3.8-flash-lite-tts"
const val DEFAULT_VOICE = "Kore"
const val DEFAULT_EL_MODEL = "eleven_v3"
// শুধু Muse অ্যাপের নোটিফিকেশন: Muse from Meta, Android package
const val TARGET_PACKAGE = "com.facebook.aura"
const val LOG_SEP = "\n--------\n"

// g = Gemini, e = ElevenLabs, p = ফোনের ভয়েস। ক্রম মানে আগেরটা না চললে পরেরটা।
fun normProvider(p: String?): String = when (p) {
    null -> "g_e_p"
    "eleven" -> "e_g_p"
    "gemini" -> "g_p"
    "phone" -> "p"
    else -> p
}

/** নোটিফিকেশনের লেখা অ্যাপের ইনপুট বক্সে দেখানোর সেতু (অ্যাপ খোলা থাকলে সঙ্গে সঙ্গে দেখায়)। */
object InputBus {
    @Volatile
    var listener: ((String) -> Unit)? = null
}

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var logContainer: LinearLayout
    private lateinit var logCount: TextView
    private lateinit var testMsgBox: EditText

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

        val providerValues = listOf("g_e_p", "e_g_p", "g_p", "e_p", "g", "e", "p")
        val providerLabels = listOf(
            "Gemini → ElevenLabs → ফোনের ভয়েস",
            "ElevenLabs → Gemini → ফোনের ভয়েস",
            "Gemini → ফোনের ভয়েস",
            "ElevenLabs → ফোনের ভয়েস",
            "শুধু Gemini (ব্যর্থ হলে চুপ)",
            "শুধু ElevenLabs (ব্যর্থ হলে চুপ)",
            "শুধু ফোনের ভয়েস (অফলাইন)"
        )
        val providerBox = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                providerLabels
            )
            val cur = normProvider(prefs.getString("provider", null))
            setSelection(providerValues.indexOf(cur).coerceAtLeast(0))
        }

        val elKeyBox = EditText(this).apply {
            hint = "ElevenLabs API Key"
            setSingleLine()
            setText(prefs.getString("elKey", ""))
        }
        val elVoiceBox = EditText(this).apply {
            hint = "ElevenLabs Voice ID"
            setSingleLine()
            setText(prefs.getString("elVoice", ""))
        }
        val elModelBox = EditText(this).apply {
            setSingleLine()
            setText(prefs.getString("elModel", DEFAULT_EL_MODEL))
        }

        val keyBoxes = (1..Speaker.SLOTS).map { i ->
            EditText(this).apply {
                hint = "Gemini API Key $i"
                setSingleLine()
                val v = if (i == 1) prefs.getString("key1", prefs.getString("key", DEFAULT_KEY))
                else prefs.getString("key$i", "")
                setText(v)
            }
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
        val accessBtn = Button(this).apply {
            text = "১. নোটিফিকেশন অ্যাক্সেস চালু করুন"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val saveBtn = Button(this).apply {
            text = "২. সেটিংস সেভ করুন"
            setOnClickListener {
                val ed = prefs.edit()
                ed.putString("provider", providerValues[providerBox.selectedItemPosition])
                ed.putString("elKey", elKeyBox.text.toString().trim())
                ed.putString("elVoice", elVoiceBox.text.toString().trim())
                ed.putString("elModel", elModelBox.text.toString().trim())
                ed.remove("coolEL")
                keyBoxes.forEachIndexed { idx, box ->
                    ed.putString("key${idx + 1}", box.text.toString().trim())
                    ed.remove("cool${idx + 1}")
                }
                ed.putString("model", modelBox.text.toString().trim())
                ed.putString("voice", voiceBox.selectedItem.toString())
                ed.putInt("lastSlot", 1)
                ed.apply()
                Toast.makeText(this@MainActivity, "সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
        val testBtn = Button(this).apply {
            text = "৩. ভয়েস পরীক্ষা করুন"
            setOnClickListener {
                saveBtn.performClick()
                val msg = "আসসালামু আলাইকুম। এটি একটি পরীক্ষামূলক বার্তা।"
                val ctx = this@MainActivity
                if (Speaker.planOf(ctx) == listOf('p')) {
                    phoneSpeak(msg)
                    Toast.makeText(ctx, "ফোনের ভয়েসে চলছে", Toast.LENGTH_SHORT).show()
                } else {
                    Thread {
                        val f = Speaker.fetch(ctx, msg)
                        val pcm = f.pcm
                        val phone = Speaker.allowPhone(ctx)
                        val text = if (pcm != null) {
                            "পরীক্ষা সফল (${f.source}, ${f.ms / 100 / 10.0} সেকেন্ড)" +
                                (if (f.fail.isNotBlank()) "\nআগে ব্যর্থ: ${f.fail}" else "")
                        } else if (phone) {
                            "অনলাইন ভয়েস ব্যর্থ, ফোনের ভয়েসে পড়া হচ্ছে: ${f.error}"
                        } else {
                            "ব্যর্থ: ${f.error}"
                        }
                        runOnUiThread {
                            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
                            if (pcm == null && phone) phoneSpeak(msg)
                        }
                        if (pcm != null) Speaker.play(ctx, pcm, f.rate)
                    }.start()
                }
            }
        }
        testMsgBox = EditText(this).apply {
            setText(
                prefs.getString(
                    "inbox",
                    "আসসালামু আলাইকুম। আপনার একটি নতুন বার্তা এসেছে, নোটিফিকেশন ঠিকঠাক কাজ করছে।"
                )
            )
        }
        logCount = TextView(this).apply { textSize = 13f }
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val clearLogBtn = Button(this).apply {
            text = "লগ মুছুন"
            setOnClickListener {
                prefs.edit().remove("lastLog").apply()
                renderLog()
            }
        }

        root.addView(label("কোন ভয়েস ব্যবহার হবে (ক্রম: আগেরটা না চললে পরেরটা। \"শুধু\" বেছে নিলে ব্যর্থ হলে আর কিছু পড়া হবে না)"))
        root.addView(providerBox)
        root.addView(label("ElevenLabs API Key"))
        root.addView(elKeyBox)
        root.addView(label("ElevenLabs Voice ID (ElevenLabs-এর Voices পাতা থেকে কপি করুন)"))
        root.addView(elVoiceBox)
        root.addView(label("ElevenLabs মডেল (বাংলার জন্য eleven_v3)"))
        root.addView(elModelBox)
        root.addView(label("Gemini API Key (একটায় ত্রুটি হলে নিজে পরেরটায় যাবে। দরকার নেই এমন ঘর ফাঁকা রাখুন)"))
        keyBoxes.forEach { root.addView(it) }
        root.addView(label("Gemini মডেল (যেমন gemini-3.8-flash-lite-tts)"))
        root.addView(modelBox)
        root.addView(label("Gemini ভয়েস"))
        root.addView(voiceBox)
        root.addView(label("Target App: Muse (Meta)"))
        root.addView(TextView(this).apply {
            text = "com.facebook.aura"
            textSize = 14f
        })
        root.addView(accessBtn)
        root.addView(saveBtn)
        root.addView(testBtn)
        root.addView(label("ইনপুট বক্স: নোটিফিকেশনের লেখা এখানে এসে বসে, সেখান থেকেই পড়া হয়। চাইলে নিজেও লিখে পরীক্ষা করতে পারেন"))
        root.addView(testMsgBox)
        root.addView(label("শেষ নোটিফিকেশনের হিসাব (নতুনটা উপরে, অ্যাপে ফিরে এলে নতুন হয়)"))
        root.addView(logCount)
        root.addView(logContainer)
        root.addView(clearLogBtn)

        setContentView(scroll)
    }

    override fun onPause() {
        InputBus.listener = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        prefs.getString("inbox", null)?.let { testMsgBox.setText(it) }
        InputBus.listener = { t -> runOnUiThread { testMsgBox.setText(t) } }
        renderLog()
    }

    private fun renderLog() {
        val items = prefs.getString("lastLog", "").orEmpty()
            .split(LOG_SEP)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        logCount.text = if (items.isEmpty()) {
            "এখনো কোনো নোটিফিকেশন পড়া হয়নি"
        } else {
            "মোট ${items.size}টা নোটিফিকেশন"
        }
        logContainer.removeAllViews()
        if (items.isEmpty()) return
        val pad = (10 * resources.displayMetrics.density).toInt()
        for (entry in items) {
            val v = TextView(this).apply {
                text = entry
                textSize = 13f
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(Color.parseColor("#F1F1F1"))
            }
            logContainer.addView(v)
            val spacer = TextView(this).apply {
                textSize = 4f
                text = ""
            }
            logContainer.addView(spacer)
        }
    }

    private fun phoneSpeak(msg: String) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                engine?.setAudioAttributes(Speaker.outAttrs(this))
                engine?.language = Locale("bn", "BD")
                engine?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "t")
            }
        }
    }



}

object Speaker {

    private const val SAMPLE_RATE = 24000
    private const val BUDGET_MS = 30000L
    const val SLOTS = 5

    /** fetch-এর ফল। error == null মানে সফল (pcm আছে)। */
    class Fetched(
        val pcm: ByteArray?,
        val rate: Int,
        val source: String,
        val ms: Long,
        val fail: String,
        val error: String?
    )

    private class Got(val pcm: ByteArray?, val rate: Int, val source: String, val error: String?)

    private class Result(val pcm: ByteArray?, val error: String?, val code: Int, val restMs: Long)

    /** সেটিং থেকে ভয়েসের ক্রম, যেমন [g, e, p]। */
    fun planOf(ctx: Context): List<Char> {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val norm = normProvider(prefs.getString("provider", null))
        return norm.split("_").filter { it.isNotEmpty() }.map { it[0] }
    }

    /** ফোনের ভয়েস ব্যাকআপ হিসেবে আছে কি না। */
    fun allowPhone(ctx: Context): Boolean = 'p' in planOf(ctx)

    /** সবসময় রিংটোন স্ট্রিমে বাজে (রিংটোন স্লাইডার মানে, মিডিয়ার সাথে সম্পর্ক নেই)। */
    fun outAttrs(@Suppress("UNUSED_PARAMETER") ctx: Context): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    /** রিংটোন সাইলেন্ট/ভাইব্রেট মোডে বা রিংটোন ভলিউম শূন্য হলে true: তখন কিছুই পড়া হবে না। */
    fun isMuted(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true
        return am.getStreamVolume(AudioManager.STREAM_RING) == 0
    }

    /** ব্লুটুথ আউটপুট থাকলে সেটা (শুধু ব্লুটুথে পাঠানোর চেষ্টা), নইলে null। */
    private fun btDevice(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }

    /**
     * শুধু অডিও আনে, বাজায় না। ব্যাকগ্রাউন্ড থ্রেডে চালাতে হবে।
     * একাধিক নোটিফিকেশনের অডিও একসাথে আনা যায়, বাজানো আলাদা ধাপে (play)।
     */
    fun fetch(ctx: Context, text: String): Fetched {
        val t0 = System.currentTimeMillis()
        val errs = ArrayList<String>()
        for (step in planOf(ctx)) {
            when (step) {
                'g' -> {
                    val g = fetchGemini(ctx, text)
                    val pcm = g.pcm
                    if (pcm != null) {
                        return Fetched(
                            pcm, g.rate, g.source,
                            System.currentTimeMillis() - t0, errs.joinToString(" | "), null
                        )
                    }
                    errs.add("Gemini: ${g.error}")
                }
                'e' -> {
                    val g = fetchEleven(ctx, text)
                    val pcm = g.pcm
                    if (pcm != null) {
                        return Fetched(
                            pcm, g.rate, g.source,
                            System.currentTimeMillis() - t0, errs.joinToString(" | "), null
                        )
                    }
                    errs.add("ElevenLabs: ${g.error}")
                }
                'p' -> {
                    val all = errs.joinToString(" | ")
                    return Fetched(
                        null, 0, "", System.currentTimeMillis() - t0, all,
                        if (errs.isEmpty()) "ফোনের ভয়েস" else all
                    )
                }
            }
        }
        val all = errs.joinToString(" | ")
        return Fetched(
            null, 0, "", System.currentTimeMillis() - t0, all,
            all.ifEmpty { "কোনো ভয়েস বাছা নেই" }
        )
    }

    private fun restFor(code: Int, resp: String): Long = when (code) {
        429 -> {
            if (resp.contains("PerDay", ignoreCase = true)) {
                30 * 60 * 1000L
            } else {
                // মিনিটের সীমা: Google যত সেকেন্ড বলে ততই বিশ্রাম, না বললে ১ মিনিট
                val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(resp)
                val s = m?.groupValues?.get(1)?.toLongOrNull() ?: 60L
                (minOf(s, 600L) + 1L) * 1000L
            }
        }
        401, 403 -> 30 * 60 * 1000L
        else -> 0L
    }

    private fun fetchEleven(ctx: Context, text: String): Got {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val key = prefs.getString("elKey", "").orEmpty().trim()
        val voice = prefs.getString("elVoice", "").orEmpty().trim()
        val model = prefs.getString("elModel", DEFAULT_EL_MODEL).orEmpty().trim()
            .ifEmpty { DEFAULT_EL_MODEL }
        if (key.isEmpty()) return Got(null, 0, "", "ElevenLabs key দেওয়া হয়নি")
        if (voice.isEmpty()) return Got(null, 0, "", "Voice ID দেওয়া হয়নি")
        if (prefs.getLong("coolEL", 0L) > System.currentTimeMillis()) {
            return Got(null, 0, "", "কিছুক্ষণের জন্য বন্ধ")
        }

        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("text", text)
                put("model_id", model)
                put("language_code", "bn")
            }
            val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=pcm_24000")
            val c = url.openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 12000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("xi-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code !in 200..299) {
                val resp = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = try {
                    JSONObject(resp).getJSONObject("detail").getString("message")
                } catch (e: Exception) {
                    resp.take(100)
                }
                if (code == 401 || code == 402 || code == 429) {
                    // ৪২৯ মানে একসাথে অনেক অনুরোধ, অল্প বিশ্রামই যথেষ্ট
                    val rest = if (code == 429) 20 * 1000L else 5 * 60 * 1000L
                    prefs.edit().putLong("coolEL", System.currentTimeMillis() + rest).apply()
                }
                return Got(null, 0, "", "ত্রুটি $code: ${msg.take(80)}")
            }
            val pcm = c.inputStream.use { it.readBytes() }
            Got(pcm, SAMPLE_RATE, "ElevenLabs", null)
        } catch (e: IOException) {
            conn?.disconnect()
            Got(null, 0, "", "ইন্টারনেট ত্রুটি: ${e.message}")
        } catch (e: Exception) {
            conn?.disconnect()
            Got(null, 0, "", "ত্রুটি: ${e.message}")
        }
    }

    private fun fetchGemini(ctx: Context, text: String): Got {
        val t0 = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val model = prefs.getString("model", DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        val voice = prefs.getString("voice", DEFAULT_VOICE).orEmpty().ifEmpty { DEFAULT_VOICE }

        val all = (1..SLOTS).mapNotNull { i ->
            val k = prefs.getString(
                "key$i",
                if (i == 1) prefs.getString("key", DEFAULT_KEY) else ""
            ).orEmpty().trim()
            if (k.isEmpty()) null else i to k
        }
        if (all.isEmpty()) return Got(null, 0, "", "API key দেওয়া হয়নি")

        val slots = all.filter { prefs.getLong("cool${it.first}", 0L) <= t0 }
        if (slots.isEmpty()) return Got(null, 0, "", "সব key কিছুক্ষণের জন্য বন্ধ (কোটা শেষ)")

        val start = prefs.getInt("lastSlot", 1)
        val ordered = slots.sortedBy { (it.first - start + SLOTS) % SLOTS }

        val errors = ArrayList<String>()
        outer@ for ((slot, key) in ordered) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val remaining = BUDGET_MS - (System.currentTimeMillis() - t0)
                if (remaining < 3000) {
                    errors.add("সময় শেষ")
                    break@outer
                }
                val r: Result = try {
                    request(key, model, voice, text, minOf(remaining, 20000L).toInt())
                } catch (e: IOException) {
                    errors.add("key $slot: ইন্টারনেট ত্রুটি: ${e.message}")
                    continue@outer
                } catch (e: Exception) {
                    Result(null, "ত্রুটি: ${e.message}", 0, 0L)
                }
                val raw = r.pcm
                if (raw != null) {
                    val (data, rate) = decodeAudio(raw)
                    prefs.edit().putInt("lastSlot", slot).apply()
                    return Got(data, rate, "Gemini key $slot", null)
                }
                errors.add("key $slot: ${r.error}")
                if (r.restMs > 0) {
                    prefs.edit().putLong("cool$slot", System.currentTimeMillis() + r.restMs).apply()
                    continue@outer
                }
                // সার্ভারের সাময়িক ত্রুটি বা অডিও না আসা: একই key-তে আরেকবার চেষ্টা
                val retryable = r.code == 0 || r.code == 500 || r.code == 503
                if (!retryable) continue@outer
            }
        }
        return Got(null, 0, "", errors.joinToString(" | "))
    }

    private fun request(key: String, model: String, voice: String, text: String, readMs: Int): Result {
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
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = readMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(resp).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    resp.take(100)
                }
                return Result(null, "ত্রুটি $code: ${msg.take(80)}", code, restFor(code, resp))
            }

            val root = JSONObject(resp)
            val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
            var b64: String? = null
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val d = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!d.isNullOrEmpty()) {
                        b64 = d
                        break
                    }
                }
            }
            if (b64 == null) {
                val reason = cand?.optString("finishReason").orEmpty()
                val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                return Result(null, "অডিও আসেনি ($reason $block)".trim(), 0, 0L)
            }
            return Result(Base64.decode(b64, Base64.DEFAULT), null, code, 0L)
        } catch (e: Exception) {
            // সফল হলে disconnect করা হয় না, যাতে সংযোগ আবার কাজে লাগে (দ্রুত হয়)
            conn.disconnect()
            throw e
        }
    }

    /** WAV হেডার থাকলে সরিয়ে শুধু অডিও অংশ আর স্যাম্পল রেট দেয়। */
    private fun decodeAudio(raw: ByteArray): Pair<ByteArray, Int> {
        var rate = SAMPLE_RATE
        var data = raw
        if (raw.size > 44 &&
            String(raw, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(raw, 8, 4, Charsets.US_ASCII) == "WAVE"
        ) {
            var pos = 12
            while (pos + 8 <= raw.size) {
                val id = String(raw, pos, 4, Charsets.US_ASCII)
                val size = (raw[pos + 4].toInt() and 0xFF) or
                    ((raw[pos + 5].toInt() and 0xFF) shl 8) or
                    ((raw[pos + 6].toInt() and 0xFF) shl 16) or
                    ((raw[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "fmt " && pos + 16 <= raw.size) {
                    rate = (raw[pos + 12].toInt() and 0xFF) or
                        ((raw[pos + 13].toInt() and 0xFF) shl 8) or
                        ((raw[pos + 14].toInt() and 0xFF) shl 16) or
                        ((raw[pos + 15].toInt() and 0xFF) shl 24)
                } else if (id == "data") {
                    val start = pos + 8
                    val end = if (size < 0 || start + size > raw.size) raw.size else start + size
                    data = raw.copyOfRange(start, end)
                    break
                }
                pos += 8 + size + (size and 1)
            }
        }
        if (rate < 8000 || rate > 48000) rate = SAMPLE_RATE
        return Pair(data, rate)
    }

    /** কাঁচা ১৬-বিট মোনো অডিও বাজায় (শেষ না হওয়া পর্যন্ত অপেক্ষা করে)। শেষে ০.১ সেকেন্ড নীরবতা যোগ করে। */
    fun play(ctx: Context, data: ByteArray, rate: Int) {
        if (isMuted(ctx)) return
        val padded = data.copyOf((data.size / 2) * 2 + (rate / 10) * 2)
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(outAttrs(ctx))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val dev = btDevice(ctx)
        if (dev != null) track.setPreferredDevice(dev)
        try {
            track.play()
            track.write(padded, 0, padded.size)
            val totalFrames = padded.size / 2
            val deadline = System.currentTimeMillis() + (totalFrames * 1000L / rate) + 3000
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

    private val playPool = Executors.newSingleThreadExecutor()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsDone: CountDownLatch? = null

    private val dedupeLock = Any()
    private var lastText = ""
    private var lastTime = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        // শুধুমাত্র Target App
        if (sbn.packageName != TARGET_PACKAGE) return

        val notification = sbn.notification

        // Group summary বাদ
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            return
        }

        val extras = notification.extras

        val title = extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            .orEmpty()

        val body = extractNotificationText(extras)

        // ১২০ অক্ষরের ক্যাপ রাখা হয়েছে: গতি ও কোটা বাঁচাতে
        val message = body.ifBlank { title }.trim().take(120)

        if (message.isBlank()) return

        // Silent / Vibrate / Ring volume 0 হলে API call না করা
        if (Speaker.isMuted(this)) {
            saveLog(
                "অডিও বন্ধ\n" +
                "Target: $TARGET_PACKAGE\n" +
                "কারণ: ফোন Silent/Vibrate অথবা Ring volume 0"
            )
            return
        }

        // একই notification অল্প সময়ের মধ্যে পুনরায় এলে বাদ
        if (isDuplicate(message)) return

        // Inbox-এ রাখা
        saveInbox(message)

        // অ্যাপ খোলা থাকলে UI-তে পাঠানো
        InputBus.listener?.invoke(message)

        // ফোনের ভয়েস হলে API দরকার নেই
        if (Speaker.planOf(this) == listOf('p')) {
            playPool.execute {
                speakPhone(message)
                saveLog(
                    "Target: $TARGET_PACKAGE\n" +
                    "বার্তা: ${message.take(100)}\n" +
                    "ফলাফল: ফোনের ভয়েস"
                )
            }
            return
        }

        // ভয়েস পরীক্ষা বাটনের মতোই: একই থ্রেডে fetch, তারপর play, কোনো আলাদা fetch queue না
        playPool.execute {
            // টেস্ট বাটনের মতো key 1 থেকে শুরু, যাতে একই key ক্রমে চেষ্টা হয়
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putInt("lastSlot", 1).apply()
            val result = Speaker.fetch(this, message)
            if (result.pcm != null) {
                Speaker.play(this, result.pcm, result.rate)
                saveLog(
                    "Target: $TARGET_PACKAGE\n" +
                    "বার্তা: ${message.take(100)}\n" +
                    "ফলাফল: ${result.source}\n" +
                    "সময়: ${secText(result.ms)}" +
                    (if (result.fail.isNotBlank()) "\nআগে ব্যর্থ: ${result.fail}" else "")
                )
            } else if (Speaker.allowPhone(this)) {
                speakPhone(message)
                saveLog(
                    "Target: $TARGET_PACKAGE\n" +
                    "বার্তা: ${message.take(100)}\n" +
                    "ফলাফল: Online TTS ব্যর্থ → ফোনের ভয়েস\n" +
                    "কারণ: ${result.error}"
                )
            } else {
                saveLog(
                    "Target: $TARGET_PACKAGE\n" +
                    "বার্তা: ${message.take(100)}\n" +
                    "ফলাফল: ব্যর্থ\n" +
                    "কারণ: ${result.error}"
                )
            }
        }
    }

    private fun isDuplicate(text: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(dedupeLock) {
            if (text == lastText && now - lastTime < 10_000) {
                return true
            }
            lastText = text
            lastTime = now
            return false
        }
    }

    private fun saveInbox(text: String) {
        getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .edit()
            .putString("inbox", text)
            .apply()
    }

    private fun extractNotificationText(extras: Bundle): String {
        val candidates = mutableListOf<String>()

        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.let {
            if (it.isNotBlank()) candidates.add(it)
        }

        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.let {
            if (it.isNotBlank()) candidates.add(it)
        }

        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
            val text = lines
                .map { it.toString().trim() }
                .filter { it.isNotBlank() }
                .joinToString(". ")
            if (text.isNotBlank()) {
                candidates.add(text)
            }
        }

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null) {
            val text = messages
                .mapNotNull {
                    (it as? Bundle)
                        ?.getCharSequence("text")
                        ?.toString()
                }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .takeLast(2)
                .joinToString(". ")
            if (text.isNotBlank()) {
                candidates.add(text)
            }
        }

        return candidates.maxByOrNull { it.length }.orEmpty()
    }

    private fun secText(ms: Long): String {
        return String.format(Locale.US, "%.1f সেকেন্ড", ms / 1000.0)
    }

    private fun saveLog(entry: String) {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val old = prefs.getString("lastLog", "").orEmpty()
        val logs = if (old.isBlank()) {
            emptyList()
        } else {
            old.split(LOG_SEP)
        }
        val updated = (listOf(
            "${SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())}  $entry"
        ) + logs).take(6)
        prefs.edit().putString("lastLog", updated.joinToString(LOG_SEP)).apply()
    }

    private fun ensureTts(): TextToSpeech? {
        tts?.let { return it }
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val engine = TextToSpeech(applicationContext) { status ->
            success.set(status == TextToSpeech.SUCCESS)
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            engine.shutdown()
            return null
        }
        if (!success.get()) {
            engine.shutdown()
            return null
        }
        engine.language = Locale("bn", "BD")
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    ttsDone?.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    ttsDone?.countDown()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    ttsDone?.countDown()
                }
            }
        )
        tts = engine
        return engine
    }

    private fun speakPhone(text: String) {
        if (Speaker.isMuted(this)) return
        val engine = ensureTts() ?: return
        engine.setAudioAttributes(Speaker.outAttrs(this))
        val latch = CountDownLatch(1)
        ttsDone = latch
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "notif_voice")
        if (result != TextToSpeech.SUCCESS) {
            return
        }
        latch.await(30, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        playPool.shutdownNow()
        super.onDestroy()
    }
}
