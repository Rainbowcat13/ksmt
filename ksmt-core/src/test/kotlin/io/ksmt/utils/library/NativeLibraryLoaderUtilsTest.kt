package io.ksmt.utils.library

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeLibraryLoaderUtilsTest {
    @Test
    fun extractLibraryFromModuleDistArchive() {
        val workspaceRoot = createTempDirectory("ksmt-native-loader-test")
        val searchRoot = workspaceRoot.resolve("ksmt-cvc5/ksmt-cvc5-native/out/windowsX64/classes").apply {
            createDirectories()
        }
        val distDir = workspaceRoot.resolve("ksmt-cvc5/dist").apply {
            createDirectories()
        }
        val archive = distDir.resolve("cvc5-native-win-x86-64-1.2.0.zip")

        writeArchiveWithLibrary(archive, "libcvc5.dll", "cvc5")

        val outputDirectory = workspaceRoot.resolve("extracted").apply {
            createDirectories()
        }

        val extracted = NativeLibraryLoaderUtils.extractLibraryFromArchives(
            searchRoot = searchRoot,
            libraryName = "libcvc5.dll",
            outputDirectory = outputDirectory
        )

        assertNotNull(extracted)
        assertEquals("cvc5", extracted.readText())
    }

    private fun writeArchiveWithLibrary(archive: Path, libraryName: String, contents: String) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(libraryName))
            contents.byteInputStream().copyTo(zip)
            zip.closeEntry()
        }
    }
}
