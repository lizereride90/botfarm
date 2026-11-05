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

/**
 * pulls the termux userland down once and unpacks it into the app directory.
 * everything lands under filesDir, so no storage permission is involved and
 * uninstalling takes all of it away.
 */
object Setup {

	const val BASE = "https://github.com/lizereride90/botfarm/releases/download/userland"

	suspend fun install(log: (String) -> Unit) = withContext(Dispatchers.IO) {
		Env.prefix.mkdirs()
		Env.home.mkdirs()
		Env.logs.mkdirs()
		Env.tmp.mkdirs()
		Env.projects.mkdirs()

		if (!Env.ready) {
			log("downloading termux, about 60mb")
			val archive = File(Env.tmp, "termux.tar.gz")
			download("$BASE/termux.tar.gz", archive) { done, total ->
				if (total > 0) log("termux ${done * 100 / total}%")
			}

			log("unpacking")
			extractTarGz(archive, Env.root)
			archive.delete()
		} else {
			log("termux already there")
		}

		Os.chmod(Env.sh.absolutePath, 0b111101101)

		writeDefaults()
		linkStorage()

		log("checking")
		val probe = ProcessBuilder(Env.script("echo ok && uname -m && \$SHELL -c 'echo shell'"))
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

	/** a couple of files termux expects to exist before a login shell is happy */
	private fun writeDefaults() {
		val bashrc = File(Env.home, ".bashrc")
		if (!bashrc.exists()) {
			bashrc.writeText(
				"""
				# botfarm
				export PS1='\[\e[38;5;160m\]\w\[\e[0m\] \$ '
				export PATH=${Env.bin.absolutePath}:\$PATH
				""".trimIndent() + "\n",
			)
		}

		File(Env.home, ".termux").mkdirs()
	}

	/** termux-setup-storage, but without asking the system for anything new */
	private fun linkStorage() {
		val target = Env.storage ?: return
		val link = File(Env.home, "storage")
		if (link.exists()) return
		runCatching { Os.symlink(target.absolutePath, link.absolutePath) }
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
		val root = target.canonicalPath
		TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
			while (true) {
				val entry = tar.nextEntry as? TarArchiveEntry ?: break
				val out = File(target, entry.name)

				// nothing may escape the target directory, a tar with ../ in it
				// would otherwise write into the app's private files
				if (!out.canonicalPath.startsWith(root + File.separator)) continue

				when {
					entry.isDirectory -> out.mkdirs()

					entry.isSymbolicLink -> {
						out.parentFile?.mkdirs()
						out.delete()
						runCatching { Os.symlink(entry.linkName, out.absolutePath) }
					}

					entry.isLink -> {
						// hard link, termux ships a few of those for busybox
						val source = File(target, entry.linkName)
						out.parentFile?.mkdirs()
						out.delete()
						runCatching { Os.link(source.absolutePath, out.absolutePath) }
							.onFailure { runCatching { source.copyTo(out, overwrite = true) } }
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
