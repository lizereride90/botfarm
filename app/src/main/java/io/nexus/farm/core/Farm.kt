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

enum class Status { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

data class Server(
	val id: String,
	val name: String,
	val command: String,
	val dir: String,
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

	fun add(name: String, command: String, dir: String = "", autoRestart: Boolean = true): Server {
		val server = Server(
			id = System.currentTimeMillis().toString(36),
			name = name.trim().ifBlank { "bot" },
			command = command.trim(),
			dir = dir.trim(),
			autoRestart = autoRestart,
		)
		_servers.value = _servers.value + server
		save()
		return server
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
				builder.redirectErrorStream(true)
				val process = builder.start()

				running[id] = process
				update(id) { it.copy(status = Status.RUNNING, pid = processPid(process), startedAt = System.currentTimeMillis()) }

				process.inputStream.bufferedReader().useLines { lines ->
					for (line in lines) append(buffer, line)
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

	private fun processPid(process: Process): Int = runCatching {
		val field = process.javaClass.getDeclaredField("pid")
		field.isAccessible = true
		field.getInt(process)
	}.getOrDefault(0)
}
