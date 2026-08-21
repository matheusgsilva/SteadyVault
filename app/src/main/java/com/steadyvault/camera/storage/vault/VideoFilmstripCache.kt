package com.steadyvault.camera.storage.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Cache leve dos frames usados pelo corte; nunca contém a mídia original. */
object VideoFilmstripCache {
    fun loadOrCreate(
        context: Context,
        file: File,
        durationMs: Int,
        frameCount: Int,
        maximumSide: Int,
        generator: () -> List<Bitmap>
    ): List<Bitmap> {
        val directory = cacheDirectory(context, file, durationMs, frameCount, maximumSide)
        readFrames(directory, frameCount).takeIf { it.size == frameCount }?.let { return it }
        directory.deleteRecursively()
        val generated = generator()
        if (generated.size != frameCount) return generated
        runCatching { persistFrames(directory, generated) }
            .onFailure { directory.deleteRecursively() }
        return generated
    }

    fun remove(context: Context, file: File) {
        val directory = root(context)
        val prefix = pathKey(file) + "_"
        directory.listFiles { candidate -> candidate.isDirectory && candidate.name.startsWith(prefix) }
            .orEmpty()
            .forEach { candidate -> candidate.deleteRecursively() }
    }

    private fun readFrames(directory: File, frameCount: Int): List<Bitmap> {
        if (!directory.isDirectory) return emptyList()
        val result = ArrayList<Bitmap>(frameCount)
        repeat(frameCount) { index ->
            val bitmap = BitmapFactory.decodeFile(frameFile(directory, index).absolutePath)
            if (bitmap == null) {
                result.forEach { if (!it.isRecycled) it.recycle() }
                return emptyList()
            }
            result += bitmap
        }
        return result
    }

    private fun persistFrames(directory: File, frames: List<Bitmap>) {
        check(directory.mkdirs() || directory.isDirectory) { "Não foi possível criar o cache do corte" }
        frames.forEachIndexed { index, bitmap ->
            val destination = frameFile(directory, index)
            val pending = File(directory, destination.name + ".pending")
            FileOutputStream(pending).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
                output.fd.sync()
            }
            check(pending.renameTo(destination)) { "Não foi possível concluir o frame do corte" }
        }
    }

    private fun cacheDirectory(
        context: Context,
        file: File,
        durationMs: Int,
        frameCount: Int,
        maximumSide: Int
    ): File {
        val version = sha256("${file.length()}:${file.lastModified()}:$durationMs:$frameCount:$maximumSide").take(20)
        return File(root(context), "${pathKey(file)}_$version")
    }

    private fun pathKey(file: File): String = sha256(file.absoluteFile.normalize().path).take(20)

    private fun root(context: Context): File = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)

    private fun frameFile(directory: File, index: Int): File = File(directory, "frame_$index.jpg")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val CACHE_DIRECTORY = "SteadyVaultFilmstrips"
    private const val JPEG_QUALITY = 88
}
