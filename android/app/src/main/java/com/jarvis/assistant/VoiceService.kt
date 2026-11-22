package com.jarvis.assistant

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var wakeManager: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private val client = OkHttpClient()
    private val channel = "jarvis_voice"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listening = false
    private var commandMode = false
    private var isSpeaking = false
    private var awaitingReplyText = false
    private var awaitingReplyConfirmation = false
    private var replyDraft = ""

    private val idleTimeout = Runnable {
        if (listening && commandMode && !isSpeaking) {
            awaitingReplyText = false
            awaitingReplyConfirmation = false
            replyDraft = ""
            enterWakeMode()
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createChannel()
        startForeground(7, notification("Standby • ucapkan “Jarvis”"))
        listening = true
        enterWakeMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_INCOMING_MESSAGE) {
            val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
            val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
            if (sender.isNotBlank() && message.isNotBlank()) {
                activateCommandMode()
                awaitingReplyText = true
                awaitingReplyConfirmation = false
                replyDraft = ""
                speak("Pesan dari $sender: $message. Mau saya balas apa?")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        listening = false
        mainHandler.removeCallbacksAndMessages(null)
        stopWakeDetector()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    mainHandler.post {
                        isSpeaking = false
                        if (listening) {
                            if (commandMode) {
                                startRecognition()
                                scheduleIdleTimeout()
                            } else {
                                enterWakeMode()
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    mainHandler.post {
                        isSpeaking = false
                        if (listening) {
                            if (commandMode) startRecognition() else enterWakeMode()
                        }
                    }
                }
            })
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(channel, "JARVIS Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    private fun notification(msg: String) = NotificationCompat.Builder(this, channel)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("JARVIS aktif")
        .setContentText(msg)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )).build()

    private fun enterWakeMode() {
        if (!listening || isSpeaking) return
        commandMode = false
        mainHandler.removeCallbacks(idleTimeout)
        stopCommandRecognizer()

        val accessKey = getSharedPreferences("com.jarvis.assistant_preferences", 0)
            .getString("picovoice_key", "").orEmpty()
        if (accessKey.isBlank()) {
            update("Isi Picovoice AccessKey di pengaturan untuk mengaktifkan wake word")
            return
        }

        try {
            stopWakeDetector()
            wakeManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.65f)
                .build(applicationContext, PorcupineManagerCallback {
                    mainHandler.post { onWakeWordDetected() }
                })
            wakeManager?.start()
            update("Standby • menunggu “Jarvis”")
        } catch (e: PorcupineException) {
            stopWakeDetector()
            update("Wake word gagal: periksa AccessKey dan koneksi internet")
        } catch (e: Exception) {
            stopWakeDetector()
            update("Wake word tidak dapat dimulai")
        }
    }

    private fun onWakeWordDetected() {
        if (!listening || commandMode) return
        activateCommandMode()
        speak("Ya, saya mendengarkan.")
    }

    private fun activateCommandMode() {
        mainHandler.removeCallbacks(idleTimeout)
        stopWakeDetector()
        commandMode = true
    }

    private fun stopWakeDetector() {
        val manager = wakeManager ?: return
        try { manager.stop() } catch (_: Exception) {}
        try { manager.delete() } catch (_: Exception) {}
        wakeManager = null
    }

    private fun stopCommandRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun scheduleIdleTimeout() {
        mainHandler.removeCallbacks(idleTimeout)
        if (listening && commandMode && !isSpeaking) {
            mainHandler.postDelayed(idleTimeout, 12_000)
        }
    }

    private fun startRecognition() {
        if (!listening || !commandMode || isSpeaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Layanan pengenalan suara tidak tersedia.")
            return
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { update("Mendengarkan perintah…") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { update("Memproses suara…") }
                    override fun onError(error: Int) {
                        if (listening && commandMode && !isSpeaking) {
                            mainHandler.postDelayed({
                                startRecognition()
                                scheduleIdleTimeout()
                            }, 700)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!words.isNullOrBlank()) {
                            mainHandler.removeCallbacks(idleTimeout)
                            handleCommand(words)
                        } else {
                            startRecognition()
                            scheduleIdleTimeout()
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            update("Tidak dapat memulai pengenalan perintah")
            scheduleIdleTimeout()
        }
    }

    private fun handleCommand(raw: String) {
        val s = raw.lowercase(Locale("id", "ID")).trim()
        update("Kamu: $raw")

        if (awaitingReplyText) {
            if (s in listOf("batal", "jangan jadi", "batalkan")) {
                awaitingReplyText = false
                speak("Baik, pesan tidak jadi dibalas.")
            } else {
                replyDraft = raw.trim()
                awaitingReplyText = false
                awaitingReplyConfirmation = true
                speak("Saya akan membalas: $replyDraft. Ucapkan kirim untuk mengirim, atau batal untuk membatalkan.")
            }
            return
        }

        if (awaitingReplyConfirmation) {
            when {
                s == "kirim" || s == "ya kirim" || s == "kirim sekarang" -> {
                    val sent = MessageReplyBridge.sendReply(this, replyDraft)
                    awaitingReplyConfirmation = false
                    replyDraft = ""
                    speak(if (sent) "Balasan berhasil dikirim." else "Saya tidak menemukan tombol balas cepat untuk pesan ini. Balas langsung dari aplikasinya.")
                }
                s == "batal" || s == "jangan jadi" || s == "batalkan" -> {
                    awaitingReplyConfirmation = false
                    replyDraft = ""
                    speak("Baik, balasan dibatalkan.")
                }
                else -> {
                    replyDraft = raw.trim()
                    speak("Draf diperbarui menjadi: $replyDraft. Ucapkan kirim untuk mengirim, atau batal.")
                }
            }
            return
        }

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
                mainHandler.postDelayed({ stopSelf() }, 900)
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
            val common = mapOf(
                "youtube" to "com.google.android.youtube",
                "whatsapp" to "com.whatsapp",
                "kamera" to "com.android.camera",
                "chrome" to "com.android.chrome",
                "instagram" to "com.instagram.android",
                "telegram" to "org.telegram.messenger",
                "maps" to "com.google.android.apps.maps"
            )
            common[query]?.let { pm.getLaunchIntentForPackage(it) }
        }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            speak("Membuka $query.")
            return true
        }
        return false
    }

    private fun askAi(prompt: String) {
        val key = getSharedPreferences("com.jarvis.assistant_preferences", 0)
            .getString("gemini_key", "") ?: ""
        if (key.isBlank()) {
            speak("Masukkan Gemini API Key terlebih dahulu di pengaturan JARVIS.")
            return
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", "Kamu adalah JARVIS, asisten Android pribadi yang ramah dan cerdas. Jawab singkat, jelas, dan selalu dalam bahasa Indonesia.")
            )))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 700))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"
        val req = Request.Builder().url(url)
            .addHeader("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                speak("Koneksi ke Gemini gagal. Periksa internet dan Gemini API Key.")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val answer = try {
                        val json = JSONObject(it.body?.string() ?: "")
                        if (!it.isSuccessful) {
                            val message = json.optJSONObject("error")?.optString("message")
                            "Gemini mengalami kendala: " + (message ?: "HTTP " + it.code)
                        } else {
                            json.getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts")
                                .let { parts -> (0 until parts.length()).mapNotNull { index ->
                                    parts.getJSONObject(index).optString("text").takeIf { value -> value.isNotBlank() }
                                }.joinToString(" ") }
                                .ifBlank { "Gemini tidak memberikan jawaban teks." }
                        }
                    } catch (_: Exception) {
                        "Saya belum bisa membaca jawaban dari Gemini."
                    }
                    speak(answer)
                }
            }
        })
    }

    private fun speak(text: String) {
        update(text)
        mainHandler.post {
            isSpeaking = true
            try { recognizer?.cancel() } catch (_: Exception) {}
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
            if (result == TextToSpeech.ERROR || tts == null) {
                isSpeaking = false
                if (listening) {
                    if (commandMode) startRecognition() else enterWakeMode()
                }
            }
        }
    }

    private fun update(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(7, notification(msg.take(100)))
    }

    companion object {
        const val ACTION_INCOMING_MESSAGE = "com.jarvis.assistant.INCOMING_MESSAGE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_MESSAGE = "message"
    }
}
