# Chat AI

Aplikasi Android yang dibuat melalui **GitHub By Novan**.

* **Paket**: `com.chatai.studio`
* **Versi**: `1.0.0.0` (Kode versi: `1`)
* **Target SDK**: Android 35 (Minimal Android 26)

---

## Pembangunan APK Otomatis

Proyek ini dibangun secara otomatis menggunakan alur kerja GitHub Actions (`.github/workflows/bangun-apk.yml`).

Setiap kali alur kerja dijalankan (melalui tombol **Mulai pembangunan APK** di aplikasi atau lewat pemicu commit/manual di GitHub), APK akan langsung dikompilasi.

---

## Metode Penandatanganan APK (Signing)

Alur kerja build mendukung dua opsi penandatanganan:

* **Tanda Tangan Pribadi (Release)**:
  * Aktif otomatis jika berkas `.tanda-tangan/kunci.p12` dan `.tanda-tangan/sandi.properties` tersedia di repositori.
  * APK dikompilasi dalam varian Release dan ditandatangani menggunakan sertifikat PKCS12 pribadi.
  * Aplikasi yang sudah dipasang di perangkat Android dapat selalu diperbarui (*update*) tanpa perlu mencopot versi lama.
  * **Keamanan**: Pastikan repositori ini selalu bersifat **privat** agar sertifikat dan kata sandi Anda tetap terlindungi.

* **Tanda Tangan Bawaan GitHub (Debug)**:
  * Digunakan jika folder `.tanda-tangan` tidak ada di repositori.
  * APK dikompilasi dalam varian Debug menggunakan kunci sementara bawaan runner GitHub Actions.
  * Cocok untuk pengujian cepat. Karena kunci runner berganti di setiap pembangunan baru, pembaruan aplikasi mengharuskan pencopotan (*uninstall*) versi lama terlebih dahulu.

---

## Unduh Berkas APK

Setelah proses pembangunan selesai, berkas APK dapat diunduh melalui:
* **GitHub Releases**: Berkas APK siap pasang otomatis terbit di tab **Releases** dengan tag `v<nomor_build>`.
* **GitHub Actions (Artifacts)**: Berkas APK juga tersimpan sebagai artefak pada riwayat alur kerja build terkait.
