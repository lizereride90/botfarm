package io.nexus.farm.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.androidViewLifecycleAware
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.nexus.farm.core.BotFarmTerminalSessionClient
import io.nexus.farm.core.BotFarmTerminalViewClient
import io.nexus.farm.core.Env

/**
 * Compose wrapper around Termux's TerminalView.
 *
 * a real PTY-backed shell session inside the app process. no tmux, no polling
 * — the terminal renders directly to a SurfaceView.
 *
 * returns the current {@link TerminalSession} so callers can write input to it.
 */
@Composable
fun TermuxScreen(): TerminalSession? {
	val context = LocalContext.current
	var sessionRef by remember { mutableStateOf<TerminalSession?>(null) }

	val viewClient = remember {
		BotFarmTerminalViewClient()
	}

	val sessionClient = remember {
		BotFarmTerminalSessionClient()
	}

	// create the terminal view and hold a reference
	var viewRef by remember { mutableStateOf<TerminalView?>(null) }

	androidx.compose.ui.platform.AndroidView(
		factory = { ctx ->
			TerminalView(ctx, null).also { viewRef = it }
		},
		update = { view ->
			viewRef = view
		},
		modifier = Modifier.fillMaxSize(),
	)

	// create and attach the session once the view exists
	DisposableEffect(viewRef, viewClient, sessionClient) {
		if (viewRef == null) return@DisposableEffect

		val env = Env.environment()
		val envArray = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()

		val session = TerminalSession(
			Env.bash.absolutePath,
			Env.home.absolutePath,
			arrayOf("bash", "-l"),
			envArray,
			5000,
			sessionClient,
		)

		sessionRef = session
		viewRef?.attachSession(session)

		onDispose {
			sessionRef?.finishIfRunning()
			sessionRef = null
		}
	}

	return sessionRef
}
