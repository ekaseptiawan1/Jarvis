# JARVIS Android

Starter project Android Kotlin untuk asisten suara berbahasa Indonesia.

## Fitur yang disiapkan
- Foreground microphone service dengan notifikasi persisten.
- SpeechRecognizer locale id-ID dan TextToSpeech.
- Perintah sederhana untuk membuka aplikasi yang terpasang.
- Koneksi ke endpoint API gaya OpenAI Chat Completions.
- Kerangka Accessibility Service (pengguna mengaktifkannya sendiri).

## Batasan penting
- Ini starter project, bukan asisten sistem penuh. Android/OEM dapat menghentikan service karena baterai, dan pengenalan suara bergantung pada layanan perangkat.
- Mulai JARVIS dari layar aplikasi setelah izin diberikan; Android membatasi mulai layanan mikrofon dari background.
- Tidak ada akses universal ke semua aplikasi. Accessibility hanya dapat melakukan tindakan yang diizinkan dan terlihat.
- Notifikasi menunjukkan saat mikrofon aktif. Ucapkan "matikan mikrofon" atau tekan tombol stop.
- SpeechRecognizer dapat mengirim audio ke penyedia pengenalan suara perangkat; offline tidak dijamin.
- API key disimpan lokal untuk prototipe. Untuk produksi, gunakan backend sendiri.
- Belum ada wake-word "Halo Jarvis"; mode always menjalankan pengenalan suara berulang.

## Build APK
1. Pasang Android Studio dan Android SDK Platform 35.
2. Buka folder `android` di Android Studio.
3. Tunggu Gradle Sync selesai.
4. Pilih Build > Build Bundle(s) / APK(s) > Build APK(s).
5. APK debug tersedia di `app/build/outputs/apk/debug/app-debug.apk`.
6. Instal, izinkan mikrofon dan notifikasi, lalu tekan AKTIFKAN JARVIS.

## Pengembangan berikutnya
- Pindahkan konfigurasi ke DataStore dan API key ke backend.
- Tambahkan UI chat, wake-word engine, dan konfirmasi sebelum aksi berisiko.
