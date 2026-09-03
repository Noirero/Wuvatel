# Build dari HP tanpa Android Studio

Project ini disiapkan supaya APK dapat dibuat oleh GitHub Actions. HP hanya dipakai untuk mengelola repository dan mengunduh hasil build.

## Cara paling mudah

1. Buka repository Wuvatel di GitHub.
2. Pastikan branch yang dipilih adalah `beta` untuk pengembangan.
3. Buka tab **Actions** di repository.
4. Pilih workflow **Build Debug APK**.
5. Tekan **Run workflow** jika build belum berjalan otomatis.
6. Setelah build berstatus hijau, buka hasil run tersebut.
7. Di bagian **Artifacts**, unduh `MangaTranslator-M1-debug.apk`.
8. Instal APK di HP dan uji OCR dengan satu halaman manga Jepang.

Workflow juga berjalan otomatis setiap ada perubahan yang di-push ke branch `main` atau `beta`.

## Yang dikerjakan GitHub

- JDK 17
- Android SDK API 37
- Android SDK Build Tools 36.0.0
- Gradle 9.6.0
- `:app:assembleDebug`
- Mengunggah APK hasil build sebagai artifact

## Catatan

APK dari workflow ini adalah **debug APK** untuk pengujian M1, bukan APK release final.
