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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nexus.farm.FarmApp
import io.nexus.farm.core.Env
import io.nexus.farm.core.Farm
import io.nexus.farm.core.Keys
import io.nexus.farm.core.OpenCode
import io.nexus.farm.core.Status
import io.nexus.farm.core.Terminal
import io.nexus.farm.ui.Pal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/* ----------------------------------------------------------------- terminal */

@Composable
fun TerminalScreen() {
	val scope = rememberCoroutineScope()
	val scroll = rememberScrollState()

	var text by remember { mutableStateOf("") }
	var where by remember { mutableStateOf("") }
	var live by remember { mutableStateOf(false) }
	var input by remember { mutableStateOf("") }
	val focus = remember { FocusRequester() }
	val keyboard = LocalSoftwareKeyboardController.current

	// tmux owns the pty, so a poll is enough and the session survives a
	// recomposition, a tab switch, even the app being swiped away
	LaunchedEffect(Unit) {
		Terminal.ensure()
		while (true) {
			val up = Terminal.alive()
			live = up
			if (up) {
				text = Terminal.screen()
				where = Terminal.where()
			}
			delay(700)
		}
	}

	LaunchedEffect(text.length) {
		scroll.animateScrollTo(scroll.maxValue)
	}

	LaunchedEffect(Unit) {
		focus.requestFocus()
		keyboard?.show()
	}

	Column(Modifier.fillMaxSize().padding(12.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text("terminal", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.text)
				Text(
					if (live) "tmux · $where" else "no session",
					fontSize = 11.sp,
					color = if (live) Pal.muted else Pal.warn,
				)
			}
			TextButton(onClick = { scope.launch { Terminal.restart(); text = "" } }) {
				Text("restart", fontSize = 12.sp, color = Pal.muted)
			}
		}

		Spacer(Modifier.height(8.dp))

		Box(
			Modifier
				.fillMaxWidth()
				.weight(1f)
				.background(Pal.black, RoundedCornerShape(10.dp))
				.padding(8.dp),
		) {
			Text(
				text.ifBlank { if (live) "shell is up, type something" else "starting the shell" },
				fontSize = 11.sp,
				color = if (text.isBlank()) Pal.muted else Pal.text,
				fontFamily = FontFamily.Monospace,
				modifier = Modifier.fillMaxSize().verticalScroll(scroll),
			)
		}

		Spacer(Modifier.height(6.dp))

		Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			for ((label, key) in KEYS) {
				TextButton(onClick = { scope.launch { Terminal.key(key) } }) {
					Text(label, fontSize = 11.sp, color = Pal.muted)
				}
			}
		}

		Row(verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				input,
				{ input = it },
				placeholder = { Text("ls", fontSize = 13.sp) },
				singleLine = true,
				modifier = Modifier
					.weight(1f)
					.focusRequester(focus),
				keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
			)
			Spacer(Modifier.width(8.dp))
			TextButton(
				onClick = {
					if (input.isNotBlank()) {
						val line = input
						input = ""
						scope.launch { Terminal.type(line) }
					}
				},
			) { Text("send", fontWeight = FontWeight.SemiBold) }
		}
	}
}

private val KEYS = listOf(
	"ctrl-c" to "C-c",
	"ctrl-d" to "C-d",
	"tab" to "Tab",
	"esc" to "Escape",
	"up" to "Up",
	"down" to "Down",
)

/* -------------------------------------------------------------------- files */
