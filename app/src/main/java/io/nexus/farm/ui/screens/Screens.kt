package io.nexus.farm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nexus.farm.FarmApp
import io.nexus.farm.core.Env
import io.nexus.farm.core.Farm
import io.nexus.farm.core.Status
import io.nexus.farm.ui.Pal
import java.io.File

/* ------------------------------------------------------------------ servers */

@Composable
fun ServersScreen() {
	val servers by Farm.servers.collectAsState()
	val logs by Farm.logs.collectAsState()

	var adding by remember { mutableStateOf(false) }
	var open by remember { mutableStateOf<String?>(null) }

	Box(Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			item {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text("servers", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.text)
						Text("${servers.count { it.status == Status.RUNNING }} running", fontSize = 12.sp, color = Pal.muted)
					}
				}
				Spacer(Modifier.height(6.dp))
			}

			if (servers.isEmpty()) {
				item {
					Box(
						Modifier
							.fillMaxWidth()
							.padding(top = 80.dp),
						contentAlignment = Alignment.Center,
					) {
						Text("nothing added yet. tap plus.", color = Pal.muted, fontSize = 13.sp)
					}
				}
			}

			items(servers, key = { it.id }) { server ->
				val lines = logs[server.id] ?: ""

				Card(
					colors = CardDefaults.cardColors(containerColor = Pal.panel),
					shape = RoundedCornerShape(14.dp),
					modifier = Modifier.fillMaxWidth().clickable { open = if (open == server.id) null else server.id },
				) {
					Column(Modifier.padding(14.dp)) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Box(
								Modifier.size(8.dp).background(statusColor(server.status), CircleShape),
							)
							Spacer(Modifier.width(8.dp))
							Text(server.name, fontWeight = FontWeight.SemiBold, color = Pal.text)
							Spacer(Modifier.weight(1f))
							Text(server.status.name.lowercase(), fontSize = 11.sp, color = statusColor(server.status))
						}

						Spacer(Modifier.height(6.dp))
						Text(server.command, fontSize = 12.sp, color = Pal.muted, fontFamily = FontFamily.Monospace)

						Spacer(Modifier.height(10.dp))
						Row(verticalAlignment = Alignment.CenterVertically) {
							when (server.status) {
								Status.RUNNING, Status.STARTING -> {
									AssistChip(onClick = { Farm.stop(server.id) }, label = { Text("stop", fontSize = 12.sp) })
									Spacer(Modifier.width(6.dp))
									AssistChip(onClick = { Farm.kill(server.id) }, label = { Text("kill", fontSize = 12.sp) })
								}
								Status.STOPPING -> {
									AssistChip(onClick = { Farm.kill(server.id) }, label = { Text("force", fontSize = 12.sp) })
								}
								else -> {
									AssistChip(onClick = { Farm.start(server.id) }, label = { Text("start", fontSize = 12.sp) })
								}
							}

							Spacer(Modifier.weight(1f))
							IconButton(onClick = { Farm.remove(server.id) }) {
								Icon(Icons.Filled.Delete, "delete", tint = Pal.muted)
							}
						}

						if (open == server.id && lines.isNotBlank()) {
							Spacer(Modifier.height(8.dp))
							Text(
								lines.takeLast(1600),
								fontSize = 11.sp,
								color = Pal.text,
								fontFamily = FontFamily.Monospace,
								modifier = Modifier
									.fillMaxWidth()
									.background(Pal.black, RoundedCornerShape(8.dp))
									.padding(8.dp)
									.height(180.dp)
									.verticalScroll(rememberScrollState()),
							)
						}
					}
				}
			}
		}

		FloatingActionButton(
			onClick = { adding = true },
			containerColor = Pal.red,
			modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
		) {
			Icon(Icons.Filled.Add, "add")
		}
	}

	if (adding) AddServer { name, command, dir ->
		Farm.add(name, command, dir)
		adding = false
	}
}

