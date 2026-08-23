package com.steadyvault.camera.storage.vault

import java.io.File
import java.util.Locale

/** Centraliza formatos aceitos pelos cofres, importação, thumbnails e visualização. */
object VaultMediaFormats {
    val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "3gp", "3gpp", "avi", "mpg", "mpeg", "mts", "m2ts", "ts", "wmv"
    )
    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "webp", "heic", "heif", "gif", "bmp", "avif", "tif", "tiff"
    )
    val ALL_EXTENSIONS = VIDEO_EXTENSIONS + IMAGE_EXTENSIONS

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    fun isSupported(name: String, mime: String = ""): Boolean {
        val ext = extensionOf(name)
        if (ext in ALL_EXTENSIONS) return true
        val normalizedMime = mime.lowercase(Locale.US)
        return normalizedMime in SUPPORTED_MIMES
    }

    fun isVideoExtension(extension: String): Boolean = extension.lowercase(Locale.US) in VIDEO_EXTENSIONS
    fun isImageExtension(extension: String): Boolean = extension.lowercase(Locale.US) in IMAGE_EXTENSIONS

    fun mimeFor(extension: String, video: Boolean = false): String = when (extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp", "3gpp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "mpg", "mpeg" -> "video/mpeg"
        "mts", "m2ts", "ts" -> "video/mp2t"
        "wmv" -> "video/x-ms-wmv"
        "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "tif", "tiff" -> "image/tiff"
        else -> if (video) "video/*" else "image/*"
    }

    fun extensionFromMime(mime: String, fallback: String): String = when (mime.lowercase(Locale.US)) {
        "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/gif" -> "gif"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        "image/avif" -> "avif"
        "image/tiff", "image/tif" -> "tif"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/3gpp", "video/3gpp2" -> "3gp"
        "video/x-msvideo" -> "avi"
        "video/mpeg" -> "mpg"
        "video/mp2t" -> "ts"
        "video/x-ms-wmv" -> "wmv"
        else -> fallback
    }

    fun importFileName(displayName: String?, mime: String, fallbackPrefix: String = "Midia"): String {
        val rawName = displayName.orEmpty().trim()
        val rawExtension = extensionOf(rawName)
        val extension = when {
            rawExtension in ALL_EXTENSIONS -> rawExtension
            mime.startsWith("image/", ignoreCase = true) -> extensionFromMime(mime, "jpg")
            mime.startsWith("video/", ignoreCase = true) -> extensionFromMime(mime, "mp4")
            else -> "jpg"
        }
        val name = rawName.ifBlank { "${fallbackPrefix}_${System.currentTimeMillis()}.$extension" }
        val nameExtension = extensionOf(name)
        val withExtension = when {
            nameExtension.isBlank() -> "$name.$extension"
            nameExtension in ALL_EXTENSIONS -> name
            else -> name.substringBeforeLast('.', name).ifBlank { fallbackPrefix } + ".$extension"
        }
        return sanitizeName(withExtension)
    }

    fun sanitizeName(value: String): String = value
        .replace(Regex("[\\/:*?\"<>|]"), "_")
        .take(120)
        .trim()
        .ifBlank { "Midia_${System.currentTimeMillis()}.mp4" }

    fun hasEquivalentFile(directory: File, displayName: String, sizeBytes: Long, mime: String = ""): Boolean {
        val safe = importFileName(displayName, mime, "Importado")
        val exact = File(directory, safe)
        if (!exact.isFile || sizeBytes <= 0L) return false
        return exact.length() == sizeBytes
    }

    private val SUPPORTED_MIMES = ALL_EXTENSIONS.mapTo(mutableSetOf()) { mimeFor(it, it in VIDEO_EXTENSIONS) }.apply {
        add("image/jpg")
        add("image/pjpeg")
        add("image/x-ms-bmp")
        add("video/3gpp2")
    }
}
