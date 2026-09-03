# Wuvatel — M1

Starter Android untuk fondasi aplikasi penerjemah manga mandiri.

## Target M1

1. Pilih satu gambar manga (JPG/PNG/WebP).
2. Tampilkan gambar tanpa mengubah file asli.
3. Jalankan OCR Jepang secara on-device dengan ML Kit Japanese Text Recognition.
4. Tampilkan bounding box di atas region teks yang ditemukan.
5. Tampilkan hasil teks OCR di bawah gambar.

Belum ada translation, image cleaning, typesetting, ZIP/CBZ, atau AI pada milestone ini.

## Teknologi

- Kotlin
- Jetpack Compose
- Android minSdk 23
- compileSdk/targetSdk 36
- ML Kit Japanese OCR bundled
- Storage Access Framework (`OpenDocument`)

## Build tanpa Android Studio

Project ini menyertakan GitHub Actions di:

`.github/workflows/build-apk.yml`

Artinya APK debug dapat dibuat di server GitHub dan hasilnya diunduh dari HP. Petunjuk lengkap ada di `BUILD_FROM_PHONE.md`.

## Struktur utama

- `app/src/main/java/com/example/mangatranslator/MainActivity.kt`
- `app/src/main/java/com/example/mangatranslator/ocr/JapaneseOcrEngine.kt`
- `app/src/main/java/com/example/mangatranslator/ocr/TextRegion.kt`
- `.github/workflows/build-apk.yml`
- `M1_ACCEPTANCE.md`

## Branch

- `beta` — seluruh pengembangan aktif.
- `main` — milestone yang sudah stabil dan lolos pengujian.

## Aturan fondasi

- File manga asli tidak ditimpa.
- OCR dibuat modular.
- Fokus M1 hanya satu gambar + OCR + bounding box.
