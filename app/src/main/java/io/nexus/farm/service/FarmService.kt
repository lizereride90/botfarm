package io.nexus.farm.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinderimport androidx.core.app.NotificationCompat
import io.nexus.farm.FarmApp
import io.nexus.farm.MainActivity
import io.nexus.farm.R
import io.nexus.farm.core.Farm

/**
 * the only reason this exists is to keep the process alive after the user
 * swipes the app away. hosted bots run as children of this process, so if this
 * dies they die with it.
 */
class FarmService : Service() {

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val notification = build()

		// plain call. the typed overload only matters when targeting 34, and we
		// deliberately target 28 so the userland can be executed at all.
		startForeground(ID, notification)

		// START_STICKY so android brings us back if it kills the process for memory
		return START_STICKY
	}

	private fun build(): Notification {
		val open = PendingIntent.getActivity(
			this,
			0,
			Intent(this, MainActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

		val running = Farm.runningCount()

		return NotificationCompat.Builder(this, FarmApp.CHANNEL)
			.setContentTitle(getString(R.string.service_title))
			.setContentText(if (running == 0) "idle, waiting for a start" else "$running running")
			.setSmallIcon(android.R.drawable.stat_sys_download_done)
			.setOngoing(true)
			.setSilent(true)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setContentIntent(open)
			.build()
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		// user swiped the app out of recents. do not stop, that is the point.
		super.onTaskRemoved(rootIntent)
	}

	companion object {
		private const val ID = 41

		fun start(context: Context) {
			val intent = Intent(context, FarmService::class.java)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				context.startForegroundService(intent)
			} else {
				context.startService(intent)
			}
		}

		fun stop(context: Context) {
			context.stopService(Intent(context, FarmService::class.java))
		}
	}
}
