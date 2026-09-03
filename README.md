# Wuvatel — M2

Aplikasi Android mandiri untuk OCR dan penerjemahan manga. Pengembangan aktif berada di branch `beta`.

## Status

M1 sudah lolos uji di perangkat Android: pemilih gambar, preview, OCR Jepang on-device, bounding box, dan daftar hasil OCR bekerja.

## Target M2

1. Gunakan ML Kit `TextBlock` sebagai kelompok teks dasar, bukan setiap line sebagai region terpisah.
2. Deteksi kelompok yang dominan vertikal dari bentuk bounding box line.
3. Susun kolom Jepang vertikal dari kanan ke kiri.
4. Gabungkan bounding box semua line dalam kelompok.
5. Urutkan kelompok halaman dari atas ke bawah dan kanan ke kiri dalam baris yang sama.
6. Pertahankan file manga asli tanpa perubahan.

M2 masih berupa pengelompokan OCR berbasis layout. Deteksi bentuk balon manga sebenarnya belum ditambahkan, jadi satu `TextBlock` belum dijamin selalu sama dengan satu balon pada semua halaman.

Belum ada translation, image cleaning, typesetting, ZIP/CBZ, atau AI pada milestone ini.

## Teknologi

- Kotlin
- Jetpack Compose
- Android minSdk 23
- compileSdk/targetSdk 36
- ML Kit Japanese OCR bundled
- Storage Access Framework (`OpenDocument`)

## Build tanpa Android Studio

Project menyertakan GitHub Actions di `.github/workflows/build-apk.yml`.

APK M2 hasil build bernama `Wuvatel-M2-debug.apk` dan dapat diunduh dari halaman Actions melalui HP.

## Struktur utama

- `app/src/main/java/com/example/mangatranslator/MainActivity.kt`
- `app/src/main/java/com/example/mangatranslator/ocr/JapaneseOcrEngine.kt`
- `app/src/main/java/com/example/mangatranslator/ocr/TextRegion.kt`
- `.github/workflows/build-apk.yml`

## Branch

- `beta` — seluruh pengembangan aktif.
- `main` — milestone yang sudah stabil dan lolos pengujian.

## Aturan fondasi

- File manga asli tidak ditimpa.
- OCR dibuat modular.
- Satu milestone diuji sebelum melanjutkan ke milestone berikutnya.
