# M1 acceptance checklist

M1 is considered complete only when these checks pass on a real Android device/emulator:

- App opens without requesting broad storage permission.
- "Pilih 1 halaman manga" opens Android's document picker.
- JPG/JPEG, PNG, and WebP can be selected.
- Selected image is displayed without modifying the source file.
- Japanese OCR runs locally using the bundled model.
- Detected text lines are listed below the image.
- Red bounding boxes align with the detected text on the displayed bitmap.
- Selecting another image replaces the previous result cleanly.
- OCR failure shows an error instead of crashing the app.

Out of scope for M1: translation, cleaning/inpainting, typesetting, ZIP/CBZ, Room persistence, AI provider.
