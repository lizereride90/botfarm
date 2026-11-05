package io.nexus.farm.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * a real shell, not a command runner.
 *
 * tmux holds the pty and the session state, so `cd` sticks, env vars stick, and
 * anything that wants a terminal (nano, top, node repl) works. the app never
 * touches the pty directly, it just asks tmux to type and to hand back what is
 * on the pane.
 *
 * the socket lives in the prefix tmp dir so it goes away with the app.
 */
object Terminal {

	private const val SOCKET = "farm"
	private const val SESSION = "main"

	private val tmux: File get() = File(Env.bin, "tmux")

	val ready: Boolean get() = Env.ready && tmux.exists()

	private fun env(): MutableMap<String, String> = Env.environment().apply {
		put("TMUX_TMPDIR", Env.tmp.absolutePath)
	}

	private fun base(): List<String> = listOf(tmux.absolutePath, "-L", SOCKET)

	/** blocking. callers are on io already. */
	private fun run(args: List<String>, input: String? = null): Pair<Int, String> {
		return runCatching {
			val builder = ProcessBuilder(args)
			builder.environment().putAll(env())
			builder.directory(Env.home)
			builder.redirectErrorStream(true)

			val process = builder.start()
			process.outputStream.bufferedWriter().use { writer ->
				if (input != null) writer.write(input)
			}

			val text = process.inputStream.bufferedReader().readText()
			process.waitFor() to text
		}.getOrElse { -1 to (it.message ?: "tmux failed") }
	}

	suspend fun alive(): Boolean = withContext(Dispatchers.IO) {
		if (!ready) return@withContext false
		run(base() + listOf("has-session", "-t", SESSION)).first == 0
	}

	/** start the session if it is not already up, keep it if it is */
	suspend fun ensure(): Boolean = withContext(Dispatchers.IO) {
		if (!ready) return@withContext false
		Env.tmp.mkdirs()

		if (run(base() + listOf("has-session", "-t", SESSION)).first == 0) {
			return@withContext true
		}

		val start = base() + listOf(
			"new-session", "-d",
			"-s", SESSION,
			"-c", Env.home.absolutePath,
			"-x", "100", "-y", "40",
			Env.bash.absolutePath, "-l",
		)

		val code = run(start).first
		if (code != 0) {
			// a socket left behind by a killed server refuses to start clean
			run(base() + listOf("kill-server"))
			run(start).first == 0
		} else {
			true
		}
	}

	suspend fun restart(): Boolean = withContext(Dispatchers.IO) {
		if (!ready) return@withContext false
		run(base() + listOf("kill-session", "-t", SESSION))
		ensure()
	}

	/**
	 * type into the pane. through a buffer, so quotes, backslashes and unicode
	 * survive the trip without any shell quoting games.
	 */
	suspend fun type(text: String): Unit = withContext(Dispatchers.IO) {
		if (!ensure()) return@withContext

		val paste = File(Env.tmp, "paste.txt")
		runCatching { paste.writeText(text) }

		run(base() + listOf("load-buffer", "-b", "farm", paste.absolutePath))
		run(base() + listOf("paste-buffer", "-b", "farm", "-d", "-t", SESSION))
		run(base() + listOf("send-keys", "-t", SESSION, "Enter"))
	}

	/** one named key: "C-c", "Tab", "Up", "Escape", "BSpace", "C-d" */
	suspend fun key(name: String): Unit = withContext(Dispatchers.IO) {
		if (!ensure()) return@withContext
		run(base() + listOf("send-keys", "-t", SESSION, name))
	}

	/** the pane as plain text, scrollback included */
	suspend fun screen(): String = withContext(Dispatchers.IO) {
		if (!ready) return@withContext ""
		run(base() + listOf("capture-pane", "-p", "-J", "-t", SESSION, "-S", "-2000"))
			.second
			.trimEnd()
	}

	/** where the shell is, for the header */
	suspend fun where(): String = withContext(Dispatchers.IO) {
		if (!ready) return@withContext ""
		run(base() + listOf("display-message", "-p", "-t", SESSION, "#{pane_current_path}"))
			.second
			.trim()
			.removePrefix(Env.root.absolutePath)
			.ifBlank { "/" }
	}

	suspend fun resize(columns: Int, rows: Int): Unit = withContext(Dispatchers.IO) {
		if (!ready) return@withContext
		val cols = columns.coerceIn(20, 400)
		val lines = rows.coerceIn(5, 200)
		run(base() + listOf("resize-window", "-t", SESSION, "-x", "$cols", "-y", "$lines"))
		Unit
	}
}
