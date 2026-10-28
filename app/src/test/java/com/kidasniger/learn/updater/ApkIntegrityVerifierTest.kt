package com.kidasniger.learn.updater

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkIntegrityVerifierTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun validZip(name: String = "test.apk"): File {
        val file = temporaryFolder.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(80, 75, 3, 4, 1, 2, 3, 4))
            zip.closeEntry()
        }
        return file
    }

    @Test fun acceptsCompleteZipWithMatchingSizeAndHash() {
        val file = validZip()
        val digest = ApkIntegrityVerifier.sha256(file)
        assertEquals(digest, ApkIntegrityVerifier.verify(file, file.length(), digest))
    }

    @Test fun rejectsWrongSizeAndWrongHash() {
        val file = validZip()
        assertThrows(IOException::class.java) { ApkIntegrityVerifier.verify(file, file.length() + 1, null) }
        assertThrows(IOException::class.java) { ApkIntegrityVerifier.verify(file, file.length(), "0".repeat(64)) }
    }

    @Test fun rejectsCorruptedOrEmptyFiles() {
        val file = temporaryFolder.newFile("corrupted.apk")
        file.writeText("not an APK")
        assertThrows(IOException::class.java) { ApkIntegrityVerifier.verify(file, file.length(), null) }
        val empty = temporaryFolder.newFile("empty.apk")
        assertThrows(IOException::class.java) { ApkIntegrityVerifier.verify(empty, 0L, null) }
    }
}
