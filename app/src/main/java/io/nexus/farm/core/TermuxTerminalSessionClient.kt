package io.nexus.farm.core

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Minimal {@link TerminalSessionClient} for BotFarm's embedded terminal.
 *
 * forwards session events (text, title, finish) to callbacks so the UI can
 * react. logging is swallowed — termux logs are noisy and we don't have a
 * logcat viewer.
 */
class BotFarmTerminalSessionClient(
	private val onTextChanged: (TerminalSession) -> Unit = {},
	private val onTitleChanged: (TerminalSession) -> Unit = {},
	private val onSessionFinished: (TerminalSession) -> Unit = {},
	private val onShellPid: (TerminalSession, Int) -> Unit = { _, _ -> },
) : TerminalSessionClient {

	override fun onTextChanged(@NonNull changedSession: TerminalSession) {
		onTextChanged(changedSession)
	}

	override fun onTitleChanged(@NonNull changedSession: TerminalSession) {
		onTitleChanged(changedSession)
	}

	override fun onSessionFinished(@NonNull finishedSession: TerminalSession) {
		onSessionFinished(finishedSession)
	}

	override fun onCopyTextToClipboard(@NonNull session: TerminalSession, text: String) {
		// no clipboard integration yet
	}

	override fun onPasteTextFromClipboard(@Nullable session: TerminalSession?) {
		// no clipboard integration yet
	}

	override fun onBell(@NonNull session: TerminalSession) {
		// no bell handler
	}

	override fun onColorsChanged(@NonNull session: TerminalSession) {
		// no color scheme changes
	}

	override fun onTerminalCursorStateChange(state: Boolean) {
		// no cursor blinking UI
	}

	override fun setTerminalShellPid(@NonNull session: TerminalSession, pid: Int) {
		onShellPid(session, pid)
	}

	override fun getTerminalCursorStyle(): Int? = null

	override fun logError(tag: String, message: String) = Unit
	override fun logWarn(tag: String, message: String) = Unit
	override fun logInfo(tag: String, message: String) = Unit
	override fun logDebug(tag: String, message: String) = Unit
	override fun logVerbose(tag: String, message: String) = Unit
	override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
	override fun logStackTrace(tag: String, e: Exception) = Unit
}
