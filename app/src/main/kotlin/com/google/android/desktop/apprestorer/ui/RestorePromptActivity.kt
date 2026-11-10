package com.google.android.desktop.apprestorer.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.desktop.apprestorer.WorkspaceRuntime
import com.google.android.desktop.apprestorer.boot.BootCountdownOverlay
import com.google.android.desktop.apprestorer.boot.Notifications
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import kotlinx.coroutines.delay

/**
 * Centered floating popup (windowIsFloating + explicit centered launch bounds) for reviewing and
 * selecting previous-boot windows, or acting as the fallback countdown popup.
 */
class RestorePromptActivity : ComponentActivity() {
  private lateinit var runtime: WorkspaceRuntime

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    runtime = WorkspaceRuntime.get(this)
    runtime.refreshPending()
    val snapshot = runtime.pending.value
    if (snapshot == null) {
      finish()
      return
    }
    val manualReview = intent?.getBooleanExtra(EXTRA_MANUAL_REVIEW, false) == true
    runtime.promptVisible = true
    setFinishOnTouchOutside(false)
    val km = getSystemService(KeyguardManager::class.java)
    setContent {
      RestorerTheme {
        var selected by remember { mutableStateOf(snapshot.windows.map { it.windowKey }.toSet()) }
        var autoAlways by remember { mutableStateOf(runtime.autoRestore.value) }
        var countdown by remember {
          mutableIntStateOf(if (!manualReview && runtime.autoRestore.value) AUTO_COUNTDOWN_S else -1)
        }
        var restoring by remember { mutableStateOf(false) }
        val doRestore = {
          if (!restoring) {
            restoring = true
            runtime.setAutoRestore(autoAlways)
            val keys = selected
            finish() // close first; the coordinator waits for the transition to settle
            runtime.restore(snapshot, keys.takeIf { it.size != snapshot.windows.size }, clearPendingAfter = true)
          }
        }
        if (countdown >= 0) {
          LaunchedEffect(countdown) {
            if (countdown == 0) {
              doRestore()
            } else {
              delay(1_000)
              if (km?.isKeyguardLocked != true) countdown -= 1
            }
          }
        }
        RestorePromptContent(
          snapshot = snapshot,
          selectedKeys = selected,
          onToggle = { key -> countdown = -1; selected = if (key in selected) selected - key else selected + key },
          autoAlways = autoAlways,
          onAutoAlwaysChange = { autoAlways = it; if (!it) countdown = -1 },
          countdownSeconds = countdown,
          onRestore = doRestore,
          onNotNow = {
            runtime.setAutoRestore(autoAlways)
            Notifications.cancelRestoreNotification(this)
            finish()
          },
          onClose = {
            Notifications.postRestoreNotification(this, snapshot)
            finish()
          },
        )
      }
    }
  }

  override fun onDestroy() {
    if (::runtime.isInitialized && isFinishing) runtime.promptVisible = false
    super.onDestroy()
  }

  companion object {
    const val AUTO_COUNTDOWN_S = BootCountdownOverlay.AUTO_COUNTDOWN_SECONDS
    const val EXTRA_MANUAL_REVIEW = "manual_review"

    fun intent(context: Context, manualReview: Boolean = false): Intent =
      Intent(context, RestorePromptActivity::class.java)
        .putExtra(EXTRA_MANUAL_REVIEW, manualReview)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
  }
}

@Composable
fun RestorePromptContent(
  snapshot: WorkspaceSnapshot,
  selectedKeys: Set<String>,
  onToggle: (String) -> Unit,
  onRestore: () -> Unit,
  onNotNow: () -> Unit,
  onClose: () -> Unit,
  autoAlways: Boolean = true,
  onAutoAlwaysChange: (Boolean) -> Unit = {},
  countdownSeconds: Int = -1,
) {
  Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            if (countdownSeconds > 0) BootCountdownOverlay.formatCountdownHeadline(countdownSeconds)
            else "Restore previous windows?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Text(summary(snapshot), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("prompt_close")) { Text("✕") }
      }
      if (countdownSeconds > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            "Restoring automatically in $countdownSeconds s… Press Cancel to skip.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("countdown"),
          )
          LinearProgressIndicator(
            progress = { (countdownSeconds / RestorePromptActivity.AUTO_COUNTDOWN_S.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      WorkspaceDesktopPreview(snapshot, selectedKeys, Modifier.widthIn(max = 520.dp).align(Alignment.CenterHorizontally))
      LazyColumn(Modifier.heightIn(max = 200.dp).testTag("window_list")) {
        items(snapshot.windows, key = { it.windowKey }) { w ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
              checked = w.windowKey in selectedKeys,
              onCheckedChange = { onToggle(w.windowKey) },
              modifier = Modifier.testTag("check_${w.windowKey}"),
            )
            val where = (if (w.displayId == 0) "" else "Display ${w.displayId} · ") + "Desk ${w.deskIndex + 1}"
            val tabs = tabLabel(w)
            Text("${w.label.ifEmpty { w.packageName }} — $where · ${w.visualState.name.lowercase()}$tabs", style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = autoAlways, onCheckedChange = onAutoAlwaysChange, modifier = Modifier.testTag("auto_always"))
        Text(
          "Restore automatically after reboot (${RestorePromptActivity.AUTO_COUNTDOWN_S}-second cancelable countdown)",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (countdownSeconds > 0) {
          OutlinedButton(onClick = onRestore, enabled = selectedKeys.isNotEmpty(), modifier = Modifier.testTag("restore_windows")) {
            Text("Restore now")
          }
          Spacer(Modifier.width(8.dp))
          Button(
            onClick = onNotNow,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("not_now"),
          ) {
            Text("Cancel")
          }
        } else {
          OutlinedButton(onClick = onNotNow, modifier = Modifier.testTag("not_now")) { Text("Not Now") }
          Spacer(Modifier.width(8.dp))
          Button(onClick = onRestore, enabled = selectedKeys.isNotEmpty(), modifier = Modifier.testTag("restore_windows")) {
            Text("Restore Windows")
          }
        }
      }
    }
  }
}
