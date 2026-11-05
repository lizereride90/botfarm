package io.nexus.farm.ui.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nexus.farm.core.Farm
import io.nexus.farm.core.Server
import io.nexus.farm.core.Status
import io.nexus.farm.ui.Pal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val TABS = listOf("console", "files", "startup")

/**
 * one server, laid out like a hosting panel: console on top, the folder you
 * deploy into, and the startup config underneath.
 */
@Composable
fun ServerDetail(id: String, onBack: () -> Unit) {
	val servers by Farm.servers.collectAsState()
	val logs by Farm.logs.collectAsState()
	val server = servers.firstOrNull { it.id == id } ?: return

	var tab by remember { mutableIntStateOf(0) }
	val lines = logs[server.id] ?: ""

	Column(Modifier.fillMaxSize().padding(12.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			TextButton(onClick = onBack) { Text("back", fontSize = 13.sp, color = Pal.muted) }
			Spacer(Modifier.weight(1f))
			IconButton(onClick = { Farm.kill(server.id); Farm.remove(server.id); onBack() }) {
				Icon(Icons.Filled.Delete, "delete", tint = Pal.muted)
			}
		}

		Column(Modifier.fillMaxWidth()) {
			Text(server.name, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.text)
			Row(verticalAlignment = Alignment.CenterVertically) {
				Box(Modifier.size(8.dp).background(statusColor(server.status), CircleShape))
				Spacer(Modifier.width(6.dp))
				Text(
					buildString {
						append(server.status.name.lowercase())
						if (server.status == Status.RUNNING) {
							append(" · pid ").append(server.pid)
							appendHelp(server)
						}
						if (server.restarts > 0) append(" · restart ${server.restarts}")
					},
					fontSize = 11.sp,
					color = statusColor(server.status),
				)
			}
		}

		Spacer(Modifier.height(10.dp))

		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			when (server.status) {
				Status.RUNNING, Status.STARTING -> {
					Button(
						onClick = { Farm.stop(server.id) },
						colors = ButtonDefaults.buttonColors(containerColor = Pal.panel),
						contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 6.dp),
					) { Text("stop", fontSize = 12.sp) }
					Button(
						onClick = { Farm.kill(server.id) },
						colors = ButtonDefaults.buttonColors(containerColor = Pal.panel),
						contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 6.dp),
					) { Text("kill", fontSize = 12.sp) }
				}
				Status.INSTALLING -> {
					Text("installing", fontSize = 12.sp, color = Pal.warn)
				}
				else -> {
					Button(
						onClick = { Farm.start(server.id) },
						colors = ButtonDefaults.buttonColors(containerColor = Pal.red),
						contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 6.dp),
					) { Text("start", fontSize = 12.sp) }
				}
			}

			if (server.install.isNotBlank()) {
				Button(
					onClick = { Farm.install(server.id, force = true) { Farm.start(server.id) } },
					colors = ButtonDefaults.buttonColors(containerColor = Pal.panel),
					contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 6.dp),
				) { Text("reinstall", fontSize = 12.sp) }
			}
		}

		Spacer(Modifier.height(12.dp))

		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			TABS.forEachIndexed { index, name ->
				Box(
					Modifier
						.background(
							if (tab == index) Pal.red else Pal.panel,
							RoundedCornerShape(8.dp),
						)
						.clickable { tab = index }
						.padding(horizontal = 14.dp, vertical = 7.dp),
				) {
					Text(
						name,
						fontSize = 12.sp,
						color = if (tab == index) Pal.black else Pal.muted,
						fontWeight = FontWeight.SemiBold,
					)
				}
			}
		}

		Spacer(Modifier.height(10.dp))

		Box(Modifier.weight(1f).fillMaxWidth()) {
			when (tab) {
				0 -> Console(lines)
				1 -> FilesTab(server)
				else -> StartupTab(server)
			}
		}
	}
}

private fun StringBuilder.appendHelp(server: Server) {
	val seconds = (System.currentTimeMillis() - server.startedAt) / 1000
	if (seconds <= 0) return
	append(" · up ")
	append(
		when {
			seconds < 60 -> "${seconds}s"
			seconds < 3600 -> "${seconds / 60}m"
			seconds < 86400 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
			else -> "${seconds / 86400}d ${seconds % 86400 / 3600}h"
		},
	)
}

