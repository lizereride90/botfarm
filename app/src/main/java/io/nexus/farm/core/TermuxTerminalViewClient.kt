package io.nexus.farm.core

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

/**
 * Minimal {@link TerminalViewClient} for BotFarm's embedded terminal.
 *
 * hardware keys (back, volume, etc.) are passed through to the shell. IME
 * input is handled by TerminalView's own InputConnection, so onCodePoint is
 * rarely called directly.
 */
class BotFarmTerminalViewClient(
	private val onKeyDown: (Int, KeyEvent, TerminalSession) -> Boolean = { _, _, _ -> false },
	private val onKeyUp: (Int, KeyEvent) -> Boolean = { _, _ -> false },
	private val onSingleTapUp: (MotionEvent) -> Unit = {},
) : TerminalViewClient {

	override fun onScale(scale: Float): Float = 1f

	override fun onSingleTapUp(e: MotionEvent) {
		onSingleTapUp(e)
	}

	override fun shouldBackButtonBeMappedToEscape(): Boolean = false

	override fun shouldEnforceCharBasedInput(): Boolean = false

	override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

	override fun isTerminalViewSelected(): Boolean = true

	override fun copyModeChanged(copyMode: Boolean) = Unit

	override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean =
		onKeyDown(keyCode, event, session)

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = onKeyUp(keyCode, event)

	override fun onLongPress(event: MotionEvent): Boolean = false

	override fun readControlKey(): Boolean = false

	override fun readAltKey(): Boolean = false

	override fun readShiftKey(): Boolean = false

	override fun readFnKey(): Boolean = false

	override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

	override fun onEmulatorSet() = Unit

	override fun logError(tag: String, message: String) = Unit
	override fun logWarn(tag: String, message: String) = Unit
	override fun logInfo(tag: String, message: String) = Unit
	override fun logDebug(tag: String, message: String) = Unit
	override fun logVerbose(tag: String, message: String) = Unit
	override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
	override fun logStackTrace(tag: String, e: Exception) = Unit
}
