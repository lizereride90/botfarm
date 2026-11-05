package io.nexus.farm.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class Status { STOPPED, STARTING, RUNNING, STOPPING, CRASHED, INSTALLING }

data class Server(
	val id: String,
	val name: String,
	val command: String,
	val dir: String,
	/** run before start, once per folder. npm install, pip install -r, whatever */
	val install: String = "",
	/** extra env vars handed to this process only */
	val env: Map<String, String> = emptyMap(),
	val status: Status = Status.STOPPED,
	val pid: Int = 0,
	val startedAt: Long = 0,
	val exit: Int? = null,
	val restarts: Int = 0,
	val autoRestart: Boolean = true,
)

/**
 * holds every running process and every line they printed.
 *
 * one writer thread per process feeding a shared buffer, and the ui only gets
 * an update every 200ms. a chatty bot can print thousands of lines a second and
 * recomposing per line would cook the phone.
 */
object Farm {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val running = ConcurrentHashMap<String, Process>()
	private val buffers = ConcurrentHashMap<String, StringBuilder>()

	private val _servers = MutableStateFlow<List<Server>>(emptyList())
	val servers: StateFlow<List<Server>> = _servers.asStateFlow()

	private val _logs = MutableStateFlow<Map<String, String>>(emptyMap())
	val logs: StateFlow<Map<String, String>> = _logs.asStateFlow()

	private val _console = MutableStateFlow("")
	val console: StateFlow<String> = _console.asStateFlow()

	private val consoleBuffer = StringBuilder()
	private val dirty = AtomicBoolean(false)
	private val persist = AtomicBoolean(false)

	private const val MAX_LINES = 4000

	fun load() {
		if (_servers.value.isNotEmpty()) return

		val file = File(Env.root, "servers.json")
		if (!file.exists()) return

		runCatching {
			val array = JSONArray(file.readText())
			val list = (0 until array.length()).map { i ->
				val o = array.getJSONObject(i)
				Server(
					id = o.getString("id"),
					name = o.getString("name"),
					command = o.getString("command"),
					dir = o.optString("dir", ""),
					install = o.optString("install", ""),
					env = readEnv(o.optJSONObject("env")),
					autoRestart = o.optBoolean("autoRestart", true),
				)
			}
			_servers.value = list
		}

		scope.launch { tick() }
	}

	private fun save() {
		if (!persist.compareAndSet(false, true)) return

		scope.launch {
			val array = JSONArray()
			for (server in _servers.value) {
				array.put(
					JSONObject()
						.put("id", server.id)
						.put("name", server.name)
						.put("command", server.command)
						.put("dir", server.dir)
						.put("install", server.install)
						.put("env", writeEnv(server.env))
						.put("autoRestart", server.autoRestart),
				)
			}
			File(Env.root, "servers.json").writeText(array.toString())
			persist.set(false)
		}
	}

	private suspend fun tick() {
		while (true) {
			delay(200)

			if (dirty.compareAndSet(true, false)) {
				_logs.value = buffers.mapValues { it.value.toString() }
				_console.value = consoleBuffer.toString()
			}
		}
	}

	private fun append(target: StringBuilder, line: String) {
		synchronized(target) {
			target.append(line).append('\n')

			// drop the oldest half once we go over, cheaper than trimming every line
			if (target.length > MAX_LINES * 90) {
				val cut = target.indexOf("\n", target.length / 2)
				if (cut > 0) target.delete(0, cut + 1)
			}
		}
		dirty.set(true)
	}

	private fun update(id: String, block: (Server) -> Server) {
		_servers.value = _servers.value.map { if (it.id == id) block(it) else it }
	}

	fun add(
		name: String,
		command: String,
		dir: String = "",
		install: String = "",
		env: Map<String, String> = emptyMap(),
		autoRestart: Boolean = true,
	): Server {
		val clean = name.trim().ifBlank { "bot" }
		val server = Server(
			id = System.currentTimeMillis().toString(36),
			name = clean,
			command = command.trim(),
			dir = dir.trim().ifBlank { File(Env.projects, slug(clean)).absolutePath },
			install = install.trim(),
			env = env,
			autoRestart = autoRestart,
		)

		runCatching { File(server.dir).mkdirs() }

		_servers.value = _servers.value + server
		save()
		return server
	}

