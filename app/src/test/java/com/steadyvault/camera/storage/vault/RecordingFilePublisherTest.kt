package com.steadyvault.camera.storage.vault

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFilePublisherTest {
    @Test
    fun publishesOneValidatedFileWithoutChangingBytes() {
        val directory = Files.createTempDirectory("steadyvault-publisher").toFile()
        try {
            val raw = directory.resolve("steadyvault_raw_test.mp4")
            val destination = directory.resolve("SV_test.mp4")
            val bytes = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
            raw.writeBytes(bytes)

            val result = RecordingFilePublisher.publish(raw, destination) { candidate ->
                candidate.isFile && candidate.length() == bytes.size.toLong()
            }

            assertEquals(destination, result.file)
            assertTrue(destination.isFile)
            assertFalse(raw.exists())
            assertTrue(bytes.contentEquals(destination.readBytes()))
        } finally {
            directory.deleteRecursively()
        }
    }
}