private fun statusColor(status: Status): Color = when (status) {
	Status.RUNNING -> Pal.good
	Status.STARTING, Status.STOPPING -> Pal.warn
	Status.CRASHED -> Pal.bad
	Status.STOPPED -> Pal.muted
}

@Composable
private fun AddServer(onAdd: (String, String, String) -> Unit) {
	var name by remember { mutableStateOf("") }
	var command by remember { mutableStateOf("") }
	var dir by remember { mutableStateOf("") }

	AlertDialog(
		onDismissRequest = { onAdd("", "", "") },
		title = { Text("new server") },
		text = {
			Column {
				OutlinedTextField(name, { name = it }, label = { Text("name") }, singleLine = true)
				Spacer(Modifier.height(8.dp))
				OutlinedTextField(dir, { dir = it }, label = { Text("folder, /root/...") }, singleLine = true)
				Spacer(Modifier.height(8.dp))
				OutlinedTextField(command, { command = it }, label = { Text("command") }, maxLines = 4)
			}
		},
		confirmButton = {
			TextButton(onClick = { if (command.isNotBlank()) onAdd(name, command, dir) }) { Text("add") }
		},
		dismissButton = { TextButton(onClick = { onAdd("", "", "") }) { Text("cancel") } },
	)
}

/* ----------------------------------------------------------------- terminal */

@Composable
fun TerminalScreen() {
	val console by Farm.console.collectAsState()
	var input by remember { mutableStateOf("") }
	val scroll = rememberScrollState()

	LaunchedEffect(console.length) {
		scroll.animateScrollTo(scroll.maxValue)
	}

	Column(Modifier.fillMaxSize().padding(12.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text("terminal", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.text)
			Spacer(Modifier.weight(1f))
			TextButton(onClick = { Farm.clearConsole() }) { Text("clear", fontSize = 12.sp, color = Pal.muted) }
		}

		Box(
			Modifier
				.fillMaxWidth()
				.weight(1f)
				.background(Pal.panel, RoundedCornerShape(12.dp))
				.padding(10.dp),
		) {
			Text(
				console.ifBlank { "type a command below" },
				fontSize = 12.sp,
				color = if (console.isBlank()) Pal.muted else Pal.text,
				fontFamily = FontFamily.Monospace,
				modifier = Modifier.fillMaxSize().verticalScroll(scroll),
			)
		}

		Spacer(Modifier.height(8.dp))

		Row(verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				input,
				{ input = it },
				placeholder = { Text("ls", fontSize = 13.sp) },
				singleLine = true,
				modifier = Modifier.weight(1f),
				keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
			)
			Spacer(Modifier.width(8.dp))
			TextButton(
				onClick = {
					if (input.isNotBlank()) {
						Farm.exec(input)
						input = ""
					}
				},
			) { Text("run", fontWeight = FontWeight.SemiBold) }
		}
	}
}

/* -------------------------------------------------------------------- files */

@Composable
fun FilesScreen() {
	var path by remember { mutableStateOf(Env.root.absolutePath) }
	var entries by remember { mutableStateOf(listOf<File>()) }

	LaunchedEffect(path) {
		entries = File(path).listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
	}

	Column(Modifier.fillMaxSize().padding(12.dp)) {
		Text("files", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.text)
		Text(path.removePrefix(Env.root.absolutePath).ifBlank { "/" }, fontSize = 11.sp, color = Pal.muted)

		Spacer(Modifier.height(8.dp))

		LazyColumn(Modifier.weight(1f)) {
			item {
				TextButton(onClick = {
					val parent = File(path).parentFile
					if (parent != null && parent.absolutePath.startsWith(Env.root.absolutePath)) path = parent.absolutePath
				}) { Text("..", color = Pal.muted) }
			}

			items(entries) { file ->
				Row(
					Modifier
						.fillMaxWidth()
						.clickable { if (file.isDirectory) path = file.absolutePath }
						.padding(vertical = 10.dp),
				) {
					Text(if (file.isDirectory) "dir" else "file", fontSize = 10.sp, color = Pal.muted, modifier = Modifier.width(34.dp))
					Text(file.name, color = Pal.text, modifier = Modifier.weight(1f))
					Text(size(file), fontSize = 11.sp, color = Pal.muted)
				}
			}
		}
	}
}

