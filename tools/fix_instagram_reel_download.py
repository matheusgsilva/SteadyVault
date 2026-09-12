from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    p.write_text(text.replace(old, new, 1))

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/InstagramPublicAccess.kt',
    '''            val uri = URI(trimmed)\n            val path = uri.path.orEmpty().ifBlank { "/" }\n            URI("https", "www.instagram.com", path, uri.rawQuery, null).toASCIIString()''',
    '''            val uri = URI(trimmed)\n            val path = uri.path.orEmpty().ifBlank { "/" }\n            val query = if (isStoryUrl(trimmed)) uri.rawQuery else null\n            URI("https", "www.instagram.com", path, query, null).toASCIIString()''',
    'canonical Instagram URL'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/SocialMediaDownloader.kt',
    '''        if (InstagramPublicAccess.isStoryUrl(url) && authenticated) {\n            try {\n                return analyzeOnce(context, url, useBrowserSession = true, browserCookieHeader = browserCookieHeader)\n            } catch (error: Throwable) {\n                lastError = error\n            }\n        }''',
    '''        if (authenticated) {\n            try {\n                return analyzeOnce(context, url, useBrowserSession = true, browserCookieHeader = browserCookieHeader)\n            } catch (error: Throwable) {\n                lastError = error\n            }\n        }''',
    'authenticated Instagram first'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/SocialMediaDownloader.kt',
    '''        if (authenticated) {\n            for (candidate in listOf(url) + InstagramPublicAccess.fallbackUrls(url)) {\n                try {\n                    return analyzeOnce(context, candidate, useBrowserSession = true, browserCookieHeader = browserCookieHeader)\n                } catch (error: Throwable) {\n                    lastError = error\n                }\n            }\n        }''',
    '''        if (authenticated) {\n            for (candidate in InstagramPublicAccess.fallbackUrls(url)) {\n                try {\n                    return analyzeOnce(context, candidate, useBrowserSession = true, browserCookieHeader = browserCookieHeader)\n                } catch (error: Throwable) {\n                    lastError = error\n                }\n            }\n        }''',
    'authenticated fallback dedupe'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''            when {\n                embeddedExtractor.isNotBlank() -> resolveSocialMediaForDownload(embeddedExtractor)\n                SocialMediaDownloader.canHandle(pageUrl) -> resolveSocialMediaForDownload(pageUrl)\n                (scan.hasVideoPlayer || internalStream) && direct.none(BrowserVaultDownloader::isLikelyVideoUrl) -> {\n                    resolveSocialMediaForDownload(pageUrl) {\n                        resolvePageMediaForDownload(pageUrl, direct, internalStream, scan.thumbnailUrl)\n                    }\n                }\n                else -> resolvePageMediaForDownload(pageUrl, direct, internalStream, scan.thumbnailUrl)\n            }''',
    '''            val directFallback = { resolvePageMediaForDownload(pageUrl, direct, internalStream, scan.thumbnailUrl) }\n            when {\n                embeddedExtractor.isNotBlank() -> resolveSocialMediaForDownload(embeddedExtractor, directFallback)\n                SocialMediaDownloader.canHandle(pageUrl) -> resolveSocialMediaForDownload(pageUrl, directFallback)\n                (scan.hasVideoPlayer || internalStream) && direct.none(BrowserVaultDownloader::isLikelyVideoUrl) ->\n                    resolveSocialMediaForDownload(pageUrl, directFallback)\n                else -> directFallback()\n            }''',
    'browser direct media fallback'
)
