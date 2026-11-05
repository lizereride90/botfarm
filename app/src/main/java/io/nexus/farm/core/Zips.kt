package io.nexus.farm.core

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * import a project the way a hosting panel does: drop an archive, get a folder.
 *
 * zip and tar.gz both, since github gives you either. paths are checked so an
 * archive with ../ in it cannot write outside the server folder.
 */
object Zips {

	suspend fun extract(archive: File, target: File, log: (String) -> Unit): Int =
		withContext(Dispatchers.IO) {
			target.mkdirs()
			val name = archive.name.lowercase()

			when {
				name.endsWith(".zip") -> unzip(archive, target, log)
				name.endsWith(".tar.gz") || name.endsWith(".tgz") -> untar(archive, target, log)
				else -> {
					// sniff instead of trusting the extension, phones rename things
					val head = archive.inputStream().use { stream ->
						val bytes = ByteArray(4)
						stream.read(bytes)
						bytes
					}
					when {
						head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> unzip(archive, target, log)
						head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> untar(archive, target, log)
						else -> throw IllegalStateException("${archive.name} is not a zip or tar.gz")
					}
				}
			}
		}

	private fun unzip(archive: File, target: File, log: (String) -> Unit): Int {
		val root = target.canonicalPath
		var count = 0

		ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				val out = File(target, entry.name)

				// anything that resolves outside the folder is skipped outright
				if (!out.canonicalPath.startsWith(root + File.separator)) {
					zip.closeEntry()
					continue
				}

				if (entry.isDirectory) {
					out.mkdirs()
				} else {
					out.parentFile?.mkdirs()
					out.outputStream().buffered().use { zip.copyTo(it) }
					count++
					if (count % 200 == 0) log("$count files")
				}

				zip.closeEntry()
			}
		}

		return count
	}

	private fun untar(archive: File, target: File, log: (String) -> Unit): Int {
		val root = target.canonicalPath
		var count = 0

		TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
			while (true) {
				val entry = tar.nextEntry as? TarArchiveEntry ?: break

				// a single top folder is common in git archives, strip it
				val name = entry.name.removeSuffix("/")
				val out = File(target, name)
				if (!out.canonicalPath.startsWith(root + File.separator)) continue

				when {
					entry.isDirectory -> out.mkdirs()

					entry.isSymbolicLink -> {
						out.parentFile?.mkdirs()
						out.delete()
						runCatching { Os.symlink(entry.linkName, out.absolutePath) }
					}

					else -> {
						out.parentFile?.mkdirs()
						out.outputStream().buffered().use { tar.copyTo(it) }
						val mode = entry.mode
						if (mode and 0b001001001 != 0) runCatching { Os.chmod(out.absolutePath, mode) }
						count++
						if (count % 200 == 0) log("$count files")
					}
				}
			}
		}

		return count
	}
}
