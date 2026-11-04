package io.nexus.farm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFC8102E)
private val RedDeep = Color(0xFF7A0A1A)
private val Black = Color(0xFF0B0B0D)
private val Panel = Color(0xFF141418)
private val Line = Color(0xFF26262C)
private val Text = Color(0xFFEDEDF0)
private val Muted = Color(0xFF8B8B95)
private val Good = Color(0xFF2ECC71)
private val Warn = Color(0xFFF1C40F)
private val Bad = Color(0xFFE74C3C)

val farmColors = darkColorScheme(
	primary = Red,
	onPrimary = Color.White,
	primaryContainer = RedDeep,
	onPrimaryContainer = Color(0xFFFFD9DE),
	secondary = Muted,
	onSecondary = Black,
	background = Black,
	onBackground = Text,
	surface = Panel,
	onSurface = Text,
	surfaceVariant = Line,
	onSurfaceVariant = Muted,
	error = Bad,
	outline = Line,
)

object Pal {
	val red = Red
	val good = Good
	val warn = Warn
	val bad = Bad
	val muted = Muted
	val panel = Panel
	val line = Line
	val black = Black
	val text = Text
}

@Composable
fun FarmTheme(content: @Composable () -> Unit) {
	@Suppress("UNUSED_EXPRESSION")
	isSystemInDarkTheme()

	MaterialTheme(colorScheme = farmColors, content = content)
}
