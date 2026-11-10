package com.google.android.desktop.apprestorer

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.desktop.apprestorer.boot.BootPromptLauncher
import com.google.android.desktop.apprestorer.permissions.PermissionCoordinator
import com.google.android.desktop.apprestorer.permissions.PermissionState
import com.google.android.desktop.apprestorer.permissions.RequiredPermission
import com.google.android.desktop.apprestorer.service.WorkspaceMonitorForegroundService
import com.google.android.desktop.apprestorer.ui.MainScreen
import com.google.android.desktop.apprestorer.ui.MainUiState
import com.google.android.desktop.apprestorer.ui.RestorerTheme

class MainActivity : ComponentActivity() {
  private lateinit var permissions: PermissionCoordinator
  private lateinit var runtime: WorkspaceRuntime
  private var permissionState by mutableStateOf(PermissionState())

  private val notificationRequest =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    permissions = PermissionCoordinator(this)
    runtime = WorkspaceRuntime.get(this)
    setContent {
      RestorerTheme {
        val live by runtime.live.collectAsState()
        val pending by runtime.pending.collectAsState()
        val connected by runtime.serviceConnected.collectAsState()
        val restore by runtime.restoreProgress.collectAsState()
        val frozen by runtime.frozen.collectAsState()
        val diag by runtime.diagnosticsStatus.collectAsState()
        val auto by runtime.autoRestore.collectAsState()
        MainScreen(
          state = MainUiState(permissionState, connected, live, pending, restore, frozen, diag, auto),
          onGrant = ::grant,
          onOpenAppInfo = { safeStart(permissions.appDetailsIntent()) },
          onCapture = { runtime.manualCapture(deepScan = true) },
          onOpenRestorePrompt = { BootPromptLauncher.launchPrompt(this, manualReview = true) },
          onDismissPending = { runtime.dismissPending() },
          onExportDiagnostics = { runtime.exportDiagnostics() },
          onAutoRestoreChange = { runtime.setAutoRestore(it) },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Live auto-refresh on return from Settings.
    refreshPermissions()
    runtime.refreshPending()
    if (permissionState.isGranted(RequiredPermission.ACCESSIBILITY)) WorkspaceMonitorForegroundService.start(this)
  }

  private fun refreshPermissions() {
    permissionState = permissions.state()
  }

  private fun grant(p: RequiredPermission) {
    if (p == RequiredPermission.NOTIFICATIONS) {
      notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }
    val intent = permissions.settingsIntent(p) ?: return
    if (!safeStart(intent)) safeStart(permissions.fallbackIntent(p))
  }

  private fun safeStart(intent: android.content.Intent): Boolean =
    try {
      startActivity(intent); true
    } catch (_: ActivityNotFoundException) {
      false
    } catch (_: SecurityException) {
      false
    }
}