@Composable
private fun Console(lines: String) {
	val scroll = rememberScrollState()
	LaunchedEffect(lines.length) { scroll.animateScrollTo(scroll.maxValue) }

	Box(Modifier.fillMaxSize().background(Pal.black, RoundedCornerShape(10.dp)).padding(10.dp)) {
		Text(
			lines.ifBlank { "no output yet" },
			fontSize = 11.sp,
			fontFamily = FontFamily.Monospace,
			color = if (lines.isBlank()) Pal.muted else Pal.text,
			modifier = Modifier.fillMaxSize().verticalScroll(scroll),
		)
	}
}

@Composable
private fun FilesTab(server: Server) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var dir by remember(server.id) { mutableStateOf(server.dir) }
	var tick by remember { mutableIntStateOf(0) }
	var preview by remember { mutableStateOf<Pair<String, String>?>(null) }

	val entries = remember(dir, tick) { Farm.listing(dir) }
	val root = server.dir

	// saf picker, so the archive can come from downloads or any drive
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult

		scope.launch {
			val archive = withContext(Dispatchers.IO) { copyIn(context, uri) }
			if (archive == null) return@launch
			Farm.importArchive(server.id, archive) { tick++ }
		}
	}

	Column(Modifier.fillMaxSize()) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(
					dir.removePrefix(root).ifBlank { "/" },
					fontSize = 11.sp,
					color = Pal.muted,
					fontFamily = FontFamily.Monospace,
				)
				Text("${entries.size} entries", fontSize = 10.sp, color = Pal.muted)
			}
			TextButton(onClick = { tick++ }) {
				Icon(Icons.Filled.Refresh, "refresh", tint = Pal.muted)
			}
		}

		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Button(
				onClick = {
					picker.launch(arrayOf("application/zip", "application/gzip", "application/x-gzip", "*/*"))
				},
				colors = ButtonDefaults.buttonColors(containerColor = Pal.red),
				contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 6.dp),
			) { Text("import archive", fontSize = 12.sp) }

			if (dir != root) {
				Button(
					onClick = { dir = File(dir).parentFile?.absolutePath ?: root },
					colors = ButtonDefaults.buttonColors(containerColor = Pal.panel),
					contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 6.dp),
				) { Text("up", fontSize = 12.sp) }
			}
		}

		Spacer(Modifier.height(8.dp))

		LazyColumn(Modifier.fillMaxSize()) {
			items(entries, key = { it.absolutePath }) { file ->
				Row(
					Modifier
						.fillMaxWidth()
						.clickable {
							when {
								file.isDirectory -> dir = file.absolutePath
								file.length() <= 256 * 1024 -> preview = file.name to runCatching { file.readText() }.getOrDefault("")
								else -> preview = file.name to "too big to show here"
							}
						}
						.padding(vertical = 9.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						if (file.isDirectory) "› ${file.name}" else file.name,
						fontSize = 12.sp,
						color = if (file.isDirectory) Pal.text else Pal.muted,
						fontFamily = FontFamily.Monospace,
						modifier = Modifier.weight(1f),
					)
					if (!file.isDirectory) Text(size(file.length()), fontSize = 10.sp, color = Pal.muted)
				}
			}
		}
	}

	preview?.let { (name, body) ->
		androidx.compose.material3.AlertDialog(
			onDismissRequest = { preview = null },
			title = { Text(name, fontSize = 13.sp) },
			text = {
				Text(
					body.take(20000),
					fontSize = 11.sp,
					fontFamily = FontFamily.Monospace,
					modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
				)
			},
			confirmButton = { TextButton(onClick = { preview = null }) { Text("close") } },
		)
	}
}

