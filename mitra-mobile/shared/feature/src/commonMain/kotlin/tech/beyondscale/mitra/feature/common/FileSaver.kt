package tech.beyondscale.mitra.feature.common

import androidx.compose.runtime.staticCompositionLocalOf

enum class SaveResult { Saved, Cancelled, Failed, Unsupported }

/** Saves a text file where the user picks (Android: the system "Save to" picker). Provided by the host app. */
fun interface FileSaver {
    suspend fun save(fileName: String, mimeType: String, content: String): SaveResult
}

val LocalFileSaver = staticCompositionLocalOf { FileSaver { _, _, _ -> SaveResult.Unsupported } }