private fun size(file: File): String {
	if (file.isDirectory) return ""
	val bytes = file.length()
	return when {
		bytes > 1 shl 20 -> "${bytes / (1 shl 20)}m"
		bytes > 1 shl 10 -> "${bytes / (1 shl 10)}k"
		else -> "${bytes}b"
	}
}

/* ----------------------------------------------------------------- opencode */

@Composable
fun OpenCodeScreen() {
	val console by Farm.console.collectAsState()
	var prompt by remember { mutableStateOf("") }

	Column(Modifier.fillMaxSize().padding(12.dp)) {
		Text("opencode", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.text)
		Text("runs the cli in the userland, sessions and forks land here", fontSize = 12.sp, color = Pal.muted)

		Spacer(Modifier.height(10.dp))

		Box(
			Modifier
				.weight(1f)
				.fillMaxWidth()
				.background(Pal.panel, RoundedCornerShape(12.dp))
				.padding(10.dp),
		) {
			Text(
				console.ifBlank { "ask something" },
				fontSize = 12.sp,
				fontFamily = FontFamily.Monospace,
				color = if (console.isBlank()) Pal.muted else Pal.text,
				modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
			)
		}

		Spacer(Modifier.height(8.dp))

		Row(verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				prompt,
				{ prompt = it },
				placeholder = { Text("what should i change", fontSize = 13.sp) },
				modifier = Modifier.weight(1f),
				maxLines = 3,
			)
			Spacer(Modifier.width(8.dp))
			TextButton(onClick = {
				if (prompt.isNotBlank()) {
					Farm.exec("opencode run ${quote(prompt)}")
					prompt = ""
				}
			}) { Text("send", fontWeight = FontWeight.SemiBold) }
		}
	}
}

private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

/* ----------------------------------------------------------------- settings */

@Composable
fun SettingsScreen(app: FarmApp) {
	var keepAlive by remember { mutableStateOf(app.prefs.keepAlive) }

	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
		Text("settings", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.text)
		Spacer(Modifier.height(16.dp))

		Row(Modifier.fillMaxWidth().border(1.dp, Pal.line, RoundedCornerShape(12.dp)).padding(14.dp)) {
			Column {
				Text("environment", color = Pal.text, fontWeight = FontWeight.SemiBold)
				Text(if (Env.ready) "userland ready" else "not installed", fontSize = 12.sp, color = if (Env.ready) Pal.good else Pal.bad)
				Text(Env.root.absolutePath, fontSize = 10.sp, color = Pal.muted, fontFamily = FontFamily.Monospace)
			}
		}

		Spacer(Modifier.height(12.dp))

		Row(Modifier.fillMaxWidth().border(1.dp, Pal.line, RoundedCornerShape(12.dp)).padding(14.dp)) {
			Column {
				Text("keep alive", color = Pal.text, fontWeight = FontWeight.SemiBold)
				Text(
					"android still needs battery unrestricted and this service on. to stop everything, turn the app off in the background apps list.",
					fontSize = 11.sp,
					color = Pal.muted,
				)
				Spacer(Modifier.height(8.dp))
				AssistChip(onClick = {
					keepAlive = !keepAlive
					app.prefs.keepAlive = keepAlive
				}, label = { Text(if (keepAlive) "on" else "off", fontSize = 12.sp) })
			}
		}

		Spacer(Modifier.height(12.dp))

		Text("botfarm 0.1.0", fontSize = 11.sp, color = Pal.muted)
	}
}
