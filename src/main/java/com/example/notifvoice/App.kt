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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

const val DEFAULT_KEY = ""
const val DEFAULT_MODEL = "gemini-3.1-flash-tts-preview"
const val DEFAULT_VOICE = "Kore"
const val DEFAULT_EL_MODEL = "eleven_v3"

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

        val providerValues = listOf("eleven", "gemini", "phone")
        val providerLabels = listOf(
            "ElevenLabs → Gemini → ফোনের ভয়েস",
            "Gemini → ফোনের ভয়েস",
            "শুধু ফোনের ভয়েস (অফলাইন)"
        )
        val providerBox = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                providerLabels
            )
            val cur = prefs.getString("provider", "gemini")
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
            text = "৪. ভয়েস পরীক্ষা করুন"
            setOnClickListener {
                saveBtn.performClick()
                val msg = "আসসালামু আলাইকুম। এটি একটি পরীক্ষামূলক বার্তা।"
                if (providerValues[providerBox.selectedItemPosition] == "phone") {
                    var engine: TextToSpeech? = null
                    engine = TextToSpeech(this@MainActivity) { st ->
                        if (st == TextToSpeech.SUCCESS) {
                            engine?.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            engine?.language = Locale("bn", "BD")
                            engine?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "t")
                        }
                    }
                    Toast.makeText(this@MainActivity, "ফোনের ভয়েসে চলছে", Toast.LENGTH_SHORT).show()
                } else {
                    Thread {
                        val err = Speaker.speak(this@MainActivity, msg)
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                err ?: ("পরীক্ষা সফল (${Speaker.lastUsed}, ${Speaker.lastMs / 100 / 10.0} সেকেন্ড)" +
                                    (if (Speaker.lastElError.isNotBlank()) "\nElevenLabs ব্যর্থ: ${Speaker.lastElError}" else "")),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }.start()
                }
            }
        }

        root.addView(label("কোন ভয়েস ব্যবহার হবে (ক্রম: আগেরটা না চললে পরেরটা)"))
        root.addView(providerBox)
        root.addView(label("ElevenLabs API Key"))
        root.addView(elKeyBox)
        root.addView(label("ElevenLabs Voice ID (ElevenLabs-এর Voices পাতা থেকে কপি করুন)"))
        root.addView(elVoiceBox)
        root.addView(label("ElevenLabs মডেল (বাংলার জন্য eleven_v3)"))
        root.addView(elModelBox)
        root.addView(label("Gemini API Key (একটায় ত্রুটি হলে নিজে পরেরটায় যাবে। দরকার নেই এমন ঘর ফাঁকা রাখুন)"))
        keyBoxes.forEach { root.addView(it) }
        root.addView(label("Gemini মডেল (সাধারণত বদলাতে হয় না)"))
        root.addView(modelBox)
        root.addView(label("Gemini ভয়েস"))
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
    private const val BUDGET_MS = 15000L
    const val SLOTS = 5

    @Volatile
    var lastUsedSlot = 0

    @Volatile
    var lastUsed = ""

    @Volatile
    var lastMs = 0L

    @Volatile
    var lastElError = ""

    private class Result(val pcm: ByteArray?, val error: String?, val code: Int)

    /** সফল হলে null, ব্যর্থ হলে ত্রুটির বার্তা (তখন ফোনের ভয়েসে পড়তে হবে)। */
    fun speak(ctx: Context, text: String): String? {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val provider = prefs.getString("provider", "gemini")
        if (provider == "phone") return "ফোনের ভয়েস"
        if (provider == "eleven") {
            val e = speakEleven(ctx, text)
            lastElError = e ?: ""
            if (e == null) return null
            val g = speakGemini(ctx, text)
            return if (g == null) null else "ElevenLabs: $e | Gemini: $g"
        }
        return speakGemini(ctx, text)
    }

    private fun speakEleven(ctx: Context, text: String): String? {
        val t0 = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val key = prefs.getString("elKey", "").orEmpty().trim()
        val voice = prefs.getString("elVoice", "").orEmpty().trim()
        val model = prefs.getString("elModel", DEFAULT_EL_MODEL).orEmpty().trim()
            .ifEmpty { DEFAULT_EL_MODEL }
        if (key.isEmpty()) return "ElevenLabs key দেওয়া হয়নি"
        if (voice.isEmpty()) return "Voice ID দেওয়া হয়নি"
        if (prefs.getLong("coolEL", 0L) > t0) return "কিছুক্ষণের জন্য বন্ধ"

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
                    prefs.edit().putLong("coolEL", System.currentTimeMillis() + 5 * 60 * 1000L).apply()
                }
                return "ত্রুটি $code: ${msg.take(80)}"
            }
            val pcm = c.inputStream.readBytes()
            lastMs = System.currentTimeMillis() - t0
            playPcm(pcm)
            lastUsed = "ElevenLabs"
            null
        } catch (e: IOException) {
            "ইন্টারনেট ত্রুটি: ${e.message}"
        } catch (e: Exception) {
            "ত্রুটি: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    /** সফল হলে null, ব্যর্থ হলে ত্রুটির বার্তা। ব্যাকগ্রাউন্ড থ্রেডে চালাতে হবে। */
    fun speakGemini(ctx: Context, text: String): String? {
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
        if (all.isEmpty()) return "API key দেওয়া হয়নি"

        val slots = all.filter { prefs.getLong("cool${it.first}", 0L) <= t0 }
        if (slots.isEmpty()) return "সব key কিছুক্ষণের জন্য বন্ধ (কোটা শেষ)"

        val start = prefs.getInt("lastSlot", 1)
        val ordered = slots.sortedBy { (it.first - start + SLOTS) % SLOTS }

        val errors = ArrayList<String>()
        for ((slot, key) in ordered) {
            val remaining = BUDGET_MS - (System.currentTimeMillis() - t0)
            if (remaining < 3000) {
                errors.add("সময় শেষ")
                break
            }
            try {
                val r = request(key, model, voice, text, minOf(remaining, 20000L).toInt())
                val pcm = r.pcm
                if (pcm != null) {
                    lastMs = System.currentTimeMillis() - t0
                    playPcm(pcm)
                    prefs.edit().putInt("lastSlot", slot).apply()
                    lastUsedSlot = slot
                    lastUsed = "Gemini key $slot"
                    return null
                }
                errors.add("key $slot: ${r.error}")
                val rest = when (r.code) {
                    429 -> 5 * 60 * 1000L
                    401, 403 -> 30 * 60 * 1000L
                    else -> 0L
                }
                if (rest > 0) {
                    prefs.edit().putLong("cool$slot", System.currentTimeMillis() + rest).apply()
                }
            } catch (e: IOException) {
                return "ইন্টারনেট ত্রুটি: ${e.message}"
            } catch (e: Exception) {
                errors.add("key $slot: ${e.message}")
            }
        }
        return errors.joinToString(" | ")
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
                return Result(null, "ত্রুটি $code: ${msg.take(80)}", code)
            }

            val b64 = JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getJSONObject("inlineData").getString("data")
            return Result(Base64.decode(b64, Base64.DEFAULT), null, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun playPcm(pcm: ByteArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
    private val pending = AtomicInteger(0)
    private var tts: TextToSpeech? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val apps = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        if (sbn.packageName !in apps) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extractBody(extras).take(250)
        val message = body.ifBlank { title }
        if (message.isBlank()) return

        pending.incrementAndGet()
        executor.execute {
            try {
                if (pending.get() > 1) {
                    fallbackSpeak(message)
                } else {
                    val err = Speaker.speak(this, message)
                    if (err != null) fallbackSpeak(message)
                }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun extractBody(extras: android.os.Bundle): String {
        val c = ArrayList<String>()
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { c.add(it) }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { c.add(it) }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
            c.add(lines.joinToString(". "))
        }
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null) {
            val last = msgs.mapNotNull {
                (it as? android.os.Bundle)?.getCharSequence("text")?.toString()
            }.takeLast(2).joinToString(". ")
            c.add(last)
        }
        return c.filter { it.isNotBlank() }.maxByOrNull { it.length } ?: ""
    }

    private fun fallbackSpeak(text: String) {
        val engine = tts
        if (engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "nv")
            return
        }
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
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
