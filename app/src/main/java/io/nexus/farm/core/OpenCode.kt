package io.nexus.farm.core

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * opencode is a bun standalone built for android aarch64, packaged for the
 * termux prefix. installing it is the same trick as the userland: unpack the
 * deb under our prefix and swap the compiled in paths, which are the same
 * length as ours.
 */
object OpenCode {

	private const val RELEASE = "https://api.github.com/repos/guysoft/opencode-termux/releases/latest"

	/** what the package was built against */
	private val FROM = "/data/data/com.termux/files".toByteArray()

	val binary: File?
		get() = listOf("opencode", "opencode2", "opencode-ai")
			.map { File(Env.bin, it) }
			.firstOrNull { it.exists() }

	val installed: Boolean get() = binary != null

	suspend fun install(log: (String) -> Unit) = withContext(Dispatchers.IO) {
		val url = latestDeb()
		log("downloading opencode")

		val deb = File(Env.tmp, "opencode.deb")
		download(url, deb) { done, total ->
			if (total > 0) log("opencode ${done * 100 / total}%")
		}

		log("unpacking")
		extractDeb(deb, log)
		deb.delete()

		log("repointing paths")
		val to = Env.root.absolutePath.toByteArray()
		if (to.size != FROM.size) {
			throw IllegalStateException("prefix ${Env.root} is ${to.size} bytes, the package needs exactly ${FROM.size}")
		}

		var patched = 0
		Env.prefix.walkTopDown().forEach { file ->
			if (!file.isFile) return@forEach
			if (patch(file, FROM, to)) patched++
		}

		log("patched $patched files")

		val bin = binary ?: throw IllegalStateException("no opencode binary landed in ${Env.bin}")
		Os.chmod(bin.absolutePath, 0b111101101)
		log("ready, ${bin.name}")
	}

	private fun latestDeb(): String {
		val connection = (URL(RELEASE).openConnection() as HttpURLConnection).apply {
			connectTimeout = 20_000
			readTimeout = 30_000
			setRequestProperty("User-Agent", "botfarm")
			setRequestProperty("Accept", "application/vnd.github+json")
		}

		try {
			val body = connection.inputStream.bufferedReader().readText()
			val assets = JSONObject(body).getJSONArray("assets")
			for (i in 0 until assets.length()) {
				val asset = assets.getJSONObject(i)
				val name = asset.getString("name")
				if (name.endsWith("_aarch64.deb")) return asset.getString("browser_download_url")
			}
		} finally {
			connection.disconnect()
		}

		throw IllegalStateException("no aarch64 deb in the latest opencode release")
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

	/** ar archive, then the data.tar member, then tar. deb is just those three. */
	private fun extractDeb(deb: File, log: (String) -> Unit) {
		DataInputStream(BufferedInputStream(deb.inputStream())).use { input ->
			val magic = ByteArray(8)
			input.readFully(magic)
			if (String(magic) != "!<arch>\n") throw IllegalStateException("not a deb")

			while (true) {
				val header = ByteArray(60)
				val first = input.read(header)
				if (first < 60) break

				// ar member names are padded to 16 bytes, directories end with /
				var name = String(header, 0, 16).trim().removeSuffix("/")
				val size = String(header, 48, 10).trim().toInt()

				if (name.startsWith("data.tar")) {
					val payload = ByteArray(size)
					input.readFully(payload)
					log("member $name, ${size / 1048576}mb")
					unpack(payload, name)
					return
				}

				input.skipBytes(size + (size % 2))
			}
		}

		throw IllegalStateException("the deb had no data member")
	}

	private fun unpack(payload: ByteArray, member: String) {
		val stream = ByteArrayInputStream(payload)
		val clean = member.removeSuffix("/")
		val decompressed = when {
			clean.endsWith(".xz") -> XZCompressorInputStream(stream)
			clean.endsWith(".gz") -> GzipCompressorInputStream(stream)
			else -> throw IllegalStateException("unsupported member $member")
		}

		val root = Env.prefix.canonicalPath
		TarArchiveInputStream(decompressed).use { tar ->
			while (true) {
				val entry = tar.nextEntry as? TarArchiveEntry ?: break

				var name = entry.name
				while (name.startsWith("./")) name = name.substring(2)
				if (!name.startsWith(DEB_ROOT)) {
					tar.skip(entry.size)
					continue
				}
				name = name.removePrefix(DEB_ROOT)

				val out = File(Env.prefix, name)
				if (!out.canonicalPath.startsWith(root + File.separator)) continue

				when {
					entry.isDirectory -> out.mkdirs()

					entry.isSymbolicLink -> {
						out.parentFile?.mkdirs()
						out.delete()
						runCatching { Os.symlink(entry.linkName, out.absolutePath) }
					}

					entry.isLink -> {
						val source = File(Env.prefix, entry.linkName.removePrefix(DEB_ROOT))
						out.parentFile?.mkdirs()
						out.delete()
						runCatching { Os.link(source.absolutePath, out.absolutePath) }
					}

					else -> {
						out.parentFile?.mkdirs()
						FileOutputStream(out).buffered().use { tar.copyTo(it) }
						val mode = entry.mode
						if (mode and 0b001001001 != 0) runCatching { Os.chmod(out.absolutePath, mode) }
					}
				}
			}
		}
	}

	/**
	 * same length swap of every compiled in path. chunked, so a 90mb binary
	 * does not have to sit in memory.
	 */
	private fun patch(file: File, from: ByteArray, to: ByteArray): Boolean {
		if (from.size != to.size || file.length() == 0L) return false

		var changed = false
		RandomAccessFile(file, "rw").use { handle ->
			val length = handle.length()
			val window = 1 shl 20
			val buffer = ByteArray(window + from.size)
			var position = 0L

			while (position < length) {
				handle.seek(position)

				val wanted = minOf(window.toLong(), length - position).toInt()
				var read = 0
				while (read < wanted) {
					val got = handle.read(buffer, read, wanted - read)
					if (got <= 0) break
					read += got
				}
				if (read <= 0) break

				var hit = false
				var index = 0
				while (index + from.size <= read) {
					var match = true
					for (offset in from.indices) {
						if (buffer[index + offset] != from[offset]) {
							match = false
							break
						}
					}

					if (match) {
						for (offset in to.indices) buffer[index + offset] = to[offset]
						hit = true
						index += to.size
					} else {
						index++
					}
				}

				if (hit) {
					handle.seek(position)
					handle.write(buffer, 0, read)
					changed = true
				}

				position += (read - from.size + 1).coerceAtLeast(1)
			}
		}

		return changed
	}

	private const val DEB_ROOT = "data/data/com.termux/files/usr/"
}
