package org.fartingaround.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.fartingaround.app.sync.SyncWorker

/**
 * Enqueues syncs, asking for POST_NOTIFICATIONS first on Android 13+ so the
 * upload progress notification actually shows. The sync runs either way —
 * the permission only affects whether the user can see it.
 */
@Composable
fun rememberSyncLauncher(): (List<String>) -> Unit {
	val context = LocalContext.current
	val pending = remember { mutableStateOf<List<String>>(emptyList()) }
	val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		pending.value.forEach { SyncWorker.enqueue(context, it) }
		pending.value = emptyList()
	}
	return { ids ->
		if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context)) {
			pending.value = ids
			ask.launch(Manifest.permission.POST_NOTIFICATIONS)
		} else {
			ids.forEach { SyncWorker.enqueue(context, it) }
		}
	}
}

private fun hasNotificationPermission(context: Context): Boolean =
	Build.VERSION.SDK_INT < 33 ||
		ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
