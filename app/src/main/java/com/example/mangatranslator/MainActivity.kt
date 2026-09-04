package com.example.mangatranslator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mangatranslator.ocr.JapaneseOcrEngine
import com.example.mangatranslator.ocr.RegionOcrRefiner
import com.example.mangatranslator.ocr.TextRegion
import com.example.mangatranslator.translation.OfflineJapaneseIndonesianTranslator
import com.example.mangatranslator.ui.theme.MangaTranslatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MangaTranslatorTheme {
                MangaOcrScreen()
            }
        }
    }
}

private sealed interface OcrUiState {
    data object Empty : OcrUiState
    data object Loading : OcrUiState
    data class Ready(
        val uri: Uri,
        val bitmap: Bitmap,
        val regions: List<TextRegion>,
    ) : OcrUiState
    data class Error(val message: String) : OcrUiState
}

@Composable
private fun MangaOcrScreen() {
    val context = LocalContext.current
    val ocrEngine = remember { JapaneseOcrEngine() }
    val regionRefiner = remember { RegionOcrRefiner() }
    val translator = remember { OfflineJapaneseIndonesianTranslator() }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var state by remember { mutableStateOf<OcrUiState>(OcrUiState.Empty) }

    DisposableEffect(ocrEngine, regionRefiner, translator) {
        onDispose {
            translator.close()
            regionRefiner.close()
            ocrEngine.close()
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            selectedUri = uri
        }
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        state = OcrUiState.Loading
        state = try {
            val bitmap = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: error("Gambar tidak dapat dibaca")
            }
            val groupedRegions = ocrEngine.recognize(bitmap)
            val refinedRegions = regionRefiner.refine(bitmap, groupedRegions)
            OcrUiState.Ready(uri, bitmap, refinedRegions)
        } catch (t: Throwable) {
            OcrUiState.Error(t.message ?: "OCR gagal")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Wuvatel · M3.2.2", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Pilih 1 halaman manga")
        }

        Spacer(Modifier.height(12.dp))

        when (val current = state) {
            OcrUiState.Empty -> EmptyState()
            OcrUiState.Loading -> LoadingState()
            is OcrUiState.Error -> ErrorState(current.message)
            is OcrUiState.Ready -> ResultState(current, translator)
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Pilih JPG, PNG, atau WebP untuk mulai OCR Jepang.")
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text("Membandingkan ulang hasil OCR tiap region…")
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Gagal: $message", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ResultState(
    state: OcrUiState.Ready,
    translator: OfflineJapaneseIndonesianTranslator,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regions by remember(state.uri, state.regions) {
        mutableStateOf(state.regions)
    }
    var editingJapaneseIndex by remember(state.uri, state.regions) {
        mutableStateOf<Int?>(null)
    }
    var japaneseDraft by remember(state.uri, state.regions) {
        mutableStateOf("")
    }
    var editingTranslationIndex by remember(state.uri, state.regions) {
        mutableStateOf<Int?>(null)
    }
    var translationDraft by remember(state.uri, state.regions) {
        mutableStateOf("")
    }
    var translationBusy by remember(state.uri) {
        mutableStateOf(false)
    }
    var activeRetranslateIndex by remember(state.uri) {
        mutableStateOf<Int?>(null)
    }
    var translationError by remember(state.uri) {
        mutableStateOf<String?>(null)
    }
    var translationStatus by remember(state.uri) {
        mutableStateOf("Belum dimulai")
    }
    var modelStatus by remember(state.uri) {
        mutableStateOf("Belum diverifikasi")
    }
    var diagnosticLog by remember(state.uri) {
        mutableStateOf<List<String>>(emptyList())
    }
    var showFullDiagnosticLog by remember(state.uri) {
        mutableStateOf(false)
    }

    fun appendDiagnostic(line: String) {
        diagnosticLog = (diagnosticLog + line).takeLast(80)
    }

    val currentJapaneseIndex = editingJapaneseIndex
    if (currentJapaneseIndex != null && currentJapaneseIndex in regions.indices) {
        AlertDialog(
            onDismissRequest = { editingJapaneseIndex = null },
            title = { Text("Edit teks Jepang") },
            text = {
                OutlinedTextField(
                    value = japaneseDraft,
                    onValueChange = { japaneseDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hasil OCR") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = japaneseDraft.trim().isNotBlank(),
                    onClick = {
                        val updated = regions.toMutableList()
                        val old = updated[currentJapaneseIndex]
                        val newText = japaneseDraft.trim()
                        val changed = newText != old.text
                        updated[currentJapaneseIndex] = old.copy(
                            text = newText,
                            reviewed = true,
                            translation = if (changed) null else old.translation,
                            translationReviewed = if (changed) false else old.translationReviewed,
                        )
                        regions = updated
                        editingJapaneseIndex = null
                    },
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingJapaneseIndex = null }) {
                    Text("Batal")
                }
            },
        )
    }

    val currentTranslationIndex = editingTranslationIndex
    if (currentTranslationIndex != null && currentTranslationIndex in regions.indices) {
        AlertDialog(
            onDismissRequest = { editingTranslationIndex = null },
            title = { Text("Edit terjemahan Indonesia") },
            text = {
                OutlinedTextField(
                    value = translationDraft,
                    onValueChange = { translationDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Terjemahan") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = translationDraft.trim().isNotBlank(),
                    onClick = {
                        val updated = regions.toMutableList()
                        updated[currentTranslationIndex] = updated[currentTranslationIndex].copy(
                            translation = translationDraft.trim(),
                            translationReviewed = true,
                        )
                        regions = updated
                        editingTranslationIndex = null
                    },
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTranslationIndex = null }) {
                    Text("Batal")
                }
            },
        )
    }

    val missingTranslations = regions.count { it.translation.isNullOrBlank() }
    val prepareReady = diagnosticLog.any { it.contains("[PREPARE-DONE]") }
    val cacheReady = diagnosticLog.any { it.contains("[CACHE-PROBE-DONE]") || it.contains("[CACHE-PROBE-FINAL-DONE]") }
    val modelsReady = diagnosticLog.any { it.contains("[READY]") }
    val translationFinished = diagnosticLog.any { it.contains("[UI] Semua region yang kosong selesai diterjemahkan") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MangaImageWithBoxes(
            bitmap = state.bitmap,
            regions = regions,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
        )

        Text("Terdeteksi: ${regions.size} kelompok teks")
        Text("Belum dicek: ${regions.count { !it.reviewed }}")
        Text("Perlu perhatian: ${regions.count(::needsReviewAttention)}")
        Text(
            "Ketuk teks Jepang untuk memeriksa atau mengedit sebelum diterjemahkan.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Gaya: Natural sederhana · offline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        Button(
            enabled = regions.isNotEmpty() && missingTranslations > 0 && !translationBusy,
            onClick = {
                scope.launch {
                    translationBusy = true
                    activeRetranslateIndex = null
                    translationError = null
                    diagnosticLog = emptyList()
                    showFullDiagnosticLog = false
                    appendDiagnostic("[UI] Mulai sesi M3.2.2 · ML Kit retry diagnostics")
                    translationStatus = "Menguji cache model lokal…"
                    modelStatus = "Belum siap"
                    try {
                        translator.ensureModel(
                            onStatus = { status -> translationStatus = status },
                            onLog = ::appendDiagnostic,
                        )
                        modelStatus = "Siap"
                        appendDiagnostic("[UI] Model siap; mulai menerjemahkan ${updatedCount(regions)} region")
                        val updated = regions.toMutableList()
                        for (index in updated.indices) {
                            if (updated[index].translation.isNullOrBlank()) {
                                translationStatus = "Menerjemahkan ${index + 1}/${updated.size}…"
                                val translated = translator.translate(
                                    text = updated[index].text,
                                    onLog = ::appendDiagnostic,
                                )
                                updated[index] = updated[index].copy(
                                    translation = translated,
                                    translationReviewed = false,
                                )
                                regions = updated.toList()
                            }
                        }
                        translationStatus = "Selesai"
                        appendDiagnostic("[UI] Semua region yang kosong selesai diterjemahkan")
                    } catch (t: Throwable) {
                        val detail = translator.diagnosticMessage(t)
                        translationError = t.message ?: detail
                        translationStatus = "Gagal"
                        appendDiagnostic("[ERROR] $detail")
                    } finally {
                        translationBusy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    translationBusy -> "Menyiapkan / menerjemahkan…"
                    missingTranslations == 0 -> "Semua sudah diterjemahkan"
                    else -> "Terjemahkan JP → ID ($missingTranslations)"
                },
            )
        }

        if (translationBusy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(translationStatus)
            }
        } else if (translationStatus != "Belum dimulai") {
            Text(
                "Terjemahan: $translationStatus · Model offline: $modelStatus",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        translationError?.let { message ->
            Text(
                "Terjemahan gagal: $message",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (diagnosticLog.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Status teknis", style = MaterialTheme.typography.titleSmall)
            if (cacheReady) {
                Text("Cache translator: terverifikasi", style = MaterialTheme.typography.bodySmall)
            }
            if (prepareReady) {
                Text("Model: prepare/download selesai", style = MaterialTheme.typography.bodySmall)
            }
            if (modelsReady) {
                Text("Model JP → ID: siap", style = MaterialTheme.typography.bodySmall)
            }
            if (translationFinished) {
                Text("Proses: selesai", style = MaterialTheme.typography.bodySmall)
            }
            if (!prepareReady && !cacheReady && !modelsReady && !translationFinished && translationBusy) {
                Text("Pemeriksaan sedang berjalan…", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showFullDiagnosticLog = !showFullDiagnosticLog }) {
                    Text(if (showFullDiagnosticLog) "Sembunyikan detail" else "Lihat detail")
                }
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Wuvatel M3.2.2 diagnostic log",
                                diagnosticLog.joinToString("\n"),
                            ),
                        )
                    },
                ) {
                    Text("Salin log")
                }
            }

            if (showFullDiagnosticLog) {
                Text(
                    "Detail diagnostik hanya diperlukan saat terjadi masalah.",
                    style = MaterialTheme.typography.bodySmall,
                )
                diagnosticLog.forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        if (regions.isEmpty()) {
            Text("Belum ada teks yang terdeteksi pada gambar ini.")
        } else {
            regions.forEachIndexed { index, region ->
                val needsAttention = needsReviewAttention(region)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editingJapaneseIndex = index
                                japaneseDraft = region.text
                            }
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("${index + 1}. Jepang: ${region.text}")
                        Text(
                            text = when {
                                region.reviewed -> "Sudah dicek"
                                needsAttention -> "Perlu cek · hasil perlu perhatian"
                                else -> "Perlu cek"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (region.reviewed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }

                    val translated = region.translation
                    if (translated.isNullOrBlank()) {
                        Text(
                            "Indonesia: belum diterjemahkan",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingTranslationIndex = index
                                    translationDraft = translated
                                }
                                .padding(bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("Indonesia: $translated")
                            Text(
                                text = if (region.translationReviewed) {
                                    "Terjemahan sudah diedit"
                                } else {
                                    "Hasil otomatis · Natural sederhana · ketuk untuk edit"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }

                    if (region.reviewed) {
                        TextButton(
                            enabled = !translationBusy,
                            onClick = {
                                scope.launch {
                                    val sourceText = regions.getOrNull(index)?.text ?: return@launch
                                    translationBusy = true
                                    activeRetranslateIndex = index
                                    translationError = null
                                    diagnosticLog = emptyList()
                                    showFullDiagnosticLog = false
                                    appendDiagnostic("[UI] M3.2.2 · terjemahkan ulang region ${index + 1}")
                                    translationStatus = "Menyiapkan region ${index + 1}…"
                                    try {
                                        translator.ensureModel(
                                            onStatus = { status -> translationStatus = status },
                                            onLog = ::appendDiagnostic,
                                        )
                                        modelStatus = "Siap"
                                        translationStatus = "Menerjemahkan ulang region ${index + 1}…"
                                        val newTranslation = translator.translate(
                                            text = sourceText,
                                            onLog = ::appendDiagnostic,
                                        )
                                        val updated = regions.toMutableList()
                                        if (index in updated.indices && updated[index].text == sourceText) {
                                            updated[index] = updated[index].copy(
                                                translation = newTranslation,
                                                translationReviewed = false,
                                            )
                                            regions = updated
                                            translationStatus = "Selesai"
                                            appendDiagnostic("[UI] Region ${index + 1} selesai diterjemahkan ulang")
                                        } else {
                                            translationStatus = "Dibatalkan karena teks berubah"
                                            appendDiagnostic("[UI] Hasil region ${index + 1} diabaikan karena teks Jepang berubah")
                                        }
                                    } catch (t: Throwable) {
                                        val detail = translator.diagnosticMessage(t)
                                        translationError = t.message ?: detail
                                        translationStatus = "Gagal"
                                        appendDiagnostic("[ERROR] $detail")
                                    } finally {
                                        activeRetranslateIndex = null
                                        translationBusy = false
                                    }
                                }
                            },
                        ) {
                            Text(
                                when {
                                    activeRetranslateIndex == index -> "Menerjemahkan ulang…"
                                    translated.isNullOrBlank() -> "Terjemahkan region ini"
                                    else -> "Terjemahkan ulang region ini"
                                },
                            )
                        }
                    }
                }

                if (index != regions.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun updatedCount(regions: List<TextRegion>): Int =
    regions.count { it.translation.isNullOrBlank() }

private fun needsReviewAttention(region: TextRegion): Boolean {
    if (region.reviewed) return false

    val japanese = region.text.trim()
    if (japanese.isBlank()) return true
    if (japanese.any { it == '\uFFFD' || it == '|' || it == '｜' || it == '¦' }) return true
    if (japanese.count { it == '(' } != japanese.count { it == ')' }) return true
    if (japanese.count { it == '[' } != japanese.count { it == ']' }) return true

    val translated = region.translation?.trim().orEmpty()
    if (translated.isBlank()) return false

    val hasKanji = japanese.any { char ->
        char.code in 0x3400..0x4DBF ||
            char.code in 0x4E00..0x9FFF ||
            char.code in 0xF900..0xFAFF
    }
    val looksLikeSingleRomanizedToken =
        Regex("^[A-Za-z][A-Za-z'’-]{3,}$").matches(translated)
    val containsUnexpectedUppercaseWord =
        Regex("\\b[A-Z]{3,}\\b").containsMatchIn(translated)

    return (hasKanji && looksLikeSingleRomanizedToken) || containsUnexpectedUppercaseWord
}

@Composable
private fun MangaImageWithBoxes(
    bitmap: Bitmap,
    regions: List<TextRegion>,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Halaman manga",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            Canvas(Modifier.fillMaxSize()) {
                val imageWidth = bitmap.width.toFloat()
                val imageHeight = bitmap.height.toFloat()
                if (imageWidth <= 0f || imageHeight <= 0f) return@Canvas

                val scale = minOf(size.width / imageWidth, size.height / imageHeight)
                val drawnWidth = imageWidth * scale
                val drawnHeight = imageHeight * scale
                val offsetX = (size.width - drawnWidth) / 2f
                val offsetY = (size.height - drawnHeight) / 2f

                regions.forEach { region ->
                    val rect = region.boundingBox
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(
                            x = offsetX + rect.left * scale,
                            y = offsetY + rect.top * scale,
                        ),
                        size = Size(
                            width = rect.width() * scale,
                            height = rect.height() * scale,
                        ),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}