	/** everything editable from the panel, in one shot */
	fun configure(
		id: String,
		name: String = "",
		command: String? = null,
		dir: String? = null,
		install: String? = null,
		env: Map<String, String>? = null,
		autoRestart: Boolean? = null,
	) {
		update(id) { server ->
			server.copy(
				name = name.trim().ifBlank { server.name },
				command = command?.trim() ?: server.command,
				dir = dir?.trim()?.ifBlank { server.dir } ?: server.dir,
				install = install?.trim() ?: server.install,
				env = env ?: server.env,
				autoRestart = autoRestart ?: server.autoRestart,
			)
		}
		_servers.value.firstOrNull { it.id == id }?.let { runCatching { File(it.dir).mkdirs() } }
		save()
	}

	fun remove(id: String) {
		kill(id)
		_servers.value = _servers.value.filterNot { it.id == id }
		buffers.remove(id)
		save()
	}

	fun start(id: String) {
		val server = _servers.value.firstOrNull { it.id == id } ?: return
		if (running.containsKey(id)) return
		if (!Env.ready) {
			append(consoleBuffer, "environment is not set up yet")
			return
		}

		val buffer = buffers.getOrPut(id) { StringBuilder() }
		append(buffer, "> ${server.command}")

		update(id) { it.copy(status = Status.STARTING, exit = null) }

		scope.launch {
			runCatching {
				val script = buildString {
					if (server.dir.isNotBlank()) append("cd '").append(server.dir.replace("'", "'\\''")).append("' && ")
					append(server.command)
				}

				val builder = ProcessBuilder(Env.script(script))
				builder.environment().putAll(Env.environment())
				builder.environment().putAll(Keys.environment())
				builder.environment().putAll(server.env)
				builder.directory(File(server.dir))
				builder.redirectErrorStream(true)
				val process = builder.start()

				running[id] = process
				update(id) { it.copy(status = Status.RUNNING, pid = processPid(process), startedAt = System.currentTimeMillis()) }

				process.inputStream.bufferedReader().useLines { lines ->
					for (line in lines) {
						append(buffer, line)

						// a limit means this key is done for now. park it and let
						// the restart pick up the next one in the pool.
						val rotated = Keys.rotate(line)
						if (rotated != null) {
							append(buffer, "key for $rotated is resting, restarting")
							process.destroy()
							break
						}
					}
				}

				val code = process.waitFor()
				running.remove(id)

				val previous = _servers.value.firstOrNull { it.id == id } ?: return@launch
				val stoppedByUser = previous.status == Status.STOPPING

				append(buffer, if (code == 0) "exited" else "exit $code")
				update(id) { it.copy(status = if (stoppedByUser) Status.STOPPED else Status.CRASHED, pid = 0, exit = code) }

				if (!stoppedByUser && code != 0 && previous.autoRestart) {
					val wait = (previous.restarts.coerceAtMost(6) * 2000L) + 2000L
					append(buffer, "restarting in ${wait / 1000}s")
					update(id) { it.copy(restarts = it.restarts + 1) }
					delay(wait)
					if (!running.containsKey(id)) start(id)
				}
			}.onFailure { error ->
				append(buffer, "failed: ${error.message}")
				update(id) { it.copy(status = Status.CRASHED, pid = 0) }
			}
		}
	}

	fun stop(id: String) {
		val process = running[id] ?: return
		update(id) { it.copy(status = Status.STOPPING) }
		runCatching { process.destroy() }
	}

	fun kill(id: String) {
		val process = running.remove(id) ?: return
		update(id) { it.copy(status = Status.STOPPED, pid = 0) }
		runCatching { process.destroyForcibly() }
	}

	fun runningCount(): Int = running.size

