package com.steadyvault.camera.ui.vault

internal object VaultImportSummary {
    data class Failure(val name: String, val reason: String)

    fun popupMessage(imported: Int, total: Int, failures: List<Failure>, skipped: Int = 0, failureCount: Int = failures.size): String = buildString {
        val actualFailures = failureCount.coerceAtLeast(failures.size)
        val processed = (imported + skipped + actualFailures).coerceAtMost(total)
        append("Importados: ").append(imported).append('/').append(total).append('.')
        if (skipped > 0) append("\nRepetidos ignorados: ").append(skipped).append('.')
        if (actualFailures > 0) append("\nFalhas: ").append(actualFailures).append('.')
        if (processed < total) append("\nNão processados: ").append(total - processed).append('.')
        if (actualFailures <= 0 || failures.isEmpty()) return@buildString

        val visible = failures.take(MAX_VISIBLE_FAILURES)
        append("\n\nPrimeiras ").append(visible.size).append(" falhas:")
        visible.forEachIndexed { index, failure ->
            append('\n').append(index + 1).append(". ").append(failure.name.take(MAX_NAME_CHARS))
            if (failure.reason.isNotBlank()) append(" — ").append(readableReason(failure.reason))
        }
        if (actualFailures > visible.size) {
            append("\n\n+").append(actualFailures - visible.size).append(" falha(s) registrada(s) em Configurações > Diagnósticos.")
        }
    }

    private fun readableReason(reason: String): String {
        val value = reason.trim()
        return when {
            value.contains("EIO", ignoreCase = true) || value.contains("I/O error", ignoreCase = true) || value.contains("Input/output", ignoreCase = true) ->
                "falha de leitura da mídia/origem após novas tentativas"
            value.contains("is child of", ignoreCase = true) || value.contains("determine if", ignoreCase = true) ->
                "item inacessível pelo provedor de arquivos do Android"
            else -> value.take(MAX_REASON_CHARS)
        }
    }

    private const val MAX_VISIBLE_FAILURES = 10
    private const val MAX_NAME_CHARS = 90
    private const val MAX_REASON_CHARS = 140
}
