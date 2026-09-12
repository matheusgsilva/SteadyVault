from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    p.write_text(text.replace(old, new, 1))

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/BrowserVaultDownloader.kt',
    '''        if (mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true)) return true\n        val clean = lower.substringBefore('#').substringBefore('?')''',
    '''        if (mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true)) return true\n        if (isInstagramVideoCdnUrl(lower)) return true\n        val clean = lower.substringBefore('#').substringBefore('?')''',
    'recognize Instagram video CDN as media'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/BrowserVaultDownloader.kt',
    '''        return when {\n            extension in VIDEO_EXTENSIONS ||\n                lower.contains("googlevideo.com/videoplayback") ||''',
    '''        return when {\n            extension in VIDEO_EXTENSIONS ||\n                isInstagramVideoCdnUrl(lower) ||\n                lower.contains("googlevideo.com/videoplayback") ||''',
    'recognize Instagram video CDN mime'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/BrowserVaultDownloader.kt',
    '''    private fun isLikelyVideoHost(url: String): Boolean {''',
    '''    private fun isInstagramVideoCdnUrl(url: String): Boolean {\n        val lower = url.lowercase(Locale.US)\n        if (!lower.contains("cdninstagram.com") && !lower.contains("fbcdn.net")) return false\n        return lower.contains("/o1/v/t16/") ||\n            lower.contains("/o1/v/t2/") ||\n            lower.contains("/v/t16.") ||\n            lower.contains("/v/t2.") ||\n            lower.contains("mime=video") ||\n            lower.contains("mime%3dvideo")\n    }\n\n    private fun isLikelyVideoHost(url: String): Boolean {''',
    'add Instagram video CDN matcher'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''    private fun scanPageMediaForDownload() {''',
    '''    private fun scanPageMediaForDownload(directOnly: Boolean = false) {''',
    'direct-only scan parameter'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''            val directFallback = { resolvePageMediaForDownload(pageUrl, direct, internalStream, scan.thumbnailUrl) }\n            when {\n                embeddedExtractor.isNotBlank() -> resolveSocialMediaForDownload(embeddedExtractor, directFallback)\n                SocialMediaDownloader.canHandle(pageUrl) -> resolveSocialMediaForDownload(pageUrl, directFallback)''',
    '''            val directFallback = { resolvePageMediaForDownload(pageUrl, direct, internalStream, scan.thumbnailUrl) }\n            when {\n                directOnly -> directFallback()\n                InstagramPublicAccess.isInstagramUrl(pageUrl) && direct.any(BrowserVaultDownloader::isLikelyVideoUrl) -> directFallback()\n                embeddedExtractor.isNotBlank() -> resolveSocialMediaForDownload(embeddedExtractor, directFallback)\n                SocialMediaDownloader.canHandle(pageUrl) -> resolveSocialMediaForDownload(pageUrl, directFallback)''',
    'prefer current Instagram WebView video'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''                }.onFailure {\n                    Toast.makeText(this, it.message ?: "Falha ao baixar vídeo", Toast.LENGTH_LONG).show()\n                }\n            }\n        }\n    }\n\n    private fun resolvePageMediaForDownload(''',
    '''                }.onFailure {\n                    if (InstagramPublicAccess.isInstagramUrl(media.sourceUrl)) {\n                        Toast.makeText(this, "Tentando o vídeo já carregado no navegador…", Toast.LENGTH_SHORT).show()\n                        scanPageMediaForDownload(directOnly = true)\n                    } else {\n                        Toast.makeText(this, it.message ?: "Falha ao baixar vídeo", Toast.LENGTH_LONG).show()\n                    }\n                }\n            }\n        }\n    }\n\n    private fun resolvePageMediaForDownload(''',
    'retry failed Instagram download from WebView'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''        if (!BrowserVaultDownloader.isLikelyMediaUrl(url, mimeType) && BrowserMediaExtractor.isExtractorPage(url)) {\n            if (SocialMediaDownloader.canHandle(url)) resolveSocialMediaForDownload(url)\n            else resolvePageMediaForDownload(url, emptyList(), sawInternalStream = false)\n            return\n        }''',
    '''        if (!BrowserVaultDownloader.isLikelyMediaUrl(url, mimeType) && BrowserMediaExtractor.isExtractorPage(url)) {\n            if (InstagramPublicAccess.isInstagramUrl(url)) scanPageMediaForDownload()\n            else if (SocialMediaDownloader.canHandle(url)) resolveSocialMediaForDownload(url)\n            else resolvePageMediaForDownload(url, emptyList(), sawInternalStream = false)\n            return\n        }''',
    'route Instagram page downloads through WebView scan'
)
