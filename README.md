# Wuvatel — M3.1.3

Aplikasi Android mandiri untuk OCR dan penerjemahan manga. Pengembangan aktif berada di branch `beta`.

## Status

- M1: import gambar, preview, OCR Jepang on-device, bounding box.
- M2: grouping teks manga, urutan baca, re-OCR konservatif, editor/verifikasi OCR manual.
- M3.1.3: terjemahan Jepang → Indonesia on-device dengan ML Kit setelah model bahasa diunduh sekali.

## M3.1.3

Model Jepang dan Indonesia diminta secara eksplisit melalui `RemoteModelManager.download()` tanpa pengecekan status model yang dapat menggantung sebelum download dimulai. Setiap download memiliki timeout, dan proses terjemahan juga memiliki timeout agar UI tidak menunggu tanpa batas.

Setelah model tersedia di perangkat, terjemahan dapat berjalan offline tanpa API key. Hasil Indonesia dapat diedit manual. File manga asli tetap tidak diubah.

## Teknologi

- Kotlin
- Jetpack Compose
- Android minSdk 23
- compileSdk/targetSdk 36
- ML Kit Japanese OCR bundled
- ML Kit Translation JP → ID
- Storage Access Framework (`OpenDocument`)

## Build tanpa Android Studio

Project menyertakan GitHub Actions di `.github/workflows/build-apk.yml`.

APK beta M3.1.3 hasil build bernama `Wuvatel-M3.1.3-debug.apk` dan dapat diunduh dari halaman Actions melalui HP.

## Struktur utama

- `app/src/main/java/com/example/mangatranslator/MainActivity.kt`
- `app/src/main/java/com/example/mangatranslator/ocr/JapaneseOcrEngine.kt`
- `app/src/main/java/com/example/mangatranslator/ocr/RegionOcrRefiner.kt`
- `app/src/main/java/com/example/mangatranslator/ocr/TextRegion.kt`
- `app/src/main/java/com/example/mangatranslator/translation/OfflineJapaneseIndonesianTranslator.kt`
- `.github/workflows/build-apk.yml`

## Branch

- `beta` — seluruh pengembangan aktif.
- `main` — milestone yang sudah stabil dan lolos pengujian.

## Aturan fondasi

- File manga asli tidak ditimpa.
- OCR dan translator dibuat modular.
- Satu milestone diuji sebelum melanjutkan ke milestone berikutnya.
