package io.nexus.farm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import io.nexus.farm.core.Env
import io.nexus.farm.core.Farm
import io.nexus.farm.core.Prefs

class FarmApp : Application() {

	lateinit var prefs: Prefs
		private set

	override fun onCreate() {
		super.onCreate()

		Env.bind(this)
		prefs = Prefs(this)
		Farm.load()
		Keys.load()

		val manager = getSystemService<NotificationManager>() ?: return
		val channel = NotificationChannel(
			CHANNEL,
			getString(R.string.service_channel),
			NotificationManager.IMPORTANCE_LOW,
		).apply {
			description = "keeps hosted bots alive"
			setShowBadge(false)
			enableVibration(false)
		}

		manager.createNotificationChannel(channel)
	}

	companion object {
		const val CHANNEL = "farm"
	}
}
