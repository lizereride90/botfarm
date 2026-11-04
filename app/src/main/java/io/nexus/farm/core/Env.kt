package io.nexus.farm.core

import android.content.Context
import java.io.File

/**
 * every path the app touches, in one place.
 *
 * filesDir is app private so no permission is needed for any of this. the
 * shared storage mount inside the rootfs is optional and only used for the
 * file manager when the user grants all files access.
 */
object Env {

	lateinit var app: Context

	fun bind(context: Context) {
		app = context.applicationContext
	}

	val root: File get() = app.filesDir

	/** a real linux userland, unpacked once */
	val rootfs: File get() = File(root, "rootfs")

	/** proot + its libs, small android binaries */
	val runtime: File get() = File(root, "runtime")
	val proot: File get() = File(runtime, "lib/proot")
	val prootLibs: File get() = File(runtime, "lib")

	/** where user projects live, this is the home inside the rootfs too */
	val projects: File get() = File(root, "projects")

	/** per server logs, kept out of the rootfs so they survive a reinstall of it */
	val logs: File get() = File(root, "logs")

	val tmp: File get() = File(root, "tmp")

	val ready: Boolean
		get() = File(rootfs, "bin/sh").exists() && proot.exists()

	val storage: File? get() = listOf("/storage/emulated/0", "/sdcard")
		.map(::File)
		.firstOrNull { it.canRead() && it.list()?.isNotEmpty() == true }

	/**
	 * the command line that puts a process inside the rootfs. proot fakes uid 0
	 * and maps the android paths the userland expects, which is why none of this
	 * needs root.
	 */
	fun prootCommand(vararg args: String): List<String> {
		val mounts = buildList {
			add("-b"); add("/dev")
			add("-b"); add("/proc")
			add("-b"); add("/sys")
			add("-b"); add("/dev/urandom:/dev/random")
			storage?.let { add("-b"); add("${it.absolutePath}:/mnt/storage") }
			add("-b"); add("${projects.absolutePath}:/root")
			add("-b"); add("${tmp.absolutePath}:/tmp")
			add("-b"); add("${logs.absolutePath}:/var/log/farm")
		}

		return buildList {
			add(proot.absolutePath)
			add("--link2symlink")
			add("-0")
			add("-r"); add(rootfs.absolutePath)
			add("-w"); add("/root")
			addAll(mounts)
			add("/usr/bin/env")
			add("-i")
			add("HOME=/root")
			add("TERM=xterm-256color")
			add("LANG=C.UTF-8")
			add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
			addAll(args)
		}
	}

	fun shellCommand(script: String): List<String> = prootCommand("/bin/sh", "-lc", script)

	fun environment(): Map<String, String> = mapOf(
		"PROOT_TMP_DIR" to tmp.absolutePath,
		"PROOT_NO_SECCOMP" to "1",
		"LD_LIBRARY_PATH" to prootLibs.absolutePath,
		"TMPDIR" to tmp.absolutePath,
	)
}
