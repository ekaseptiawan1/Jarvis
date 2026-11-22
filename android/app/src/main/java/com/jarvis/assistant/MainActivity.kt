package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var apiKey: EditText
    private lateinit var wakeKey: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("com.jarvis.assistant_preferences", 0)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
            setBackgroundColor(0xFF10182B.toInt())
        }
        fun label(s: String, size: Float = 16f) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(0xFFEAF2FF.toInt())
        }

        status = label("JARVIS • Gemini AI", 22f)
        layout.addView(status)
        layout.addView(label("Asisten suara AI pribadi • Bahasa Indonesia", 14f))

        val start = Button(this).apply { text = "AKTIFKAN JARVIS" }
        start.setOnClickListener { requestAndStart() }
        layout.addView(start)

        val stop = Button(this).apply { text = "HENTIKAN JARVIS" }
        stop.setOnClickListener {
            stopService(Intent(this, VoiceService::class.java))
            status.text = "JARVIS dihentikan"
        }
        layout.addView(stop)

        val notifications = Button(this).apply { text = "IZINKAN BACA & BALAS PESAN" }
        notifications.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        layout.addView(notifications)
        layout.addView(label("JARVIS membacakan pesan masuk dan meminta konfirmasi sebelum membalas.", 13f))

        val access = Button(this).apply { text = "IZINKAN KONTROL LAYAR (ACCESSIBILITY)" }
        access.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        layout.addView(access)

        layout.addView(label("Picovoice AccessKey • untuk mendeteksi kata “Jarvis”"))
        wakeKey = EditText(this).apply {
            setText(prefs.getString("picovoice_key", ""))
            setTextColor(-1)
            hint = "Tempel Picovoice AccessKey di sini"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(wakeKey)

        val saveWake = Button(this).apply { text = "SIMPAN ACCESSKEY WAKE WORD" }
        saveWake.setOnClickListener {
            prefs.edit().putString("picovoice_key", wakeKey.text.toString().trim()).apply()
            Toast.makeText(this, "Picovoice AccessKey disimpan di perangkat", Toast.LENGTH_SHORT).show()
        }
        layout.addView(saveWake)
        layout.addView(label("Buat AccessKey gratis di console.picovoice.ai. Jangan bagikan AccessKey kepada siapa pun.", 12f))

        layout.addView(label("Gemini API Key (buat di Google AI Studio)"))
        apiKey = EditText(this).apply {
            setText(prefs.getString("gemini_key", ""))
            setTextColor(-1)
            hint = "Tempel Gemini API Key di sini"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(apiKey)

        val save = Button(this).apply { text = "SIMPAN GEMINI API KEY" }
        save.setOnClickListener {
            prefs.edit().putString("gemini_key", apiKey.text.toString().trim()).apply()
            Toast.makeText(this, "Gemini API Key disimpan di perangkat", Toast.LENGTH_SHORT).show()
        }
        layout.addView(save)
        layout.addView(label("Wake word: “Jarvis” • standby memakai pendeteksi lokal. Setelah dipanggil, pengenalan perintah aktif selama 12 detik tanpa aktivitas."))
        layout.addView(label("Contoh: “Buka YouTube”, “Buka WhatsApp”, atau tanyakan apa saja."))
        setContentView(ScrollView(this).apply { addView(layout) })
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 9)
    }

    private fun requestAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        val prefs = getSharedPreferences("com.jarvis.assistant_preferences", 0)
        val picovoice = wakeKey.text.toString().trim()
        if (picovoice.isBlank()) {
            Toast.makeText(this, "Masukkan Picovoice AccessKey untuk mengaktifkan wake word Jarvis", Toast.LENGTH_LONG).show()
            wakeKey.requestFocus()
            return
        }
        prefs.edit()
            .putString("picovoice_key", picovoice)
            .putString("gemini_key", apiKey.text.toString().trim())
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
        status.text = "JARVIS aktif • ucapkan “Jarvis”"
    }
}
