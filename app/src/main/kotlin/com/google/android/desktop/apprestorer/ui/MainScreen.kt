package com.google.android.desktop.apprestorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.desktop.apprestorer.boot.BootCountdownOverlay
import com.google.android.desktop.apprestorer.model.CaptureReason
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import com.google.android.desktop.apprestorer.permissions.PermissionState
import com.google.android.desktop.apprestorer.permissions.RequiredPermission
import com.google.android.desktop.apprestorer.restore.RestoreProgress

data class MainUiState(
  val permissions: PermissionState = PermissionState(),
  val serviceConnected: Boolean = false,
  val live: WorkspaceSnapshot = WorkspaceSnapshot(),
  val pending: WorkspaceSnapshot? = null,
  val restore: RestoreProgress = RestoreProgress(),
  val frozen: CaptureReason? = null,
  val diagnosticsStatus: String? = null,
  val autoRestore: Boolean = true,
) {
  val isArmed: Boolean get() = permissions.allGranted && serviceConnected
}

@Composable
fun MainScreen(
  state: MainUiState,
  onGrant: (RequiredPermission) -> Unit,
  onOpenAppInfo: () -> Unit,
  onCapture: () -> Unit,
  onOpenRestorePrompt: () -> Unit,
  onDismissPending: () -> Unit = {},
  onExportDiagnostics: () -> Unit = {},
  onAutoRestoreChange: (Boolean) -> Unit = {},
) {
  Scaffold { padding ->
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
      Column(
        Modifier.widthIn(max = 920.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Header(state)
        if (!state.permissions.allGranted || !state.serviceConnected) {
          PermissionOnboardingSection(state.permissions, onGrant, onOpenAppInfo)
        }
        Card(Modifier.fillMaxWidth()) {
          Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Restore automatically after reboot", fontWeight = FontWeight.SemiBold)
              Text(
                if (state.autoRestore)
                  "After unlock, a quick popup counts down ${BootCountdownOverlay.AUTO_COUNTDOWN_SECONDS} seconds before restoring (press Cancel or Esc to skip)."
                else "After unlock, a popup asks whether to restore your windows.",
                style = MaterialTheme.typography.bodySmall,
              )
            }
            androidx.compose.material3.Switch(
              checked = state.autoRestore,
              onCheckedChange = onAutoRestoreChange,
              modifier = Modifier.testTag("auto_restore_switch"),
            )
          }
        }
        state.pending?.let { p ->
          Card(Modifier.fillMaxWidth().testTag("pending_card")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Windows from before your last reboot are waiting", fontWeight = FontWeight.SemiBold)
              Text(summary(p), style = MaterialTheme.typography.bodySmall)
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenRestorePrompt, modifier = Modifier.testTag("open_restore_prompt")) { Text("Review & restore…") }
                OutlinedButton(onClick = onDismissPending, modifier = Modifier.testTag("dismiss_pending")) { Text("Dismiss") }
              }
            }
          }
        }
        if (state.restore.running) {
          Column {
            Text("Restoring ${state.restore.currentLabel ?: ""} (${state.restore.done}/${state.restore.total})")
            LinearProgressIndicator(
              progress = { if (state.restore.total == 0) 0f else state.restore.done / state.restore.total.toFloat() },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
        state.restore.report?.let { r ->
          Text(
            "Last restore: ${r.attached}/${r.planned} windows attached, ${r.tabsReopened} tabs reopened, ${r.phantomsPurged} phantom windows purged" +
              if (r.failedLabels.isNotEmpty()) ". Failed: ${r.failedLabels.joinToString()}" else "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("restore_report"),
          )
        }
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Live workspace", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
              OutlinedButton(onClick = onCapture, enabled = state.serviceConnected, modifier = Modifier.testTag("capture_button")) {
                Text("Capture Workspace")
              }
            }
            Text(summary(state.live), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("live_summary"))
            Text(
              "Capture Workspace also briefly visits Chrome tabs whose address hasn't been seen yet, and peeks at Overview to find apps on other desks.",
              style = MaterialTheme.typography.bodySmall,
            )
            WorkspaceDesktopPreview(state.live)
            WindowList(state.live)
            Row(verticalAlignment = Alignment.CenterVertically) {
              OutlinedButton(onClick = onExportDiagnostics, enabled = state.serviceConnected, modifier = Modifier.testTag("export_diag")) {
                Text("Export diagnostics")
              }
              state.diagnosticsStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp).testTag("diag_status"))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Header(state: MainUiState) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Googlebook App Restorer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text("Restores windows, desks, displays & Chrome tabs after reboot", style = MaterialTheme.typography.bodySmall)
    }
    val (text, color) = when {
      state.isArmed && state.frozen != null -> "Frozen · ${state.frozen.name.lowercase().replace('_', ' ')}" to Color(0xFFE37400)
      state.isArmed -> "Armed & Capturing Before Reboot" to Color(0xFF188038)
      else -> "Setup required" to Color(0xFFD93025)
    }
    Text(
      text,
      color = Color.White,
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp).testTag("status_pill"),
    )
  }
}

@Composable
fun WindowList(snapshot: WorkspaceSnapshot) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    for (w in snapshot.windows) {
      val where = (if (w.displayId == 0) "Built-in" else "Display ${w.displayId}") + " · Desk ${w.deskIndex + 1}"
      val tabs = tabLabel(w)
      val twa = if (w.hostBrowserPackage != null) " · TWA" else ""
      Text(
        "${w.label.ifEmpty { w.packageName }} — $where · ${w.visualState.name.lowercase()} · ${w.bounds.width}×${w.bounds.height} @ ${w.bounds.left},${w.bounds.top} · z${w.zOrderLayer}$tabs$twa",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

fun tabLabel(w: com.google.android.desktop.apprestorer.model.CapturedWindow): String {
  val count = maxOf(w.tabCount, w.tabUrls.size)
  if (count == 0) return ""
  return if (w.tabUrls.size < count) " · $count tabs (${w.tabUrls.size} addresses known)" else " · $count tabs"
}

fun summary(s: WorkspaceSnapshot): String {
  val displays = (s.displays.map { it.displayId } + s.windows.map { it.displayId }).distinct().size.coerceAtLeast(1)
  val desks = s.totalDeskCountByDisplay.values.sum().coerceAtLeast(1)
  val minimized = s.windows.count { !it.isVisible }
  val tabs = s.windows.sumOf { maxOf(it.tabCount, it.tabUrls.size) }
  return "${s.windows.size} windows · $desks desk(s) · $displays display(s) · $minimized minimized · $tabs Chrome tabs"
}
