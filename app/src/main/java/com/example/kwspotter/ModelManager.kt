package com.example.kwspotter

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the offline Vosk Russian model (vosk-model-small-ru-0.22, ~45 MB), which ships
 * bundled inside the APK under `assets/vosk-model-small-ru-0.22`, into app-private storage
 * the first time it is needed. The model is already part of the APK, so nothing is ever
 * downloaded over the network — this only unpacks local files, and it happens once (the
 * unpacked copy is reused afterwards, checked via [isModelReady]).
 */
object ModelManager {

    private const val ASSET_MODEL_PATH = "vosk-model-small-ru-0.22"
    private const val MODEL_DIR_NAME = "vosk-model-small-ru-0.22"

    sealed class Progress {
        object Unpacking : Progress()
        object Done : Progress()
        data class Error(val message: String) : Progress()
    }

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    /** A representative file that only exists once the model was fully unpacked. */
    fun isModelReady(context: Context): Boolean =
        File(modelDir(context), "am/final.mdl").exists()

    suspend fun ensureModel(context: Context, onProgress: (Progress) -> Unit) {
        if (isModelReady(context)) {
            onProgress(Progress.Done)
            return
        }

        onProgress(Progress.Unpacking)
        withContext(Dispatchers.IO) {
            try {
                copyAssetDir(context.assets, ASSET_MODEL_PATH, modelDir(context))

                if (!isModelReady(context)) {
                    throw IllegalStateException("Model files missing after unpacking")
                }
                onProgress(Progress.Done)
            } catch (e: Exception) {
                modelDir(context).deleteRecursively()
                onProgress(Progress.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Recursively copies the [assetPath] folder that ships inside `assets/` into [targetDir]
     * on internal storage, so native Vosk code can memory-map the files directly from disk.
     */
    private fun copyAssetDir(assets: AssetManager, assetPath: String, targetDir: File) {
        val children = assets.list(assetPath)

        if (children.isNullOrEmpty()) {
            // Leaf node: assetPath refers to a file, not a directory.
            targetDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output -> input.copyTo(output) }
            }
            return
        }

        targetDir.mkdirs()
        for (child in children) {
            copyAssetDir(assets, "$assetPath/$child", File(targetDir, child))
        }
    }
}
