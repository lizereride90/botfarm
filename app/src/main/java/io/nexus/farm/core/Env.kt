package io.nexus.farm.core

import android.content.Context
import java.io.File

/**
 * every path the app touches, in one place.
 *
 * the userland is a termux bootstrap unpacked straight into filesDir. those
 * binaries are built against android's own libc, so they run as ordinary
 * children of this process. no proot, no ptrace, no root, full speed.
 *
 * the prefix is what termux calls the root of its installation. most tools
 * read it from the environment, a few have it compiled in, which is why the
 * app keeps it in the same shape termux uses.
 */
object Env {

	lateinit var app: Context

	fun bind(context: Context) {
		app = context.applicationContext
	}

	/**
	 * the app's private dir, but spelled the way termux spells it. the binaries
	 * we unpack have the prefix compiled in, so everything they resolve has to
	 * match this string character for character.
	 */
	val root: File get() = File("/data/data/${app.packageName}/files")

	/** the termux install, $PREFIX */
	val prefix: File get() = File(root, "usr")
	val bin: File get() = File(prefix, "bin")
	val prefixLib: File get() = File(prefix, "lib")

	val home: File get() = File(root, "home")
	val logs: File get() = File(root, "logs")

	/** $PREFIX/tmp, where termux tools expect to write */
	val tmp: File get() = File(prefix, "tmp")

	/** project folders, this is what the file manager opens by default */
	val projects: File get() = File(home, "projects")

	val sh: File get() = File(bin, "sh")
	val bash: File get() = File(bin, "bash")
	val termuxExec: File get() = File(prefixLib, "libtermux-exec.so")

	val ready: Boolean get() = sh.exists()

	val storage: File? get() = listOf("/storage/emulated/0", "/sdcard")
		.map(::File)
		.firstOrNull { it.canRead() && it.list()?.isNotEmpty() == true }

	/** the script runner every hosted process goes through */
	fun script(command: String): List<String> = listOf(sh.absolutePath, "-c", command)

	/** interactive shells, terminals and tmux sessions use this */
	fun loginShell(): List<String> = listOf(bash.absolutePath, "-l")

	fun environment(): MutableMap<String, String> = mutableMapOf(
		"PREFIX" to prefix.absolutePath,
		"HOME" to home.absolutePath,
		"TMPDIR" to tmp.absolutePath,
		"PATH" to bin.absolutePath,
		"LD_LIBRARY_PATH" to prefixLib.absolutePath,
		"SHELL" to bash.absolutePath,
		"TERM" to "xterm-256color",
		"COLORTERM" to "truecolor",
		"LANG" to "en_US.UTF-8",
	).apply {
		// the shim that makes shebangs and exec work the way linux expects
		if (termuxExec.exists()) put("LD_PRELOAD", termuxExec.absolutePath)
	}
}
