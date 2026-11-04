package io.nexus.farm.core

import android.content.Context
import androidx.core.content.edit

/**
 * tiny prefs wrapper. everything the app needs to remember between launches,
 * nothing that needs a database.
 */
class Prefs(context: Context) {

	private val sp = context.getSharedPreferences("farm", Context.MODE_PRIVATE)

	var setupDone: Boolean
		get() = sp.getBoolean("setup_done", false)
		set(value) = sp.edit { putBoolean("setup_done", value) }

	var termsAccepted: Boolean
		get() = sp.getBoolean("terms", false)
		set(value) = sp.edit { putBoolean("terms", value) }

	var onboardingStep: Int
		get() = sp.getInt("onboarding_step", 0)
		set(value) = sp.edit { putInt("onboarding_step", value) }

	/** keep the service alive through swipe, needs the user to whitelist us */
	var keepAlive: Boolean
		get() = sp.getBoolean("keep_alive", true)
		set(value) = sp.edit { putBoolean("keep_alive", value) }

	var autoRestart: Boolean
		get() = sp.getBoolean("auto_restart", true)
		set(value) = sp.edit { putBoolean("auto_restart", value) }

	var compressLogs: Boolean
		get() = sp.getBoolean("compress_logs", true)
		set(value) = sp.edit { putBoolean("compress_logs", value) }

	var lines: String
		get() = sp.getString("lines", "") ?: ""
		set(value) = sp.edit { putString("lines", value) }
}
