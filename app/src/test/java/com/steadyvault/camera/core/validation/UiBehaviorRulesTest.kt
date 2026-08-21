package com.steadyvault.camera.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiBehaviorRulesTest {
    @Test
    fun photoProgressNeverEnablesVideoStopState() {
        assertFalse(UiBehaviorRules.isRecordingBusy("Preparando foto…"))
        assertFalse(UiBehaviorRules.isRecordingBusy("Preparando sequência de fotos…"))
        assertTrue(UiBehaviorRules.isPhotoProgress("Capturando foto…"))
    }

    @Test
    fun recordingMessagesRemainBusy() {
        assertTrue(UiBehaviorRules.isRecordingBusy("Preparando 4K UHD 60 FPS…"))
        assertTrue(UiBehaviorRules.isRecordingBusy("Preparando gravação • Automático • 60 FPS…"))
        assertTrue(UiBehaviorRules.isRecordingBusy("Gravando 1080p 120 FPS…"))
        assertTrue(UiBehaviorRules.isRecordingFinalizing("Finalizando vídeo…"))
        assertTrue(UiBehaviorRules.isRecordingBusy("Salvando original no cofre…"))
        assertTrue(UiBehaviorRules.isRecordingFinalizing("Salvando original no cofre…"))
    }

    @Test
    fun doubleTapCyclesThroughUsefulZoomLevels() {
        assertEquals(2.5f, UiBehaviorRules.nextDoubleTapScale(1f, 8f), 0.001f)
        assertEquals(4.5f, UiBehaviorRules.nextDoubleTapScale(2.5f, 8f), 0.001f)
        assertEquals(1f, UiBehaviorRules.nextDoubleTapScale(4.5f, 8f), 0.001f)
    }
}
