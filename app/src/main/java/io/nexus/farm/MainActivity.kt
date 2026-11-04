package io.nexus.farm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nexus.farm.core.Env
import io.nexus.farm.core.Setup
import io.nexus.farm.service.FarmService
import io.nexus.farm.ui.FarmTheme
import io.nexus.farm.ui.Pal
import io.nexus.farm.ui.screens.FilesScreen
import io.nexus.farm.ui.screens.OpenCodeScreen
import io.nexus.farm.ui.screens.ServersScreen
import io.nexus.farm.ui.screens.SettingsScreen
import io.nexus.farm.ui.screens.TerminalScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()

		val app = application as FarmApp
		FarmService.start(this)

		setContent {
			FarmTheme {
				var ready by remember { mutableStateOf(app.prefs.setupDone && Env.ready) }

				if (ready) {
					Farm(app)
				} else {
					Onboarding(
						onFinished = {
							app.prefs.setupDone = true
							ready = true
							FarmService.start(this)
						},
					)
				}
			}
		}
	}
}

@Composable
private fun Onboarding(onFinished: () -> Unit) {
	val context = LocalContext.current
	val scope = remember { CoroutineScope(Dispatchers.Main) }

	var step by remember { mutableIntStateOf(0) }
	var log by remember { mutableStateOf("") }
	var working by remember { mutableStateOf(false) }
	var failed by remember { mutableStateOf<String?>(null) }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		verticalArrangement = Arrangement.Center,
	) {
		when (step) {
			0 -> {
				Text("BotFarm", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Pal.text)
				Spacer(Modifier.height(10.dp))
				Text(
					"hosts your discord bots, selfbots and shells inside one app, with a real linux userland underneath and opencode wired in.",
					color = Pal.muted,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					"no root. everything lives in the app's own folder, so uninstalling takes it all back.",
					color = Pal.muted,
				)
				Spacer(Modifier.height(28.dp))
				Primary("get started") { step = 1 }
			}

			1 -> {
				Text("files", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
				Spacer(Modifier.height(10.dp))
				Text(
					"give the file manager access to your phone storage so you can open project folders from anywhere. nothing else in the app needs it.",
					color = Pal.muted,
				)
				Spacer(Modifier.height(28.dp))
				Primary("allow all files") {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
						runCatching {
							context.startActivity(
								Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
									.setData(Uri.parse("package:${context.packageName}")),
							)
						}
					}
					step = 2
				}
				Skip("skip, app folder only") { step = 2 }
			}

			2 -> {
				Text("battery", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
				Spacer(Modifier.height(10.dp))
				Text(
					"set battery usage to unrestricted, otherwise android kills the bots a few minutes after you lock the screen.",
					color = Pal.muted,
				)
				Spacer(Modifier.height(28.dp))
				Primary("set unrestricted") {
					runCatching {
						context.startActivity(
							Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
								.setData(Uri.parse("package:${context.packageName}")),
						)
					}
					step = 3
				}
				Skip("skip for now") { step = 3 }
			}

			3 -> {
				Text("notifications", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
				Spacer(Modifier.height(10.dp))
				Text(
					"so you hear about it when a bot starts, stops or crashes while you are doing something else.",
					color = Pal.muted,
				)
				Spacer(Modifier.height(28.dp))
				Primary("got it") { step = 4 }
				Skip("skip") { step = 4 }
			}

			else -> {
				Text("setting up", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
				Spacer(Modifier.height(10.dp))
				Text(
					"one time download of the linux userland. do it on wifi, it takes a few minutes.",
					color = Pal.muted,
				)
				Spacer(Modifier.height(20.dp))

				Text(log.ifBlank { "not started" }, color = Pal.muted, fontSize = 12.sp)

				failed?.let {
					Spacer(Modifier.height(12.dp))
					Text(it, color = Pal.bad, fontSize = 13.sp)
				}

				Spacer(Modifier.height(28.dp))
				Primary(if (working) "working…" else "install", enabled = !working) {
					working = true
					failed = null
					scope.launch {
						runCatching { Setup.install { line -> log = line } }
							.onSuccess { onFinished() }
							.onFailure { failed = it.message; working = false }
					}
				}
			}
		}
	}
}

@Composable
private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
	Button(
		onClick = onClick,
		enabled = enabled,
		modifier = Modifier.fillMaxWidth(),
		colors = ButtonDefaults.buttonColors(containerColor = Pal.red),
	) {
		Text(text, fontWeight = FontWeight.SemiBold)
	}
}

@Composable
private fun Skip(text: String, onClick: () -> Unit) {
	TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Text(text, color = Pal.muted)
	}
}

@Composable
private fun Farm(app: FarmApp) {
	var tab by remember { mutableIntStateOf(0) }

	val items = listOf(
		Triple("servers", Icons.Filled.PlayArrow, 0),
		Triple("terminal", Icons.Filled.Terminal, 1),
		Triple("files", Icons.Filled.Folder, 2),
		Triple("code", Icons.Filled.Build, 3),
		Triple("settings", Icons.Filled.Settings, 4),
	)

	Scaffold(
		containerColor = Pal.black,
		bottomBar = {
			NavigationBar(containerColor = Pal.panel, tonalElevation = 0.dp) {
				items.forEach { (label, icon, index) ->
					NavigationBarItem(
						selected = tab == index,
						onClick = { tab = index },
						icon = { Icon(icon, contentDescription = label) },
						label = { Text(label, fontSize = 11.sp) },
					)
				}
			}
		},
	) { padding ->
		Row(modifier = Modifier.padding(padding)) {
			when (tab) {
				0 -> ServersScreen()
				1 -> TerminalScreen()
				2 -> FilesScreen()
				3 -> OpenCodeScreen()
				else -> SettingsScreen(app)
			}
		}
	}
}
