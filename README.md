# Karaoke: Karaoke for TV + Karaoke Remote

Dua aplikasi Android untuk karaoke di rumah. TV menampilkan video dan suara. HP jadi remote.

- **Karaoke for TV** (`com.khalief.karaoke.tv`): layar display di TV. Video karaoke YouTube tampil fullscreen, suara keluar dari TV. Tidak ada menu di TV, cuma layar tunggu berisi QR dan kode 6 digit.
- **Karaoke Remote** (`com.khalief.karaoke.remote`): remote di HP. Cari lagu, pilih lagu, antrean, putar, jeda, lanjut, sebelumnya, berikutnya, putar ulang, dan volume. HP tidak memutar suara atau video sama sekali, semua suara dan video hanya di TV.

## APK

File jadi ada di `apks/`:

- `karaoke-tv-debug.apk` (329 KB) - pasang di TV
- `karaoke-remote-debug.apk` (249 KB) - pasang di HP

Keduanya debug build, sudah ditandatangani dengan keystore debug (`debug.keystore`).

## Cara pasang

Izinkan dulu pemasangan dari sumber tidak dikenal, baru pasang APK-nya:

- **Di TV** (Android TV / Google TV): Settings, lalu Device Preferences, Security & Restrictions, Unknown Sources. Nyalakan untuk aplikasi yang dipakai memasang APK (misal file manager atau browser).
- **Di HP**: Settings, Apps, akses khusus, Install aplikasi tak dikenal. Nyalakan untuk aplikasi yang dipakai memasang APK.

Salin APK ke perangkat lewat USB, file manager, atau kirim dari HP ke TV lewat aplikasi kirim file.

## Cara pakai

1. HP dan TV harus di WiFi yang sama. TV juga butuh internet, karena video diambil dari YouTube.
2. Buka **Karaoke for TV** di TV. Layar tunggu muncul berisi QR besar dan kode 6 digit.
3. Buka **Karaoke Remote** di HP. Izinkan kamera kalau diminta, lalu arahkan kamera ke QR di TV.
4. Setelah terhubung, ketik judul lagu di kolom pencarian, lalu ketuk lagu untuk langsung diputar, atau ketuk tombol + untuk masuk antrean.
5. Kontrol lagu yang sedang jalan dari HP: jeda, lanjut, sebelumnya, berikutnya, putar ulang, dan geser volume. Antrean bisa dihapus satu-satu atau dibersihkan sekaligus.
6. Kalau koneksi putus atau ganti TV, ketuk "Scan Ulang QR TV" di HP.

Pencarian lagu tidak pakai API key. Aplikasi mencoba instance Invidious lalu Piped secara berantai sampai ada yang menjawab. Kalau semua gagal, muncul pesan "pencarian gagal, cek internet / coba lagi". Coba lagi beberapa detik kemudian.

## Cara kerja

TV menjalankan server HTTP (port 8080) dan WebSocket (port 8081) di jaringan lokal. Halaman web yang sama dipakai dua mode: `?mode=tv` untuk TV dan `?mode=phone` untuk HP. Antrean dan status pemutar disimpan di halaman TV. HP hanya mengirim perintah lewat WebSocket, TV menyiarkan status balik ke HP. QR berisi alamat controller plus kode 6 digit, server TV memeriksa kode itu sebelum menerima koneksi HP.

## Build ulang

Gradle tidak bisa jalan di VM ini. Sandbox memblokir koneksi TCP antar proses Java, dan daemon Gradle butuh koneksi itu. Build memakai tool Android langsung lewat `build.sh`:

```bash
./build.sh
```

Yang dibutuhkan: JDK 17 di `~/.local/jdk17` dan Android SDK (platform 34 + build-tools 34.0.0) di `~/.local/android-sdk`. Library ada di `libs/` (NanoHTTPD, Java-WebSocket, SLF4J, ZXing core). Hasil build masuk ke `apks/`.
