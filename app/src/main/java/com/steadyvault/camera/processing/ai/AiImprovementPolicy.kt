package com.steadyvault.camera.processing.ai

/** Política determinística usada para impedir recodificação sem evidência de ganho. */
object AiImprovementPolicy {
    data class Decision(val needsImprovement: Boolean, val issueCount: Int)

    fun decide(
        blur: Float,
        noise: Float,
        compression: Float,
        underExposure: Float,
        overExposure: Float,
        yellowCast: Float,
        motion: Float,
        cadenceProblems: Boolean
    ): Decision {
        val recoverableBlur = blur >= 0.62f && motion <= 0.08f
        val issues = listOf(
            recoverableBlur,
            noise >= 0.48f,
            compression >= 0.48f,
            underExposure >= 0.52f,
            overExposure >= 0.52f,
            yellowCast >= 0.42f,
            cadenceProblems
        ).count { it }
        return Decision(needsImprovement = issues > 0, issueCount = issues)
    }
}
