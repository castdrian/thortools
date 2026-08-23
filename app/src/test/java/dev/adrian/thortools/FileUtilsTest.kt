package dev.adrian.thortools

import dev.adrian.thortools.utils.FileUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsTest {
    @Test
    fun replacesTextFilesWithoutLeavingTemporaryArtifacts() {
        val directory = Files.createTempDirectory("thortools-file-utils").toFile()
        val file = directory.resolve("support/system.prop")
        try {
            assertTrue(FileUtils.saveFile(file.path, "first=value\n"))
            assertEquals("first=value\n", file.readText())
            assertTrue(FileUtils.saveFile(file.path, "second=value\n"))
            assertEquals("second=value\n", file.readText())
            assertFalse(directory.walkTopDown().any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun copiesStreamsThroughTheSameAtomicReplacementPath() {
        val directory = Files.createTempDirectory("thortools-stream-utils").toFile()
        val file = directory.resolve("scripts/boot.sh")
        try {
            assertTrue(FileUtils.copyInputStream(ByteArrayInputStream("exit 0\n".toByteArray()), file.path))
            assertEquals("exit 0\n", file.readText())
            assertFalse(directory.walkTopDown().any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedStreamCopyKeepsThePreviousFile() {
        val directory = Files.createTempDirectory("thortools-failed-copy").toFile()
        val file = directory.resolve("support/module.prop")
        try {
            assertTrue(FileUtils.saveFile(file.path, "version=old\n"))
            val failingInput = object : InputStream() {
                private var delivered = false

                override fun read(): Int {
                    if (!delivered) {
                        delivered = true
                        return 'x'.code
                    }
                    throw IOException("simulated read failure")
                }
            }
            assertFalse(FileUtils.copyInputStream(failingInput, file.path))
            assertEquals("version=old\n", file.readText())
            assertFalse(directory.walkTopDown().any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
