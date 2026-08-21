package com.steadyvault.camera.core.camera

/**
 * Interpreta metadados de OIS sem transformar uma chave opcional ausente em uma
 * negativa definitiva. A parte Android apenas coleta os valores da câmera lógica
 * e das lentes físicas; esta política permanece pura e testável.
 */
object OisSupportPolicy {
    enum class Source {
        LOGICAL_METADATA,
        PHYSICAL_METADATA,
        REQUEST_KEY_FALLBACK,
        NONE
    }

    data class Decision(
        val supported: Boolean,
        val source: Source
    )

    fun resolve(
        logicalModes: IntArray?,
        physicalModes: List<IntArray?>,
        logicalRequestAvailable: Boolean,
        physicalOverrideAvailable: Boolean,
        onMode: Int
    ): Decision {
        val validLogicalModes = logicalModes?.takeIf { it.isNotEmpty() }
        return when {
            validLogicalModes?.contains(onMode) == true ->
                Decision(true, Source.LOGICAL_METADATA)

            physicalModes.any { it?.contains(onMode) == true } &&
                (logicalRequestAvailable || physicalOverrideAvailable) ->
                Decision(true, Source.PHYSICAL_METADATA)

            // Há HALs que publicam apenas OFF no array da câmera lógica, embora a
            // mesma sessão aceite ON (ou encaminhe o controle à lente física).
            // A presença da chave de request mantém o OIS testável; o resultado
            // real da sessão passa a ser a confirmação final.
            logicalRequestAvailable ->
                Decision(true, Source.REQUEST_KEY_FALLBACK)

            else ->
                Decision(false, Source.NONE)
        }
    }

}
