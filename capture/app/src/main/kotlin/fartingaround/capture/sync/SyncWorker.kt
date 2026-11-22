package fartingaround.capture.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fartingaround.capture.model.label
import fartingaround.capture.CaptureApp
import fartingaround.capture.MainActivity
import fartingaround.capture.R

/**
 * Runs SyncEngine.syncStory for one story as a WorkManager job, so uploads
 * keep going after the app is backgrounded or swiped away. It's expedited and
 * promoted to a foreground service (with a progress notification) because a
 * multi-hundred-MB video PUT can take minutes — longer than Android lets plain
 * background work run.
 *
 * Failures are not retried automatically: the error is already written onto
 * the story (and the pending media item) for the UI to show, and the user
 * re-taps Sync. A 409 conflict is likewise stored on the story; the editor
 * offers keep-mine / take-theirs.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

	override suspend fun doWork(): Result {
		val localId = inputData.getString(KEY_STORY_ID) ?: return Result.failure()
		val app = applicationContext as CaptureApp
		val title = app.storyStore.get(localId)?.doc?.label ?: "Story"

		runCatching { setForeground(foregroundInfo(title)) } // may be refused when the app is in the background on 12+; the work still runs
		return try {
			app.syncEngine.syncStory(localId)
			Result.success()
		} catch (e: SyncEngine.ConflictException) {
			notifyError(title, e.message ?: "Changed on the site")
			Result.failure(workDataOf(KEY_ERROR to (e.message ?: "conflict")))
		} catch (e: Exception) {
			// State is already persisted on the story; surface the error in the notification briefly.
			notifyError(title, e.message ?: "Sync failed")
			Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Sync failed")))
		}
	}

	override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Syncing…")

	private fun foregroundInfo(title: String): ForegroundInfo {
		ensureChannel(applicationContext)
		val open = PendingIntent.getActivity(
			applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val n = NotificationCompat.Builder(applicationContext, CHANNEL)
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setContentTitle("Syncing to fartingaround.fun")
			.setContentText(title)
			.setOngoing(true)
			.setProgress(0, 0, true)
			.setContentIntent(open)
			.build()
		return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
	}

	private fun notifyError(title: String, message: String) {
		val nm = applicationContext.getSystemService(NotificationManager::class.java)
		val n = NotificationCompat.Builder(applicationContext, CHANNEL)
			.setSmallIcon(android.R.drawable.stat_notify_error)
			.setContentTitle("Sync failed: $title")
			.setContentText(message)
			.setStyle(NotificationCompat.BigTextStyle().bigText(message))
			.setAutoCancel(true)
			.build()
		runCatching { nm.notify(NOTIFICATION_ID + 1, n) } // POST_NOTIFICATIONS may be denied; that's fine
	}

	companion object {
		const val KEY_STORY_ID = "storyId"
		const val KEY_ERROR = "error"
		const val CHANNEL = "sync"
		private const val NOTIFICATION_ID = 41

		fun ensureChannel(context: Context) {
			val nm = context.getSystemService(NotificationManager::class.java)
			if (nm.getNotificationChannel(CHANNEL) == null) {
				nm.createNotificationChannel(
					NotificationChannel(CHANNEL, context.getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
						description = context.getString(R.string.sync_channel_desc)
					}
				)
			}
		}

		/** Queue a sync for one story. One job per story; re-enqueueing replaces a queued (not running) one. */
		fun enqueue(context: Context, localId: String) {
			val req = OneTimeWorkRequestBuilder<SyncWorker>()
				.setInputData(workDataOf(KEY_STORY_ID to localId))
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.addTag(TAG)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork("sync-$localId", ExistingWorkPolicy.KEEP, req)
		}

		const val TAG = "sync"
	}
}
