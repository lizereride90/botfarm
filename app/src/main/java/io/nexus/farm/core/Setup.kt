package io.nexus.farm.core

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * pulls the linux userland and proot down once, then unpacks them into the app
 * directory. everything lands under filesDir so no storage permission is
 * involved and uninstalling takes all of it away.
 */
object Setup {

	const val BASE = "https://github.com/lizereride90/botfarm/releases/download/userland"

	suspend fun install(log: (String) -> Unit) = withContext(Dispatchers.IO) {
		Env.rootfs.mkdirs()
		Env.runtime.mkdirs()
		Env.prootLibs.mkdirs()
		Env.projects.mkdirs()
		Env.logs.mkdirs()
		Env.tmp.mkdirs()

		if (!File(Env.rootfs, "bin/sh").exists()) {
			log("downloading userland")
			val archive = File(Env.tmp, "rootfs.tar.gz")
			download("$BASE/rootfs.tar.gz", archive) { done, total ->
				if (total > 0) log("userland ${done * 100 / total}%")
			}

			log("unpacking userland")
			extractTarGz(archive, Env.rootfs)
			archive.delete()
		} else {
			log("userland already there")
		}

		if (!Env.proot.exists()) {
			log("downloading proot")
			val archive = File(Env.tmp, "proot.tar.gz")
			download("$BASE/proot.tar.gz", archive) { _, _ -> }
			extractTarGz(archive, Env.runtime)
			archive.delete()
			Os.chmod(Env.proot.absolutePath, 0b111101101)
		} else {
			log("proot already there")
		}

		log("checking")
		val probe = ProcessBuilder(Env.shellCommand("echo ok && uname -m"))
			.redirectErrorStream(true)
			.apply { environment().putAll(Env.environment()) }
			.start()

		val out = probe.inputStream.bufferedReader().readText().trim()
		val code = probe.waitFor()

		if (code != 0 || !out.contains("ok")) {
			throw IllegalStateException("the userland did not start: $out")
		}

		log("ready, $out")
	}

	private fun download(url: String, target: File, progress: (Long, Long) -> Unit) {
		val connection = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 20_000
			readTimeout = 60_000
			instanceFollowRedirects = true
			setRequestProperty("User-Agent", "botfarm")
		}

		try {
			val code = connection.responseCode
			if (code !in 200..299) throw IllegalStateException("download failed, http $code")

			val total = connection.contentLengthLong
			var done = 0L

			connection.inputStream.use { input ->
				target.outputStream().buffered().use { output ->
					val buffer = ByteArray(1 shl 16)
					while (true) {
						val read = input.read(buffer)
						if (read <= 0) break
						output.write(buffer, 0, read)
						done += read
						progress(done, total)
					}
				}
			}
		} finally {
			connection.disconnect()
		}
	}

	private fun extractTarGz(archive: File, target: File) {
		TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
			while (true) {
				val entry = tar.nextEntry as? TarArchiveEntry ?: break
				val out = File(target, entry.name)

				// nothing may escape the target directory, a tar with ../ in it
				// would otherwise write into the app's private files
				if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue

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
						if (mode and 0b001001001 != 0) {
							runCatching { Os.chmod(out.absolutePath, mode) }
						}
					}
				}
			}
		}
	}
}
