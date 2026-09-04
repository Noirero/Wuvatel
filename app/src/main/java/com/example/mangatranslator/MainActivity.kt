package com.example.mangatranslator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
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
import com.example.mangatranslator.ui.theme.MangaTranslatorTheme
import kotlinx.coroutines.Dispatchers
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
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var state by remember { mutableStateOf<OcrUiState>(OcrUiState.Empty) }

    DisposableEffect(ocrEngine, regionRefiner) {
        onDispose {
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
        Text("Wuvatel · M2.6", style = MaterialTheme.typography.headlineSmall)
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
            is OcrUiState.Ready -> ResultState(current)
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
private fun ResultState(state: OcrUiState.Ready) {
    var regions by remember(state.uri, state.regions) {
        mutableStateOf(state.regions)
    }
    var editingIndex by remember(state.uri, state.regions) {
        mutableStateOf<Int?>(null)
    }
    var draftText by remember(state.uri, state.regions) {
        mutableStateOf("")
    }

    val currentEditingIndex = editingIndex
    if (currentEditingIndex != null && currentEditingIndex in regions.indices) {
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text("Edit teks Jepang") },
            text = {
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hasil OCR") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draftText.trim().isNotBlank(),
                    onClick = {
                        val updated = regions.toMutableList()
                        updated[currentEditingIndex] = updated[currentEditingIndex].copy(
                            text = draftText.trim(),
                            reviewed = true,
                        )
                        regions = updated
                        editingIndex = null
                    },
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = null }) {
                    Text("Batal")
                }
            },
        )
    }

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
        Text(
            "Ketuk teks untuk memeriksa atau mengedit sebelum diterjemahkan.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        if (regions.isEmpty()) {
            Text("Belum ada teks yang terdeteksi pada gambar ini.")
        } else {
            regions.forEachIndexed { index, region ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingIndex = index
                            draftText = region.text
                        }
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("${index + 1}. ${region.text}")
                    Text(
                        text = if (region.reviewed) "Sudah dicek" else "Perlu cek",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (region.reviewed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (index != regions.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
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