@Composable
private fun StartupTab(server: Server) {
	var command by remember(server.id) { mutableStateOf(server.command) }
	var dir by remember(server.id) { mutableStateOf(server.dir) }
	var install by remember(server.id) { mutableStateOf(server.install) }
	var env by remember(server.id) { mutableStateOf(envText(server.env)) }
	var auto by remember(server.id) { mutableStateOf(server.autoRestart) }
	var saved by remember { mutableStateOf("") }

	val presets = listOf("npm install", "npm ci", "pip install -r requirements.txt", "yarn", "bun install")

	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		Field("startup command", command, 3) { command = it }
		Spacer(Modifier.height(8.dp))
		Field("server folder", dir, 1) { dir = it }
		Spacer(Modifier.height(8.dp))
		Field("install command", install, 2) { install = it }

		Spacer(Modifier.height(6.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			presets.take(3).forEach { preset ->
				TextButton(onClick = { install = preset }) { Text(short(preset), fontSize = 10.sp, color = Pal.muted) }
			}
		}

		Spacer(Modifier.height(6.dp))
		Field("env vars, one KEY=value per line", env, 4) { env = it }
		Spacer(Modifier.height(4.dp))
		Text("keys from the api pool are already in there as the usual variables.", fontSize = 10.sp, color = Pal.muted)

		Spacer(Modifier.height(10.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text("auto restart", fontSize = 13.sp, color = Pal.text)
				Text("comes back after a crash, with backoff", fontSize = 10.sp, color = Pal.muted)
			}
			Switch(auto, { auto = it })
		}

		Spacer(Modifier.height(12.dp))
		Button(
			onClick = {
				Farm.configure(
					server.id,
					name = server.name,
					command = command,
					dir = dir,
					install = install,
					env = parseEnv(env),
					autoRestart = auto,
				)
				saved = "saved"
			},
			colors = ButtonDefaults.buttonColors(containerColor = Pal.red),
			modifier = Modifier.fillMaxWidth(),
		) { Text("save") }

		if (saved.isNotBlank()) {
			Spacer(Modifier.height(6.dp))
			Text(saved, fontSize = 11.sp, color = Pal.good)
		}
	}
}

@Composable
private fun Field(label: String, value: String, lines: Int, onChange: (String) -> Unit) {
	OutlinedTextField(
		value,
		onChange,
		label = { Text(label, fontSize = 11.sp) },
		maxLines = lines,
		textStyle = androidx.compose.ui.text.TextStyle(
			fontFamily = FontFamily.Monospace,
			fontSize = 12.sp,
		),
		modifier = Modifier.fillMaxWidth(),
	)
}

private fun envText(env: Map<String, String>): String =
	env.entries.joinToString("\n") { "${it.key}=${it.value}" }

private fun parseEnv(text: String): Map<String, String> {
	val map = linkedMapOf<String, String>()
	for (raw in text.split("\n")) {
		val line = raw.trim()
		if (line.isBlank() || line.startsWith("#")) continue
		val cut = line.indexOf('=')
		if (cut <= 0) continue
		val key = line.take(cut).trim()
		val value = line.substring(cut + 1).trim().removeSurrounding("\"")
		if (key.isNotEmpty()) map[key] = value
	}
	return map
}

private fun short(preset: String): String = when {
	preset.startsWith("npm ci") -> "npm ci"
	preset.startsWith("npm install") -> "npm i"
	preset.startsWith("pip") -> "pip -r"
	preset.startsWith("bun") -> "bun"
	else -> preset
}

private fun size(bytes: Long): String = when {
	bytes < 1024 -> "${bytes}b"
	bytes < 1024 * 1024 -> "${bytes / 1024}k"
	else -> "${bytes / (1024 * 1024)}m"
}

/** saf uri to a real file, so the paste survives the picker's permission window */
private fun copyIn(context: Context, uri: Uri): File? = runCatching {
	val name = displayName(context, uri) ?: "import.zip"
	val target = File(context.cacheDir, name)
	context.contentResolver.openInputStream(uri)?.use { input ->
		target.outputStream().use { output -> input.copyTo(output) }
	} ?: return null
	target
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String? {
	runCatching {
		context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
			val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (index >= 0 && cursor.moveToFirst()) {
				val name = cursor.getString(index)
				if (!name.isNullOrBlank()) return name
			}
		}
	}
	return uri.lastPathSegment?.substringAfterLast('/')
}
