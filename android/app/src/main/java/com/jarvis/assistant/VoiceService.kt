package com.jarvis.assistant

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val client = OkHttpClient()
    private val channel = "jarvis_voice"
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createChannel()
        startForeground(7, notification("Menyiapkan pengenalan suara…"))
        beginListening()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        listening = false
        recognizer?.destroy(); recognizer = null
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale("id", "ID") }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(channel, "JARVIS Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }
    private fun notification(msg: String) = NotificationCompat.Builder(this, channel)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("JARVIS aktif")
        .setContentText(msg).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    private fun beginListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { speak("Layanan pengenalan suara tidak tersedia."); return }
        listening = true
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { update("Mendengarkan…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { update("Memproses suara…") }
                override fun onError(error: Int) { if (listening) Handler(Looper.getMainLooper()).postDelayed({ startRecognition() }, 900) }
                override fun onResults(results: Bundle?) {
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!words.isNullOrBlank()) handleCommand(words)
                    if (listening) Handler(Looper.getMainLooper()).postDelayed({ startRecognition() }, 700)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startRecognition()
        } catch (_: Exception) { update("Tidak dapat memulai mikrofon") }
    }
    private fun startRecognition() {
        if (!listening) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { recognizer?.startListening(i) } catch (_: Exception) {}
    }
    private fun handleCommand(raw: String) {
        val s = raw.lowercase(Locale("id", "ID")).trim()
        update("Kamu: $raw")
        when {
            s.contains("kembali") || s.contains("tombol kembali") -> {
                if (JarvisAccessibilityService.global("back")) speak("Kembali.") else speak("Aktifkan izin Accessibility JARVIS terlebih dahulu.")
            }
            s.contains("ke beranda") || s == "home" -> {
                if (JarvisAccessibilityService.global("home")) speak("Kembali ke beranda.") else speak("Aktifkan izin Accessibility JARVIS terlebih dahulu.")
            }
            s.contains("aplikasi terbaru") -> {
                if (JarvisAccessibilityService.global("recents")) speak("Membuka aplikasi terbaru.") else speak("Aktifkan izin Accessibility JARVIS terlebih dahulu.")
            }
            s.startsWith("buka ") || s.startsWith("jalankan ") -> {
                val app = s.removePrefix("buka ").removePrefix("jalankan ").trim()
                if (!launchApp(app)) speak("Maaf, aplikasi $app tidak ditemukan.")
            }
            s.contains("berhenti") || s.contains("matikan mikrofon") -> {
                speak("Baik, saya berhenti mendengarkan.")
                stopSelf()
            }
            s.contains("jam berapa") -> speak("Sekarang pukul " + java.text.SimpleDateFormat("HH:mm", Locale("id", "ID")).format(java.util.Date()))
            else -> askAi(raw)
        }
    }
    private fun launchApp(query: String): Boolean {
        val pm = packageManager
        val matches = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val found = matches.firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(query) }
        val launch = found?.let { pm.getLaunchIntentForPackage(it.packageName) } ?: run {
            val common = mapOf("youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp",
                "kamera" to "com.android.camera", "chrome" to "com.android.chrome", "instagram" to "com.instagram.android",
                "telegram" to "org.telegram.messenger", "maps" to "com.google.android.apps.maps")
            common[query]?.let { pm.getLaunchIntentForPackage(it) }
        }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); speak("Membuka $query."); return true
        }
        return false
    }
    private fun askAi(prompt: String) {
        val endpoint = getSharedPreferences("com.jarvis.assistant_preferences", 0).getString("endpoint", "https://api.openai.com")
        val key = getSharedPreferences("com.jarvis.assistant_preferences", 0).getString("key", "") ?: ""
        if (key.isBlank()) { speak("Perintah belum dikenali. Masukkan API key AI pada pengaturan JARVIS."); return }
        val body = JSONObject().put("model", "gpt-4o-mini")
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "Kamu JARVIS, asisten Android yang ramah. Jawab singkat dalam bahasa Indonesia."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val req = Request.Builder().url(endpoint!!.trimEnd('/') + "/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { speak("Koneksi AI gagal. Periksa internet dan endpoint API.") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val answer = try { JSONObject(it.body?.string() ?: "").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }
                    catch (_: Exception) { "Saya belum bisa memproses jawaban dari server." }
                    speak(answer)
                }
            }
        })
    }
    private fun speak(text: String) {
        update(text)
        Handler(Looper.getMainLooper()).post { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply") }
    }
    private fun update(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(7, notification(msg.take(100)))
    }
}