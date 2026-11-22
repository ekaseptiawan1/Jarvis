package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var endpoint: EditText
    private lateinit var apiKey: EditText
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
            setBackgroundColor(0xFF10182B.toInt())
        }
        fun label(s: String, size: Float = 16f) = TextView(this).apply {
            text = s; textSize = size; setTextColor(0xFFEAF2FF.toInt())
        }
        status = label("JARVIS • Siap dikonfigurasi", 22f)
        layout.addView(status)
        layout.addView(label("Asisten suara AI pribadi", 14f))
        val start = Button(this).apply { text = "AKTIFKAN JARVIS" }
        start.setOnClickListener { requestAndStart() }
        layout.addView(start)
        val stop = Button(this).apply { text = "HENTIKAN MIKROFON" }
        stop.setOnClickListener { stopService(Intent(this, VoiceService::class.java)); status.text = "Mikrofon dihentikan" }
        layout.addView(stop)
        val access = Button(this).apply { text = "IZINKAN KONTROL LAYAR (ACCESSIBILITY)" }
        access.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        layout.addView(access)
        layout.addView(label("Endpoint API kompatibel OpenAI (contoh: https://api.openai.com)"))
        endpoint = EditText(this).apply { setText(getSharedPreferences("com.jarvis.assistant_preferences", 0).getString("endpoint", "https://api.openai.com")); setTextColor(-1); hint = "https://..." }
        layout.addView(endpoint)
        layout.addView(label("API key (disimpan lokal di perangkat)"))
        apiKey = EditText(this).apply { setText(getSharedPreferences("com.jarvis.assistant_preferences", 0).getString("key", "")); setTextColor(-1); hint = "Masukkan API key"; inputType = 129 }
        layout.addView(apiKey)
        val save = Button(this).apply { text = "SIMPAN PENGATURAN AI" }
        save.setOnClickListener {
            getSharedPreferences("com.jarvis.assistant_preferences", 0).edit().putString("endpoint", endpoint.text.toString().trim().trimEnd('/'))
                .putString("key", apiKey.text.toString().trim()).apply()
            Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
        }
        layout.addView(save)
        log = label("Perintah contoh: “Buka YouTube”, “Buka WhatsApp”, “Jam berapa sekarang?”")
        layout.addView(log)
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
            requestPermissionsIfNeeded(); return
        }
        getSharedPreferences("com.jarvis.assistant_preferences", 0).edit().putString("endpoint", endpoint.text.toString().trim().trimEnd('/'))
            .putString("key", apiKey.text.toString().trim()).apply()
        ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
        status.text = "JARVIS aktif • mikrofon berjalan melalui notifikasi"
    }
}