	/** free form command in the terminal tab, output goes to its own buffer */
	fun exec(command: String, onDone: (Int) -> Unit = {}) {
		if (!Env.ready) {
			append(consoleBuffer, "environment is not set up yet")
			return
		}

		append(consoleBuffer, "> $command")

		scope.launch {
			runCatching {
				val builder = ProcessBuilder(Env.script(command))
				builder.environment().putAll(Env.environment())
				builder.environment().putAll(Keys.environment())
				builder.redirectErrorStream(true)
				val process = builder.start()

				process.inputStream.bufferedReader().useLines { lines ->
					for (line in lines) append(consoleBuffer, line)
				}

				val code = process.waitFor()
				append(consoleBuffer, "[done $code]")
				onDone(code)
			}.onFailure { append(consoleBuffer, "failed: ${it.message}") }
		}
	}

	fun clearConsole() {
		synchronized(consoleBuffer) { consoleBuffer.setLength(0) }
		dirty.set(true)
	}

	/**
	 * the install step every panel has: run once per folder, output to the same
	 * log, marker file so a restart does not reinstall node modules every time.
	 */
	fun install(id: String, force: Boolean = false, then: (() -> Unit)? = null) {
		val server = _servers.value.firstOrNull { it.id == id } ?: return
		if (server.install.isBlank()) {
			then?.invoke()
			return
		}

		val dir = File(server.dir)
		val marker = File(dir, INSTALLED)

		if (!force && marker.exists()) {
			then?.invoke()
			return
		}

		val buffer = buffers.getOrPut(id) { StringBuilder() }
		append(buffer, "> install: ${server.install}")
		update(id) { it.copy(status = Status.INSTALLING) }

		scope.launch {
			runCatching {
				dir.mkdirs()
				val builder = ProcessBuilder(Env.script(server.install))
				builder.directory(dir)
				builder.environment().putAll(Env.environment())
				builder.environment().putAll(Keys.environment())
				builder.environment().putAll(server.env)
				builder.redirectErrorStream(true)

				val process = builder.start()
				process.inputStream.bufferedReader().useLines { lines ->
					for (line in lines) append(buffer, line)
				}

				val code = process.waitFor()
				if (code == 0) {
					append(buffer, "install done")
					runCatching { marker.writeText(System.currentTimeMillis().toString()) }
				} else {
					append(buffer, "install failed, exit $code")
				}

				update(id) { it.copy(status = Status.STOPPED) }
				if (code == 0) then?.invoke()
			}.onFailure { error ->
				append(buffer, "install failed: ${error.message}")
				update(id) { it.copy(status = Status.CRASHED) }
			}
		}
	}

	/** drop an archive into the server folder, like an upload on a real panel */
	fun importArchive(id: String, archive: File, onDone: (String) -> Unit = {}) {
		val server = _servers.value.firstOrNull { it.id == id } ?: return
		val buffer = buffers.getOrPut(id) { StringBuilder() }
		val dir = File(server.dir)

		append(buffer, "> import ${archive.name}")

		scope.launch {
			runCatching {
				dir.mkdirs()
				val count = Zips.extract(archive, dir) { line -> append(buffer, line) }
				runCatching { File(dir, INSTALLED).delete() }
				append(buffer, "imported $count files")
				onDone("imported $count files")
			}.onFailure { error ->
				append(buffer, "import failed: ${error.message}")
				onDone("import failed: ${error.message}")
			}
		}
	}

	/** the folder as the panel shows it, biggest first */
	fun listing(dir: String): List<File> {
		val root = File(dir)
		return root.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
	}

	private fun processPid(process: Process): Int = runCatching {
		val field = process.javaClass.getDeclaredField("pid")
		field.isAccessible = true
		field.getInt(process)
	}.getOrDefault(0)
}

/** marker written after a successful install, cleared on import */
private const val INSTALLED = ".botfarm-installed"

private fun slug(name: String): String =
	name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "bot" }

private fun readEnv(o: org.json.JSONObject?): Map<String, String> {
	if (o == null) return emptyMap()
	val map = linkedMapOf<String, String>()
	val names = o.keys()
	while (names.hasNext()) {
		val key = names.next()
		map[key] = o.optString(key, "")
	}
	return map
}

private fun writeEnv(env: Map<String, String>): JSONObject {
	val o = JSONObject()
	for ((key, value) in env) o.put(key, value)
	return o
}
