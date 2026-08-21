package com.steadyvault.camera.processing.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiImprovementPolicyTest {
    @Test fun stableVideoDoesNotNeedReencode() {
        assertFalse(AiImprovementPolicy.decide(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.02f, false).needsImprovement)
    }

    @Test fun yellowCastNeedsCorrection() {
        assertTrue(AiImprovementPolicy.decide(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.7f, 0.02f, false).needsImprovement)
    }

    @Test fun cadenceProblemNeedsRepair() {
        assertTrue(AiImprovementPolicy.decide(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.02f, true).needsImprovement)
    }

    @Test fun motionIsNotMistakenForRecoverableBlur() {
        assertFalse(AiImprovementPolicy.decide(0.70f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.18f, false).needsImprovement)
    }
}
