package com.steadyvault.camera.ui.browser

internal object BrowserSharedLinkParser {
    private val httpUrl = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private val trailingPunctuation = setOf('.', ',', ';', ':', ')', ']', '}')

    fun firstHttpUrl(text: CharSequence?): String? = httpUrl.find(text?.toString().orEmpty())
        ?.value
        ?.trimEnd { it in trailingPunctuation }
        ?.takeIf { it.isNotBlank() }
}
