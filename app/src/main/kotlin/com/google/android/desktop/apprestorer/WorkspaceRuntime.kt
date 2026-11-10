package com.google.android.desktop.apprestorer

import android.content.Context
import android.util.Log
import com.google.android.desktop.apprestorer.boot.BootCountdownOverlay
import com.google.android.desktop.apprestorer.boot.BootPromptLauncher
import com.google.android.desktop.apprestorer.boot.Notifications
import com.google.android.desktop.apprestorer.capture.UsageStatsWorkspaceReconciler
import com.google.android.desktop.apprestorer.capture.WorkspaceCaptureEngine
import com.google.android.desktop.apprestorer.data.WorkspaceRepository
import com.google.android.desktop.apprestorer.model.CaptureReason
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import com.google.android.desktop.apprestorer.platform.PackageManagerAppCatalog
import com.google.android.desktop.apprestorer.platform.UsageStatsEventSource
import com.google.android.desktop.apprestorer.restore.AccessibilityRestoreDriver
import com.google.android.desktop.apprestorer.restore.BasicRestoreDriver
import com.google.android.desktop.apprestorer.restore.RestorePlanner
import com.google.android.desktop.apprestorer.restore.RestoreProgress
import com.google.android.desktop.apprestorer.restore.WorkspaceRestoreCoordinator
import com.google.android.desktop.apprestorer.service.DiagnosticsExporter
import com.google.android.desktop.apprestorer.service.DiagnosticsLog
import com.google.android.desktop.apprestorer.service.WorkspaceCaptureAccessibilityService
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide hub shared by the accessibility service, the foreground service and the UI (all in
 * one process). Owns the capture engine, debounced persistence and restore execution.
 */
class WorkspaceRuntime private constructor(context: Context) {
  val appContext: Context = context.applicationContext
  val repository = WorkspaceRepository(appContext)
  val catalog = PackageManagerAppCatalog(appContext)
  private val reconciler = UsageStatsWorkspaceReconciler(UsageStatsEventSource(appContext))
  val engine = WorkspaceCaptureEngine(catalog, appContext.packageName, reconciler, logger = DiagnosticsLog::add)
  val planner = RestorePlanner(catalog)
  private val bootToken: String = repository.currentBootToken().encode()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var persistJob: Job? = null
  private var serviceRef: WeakReference<WorkspaceCaptureAccessibilityService>? = null

  private val _live = MutableStateFlow(WorkspaceSnapshot())
  val live: StateFlow<WorkspaceSnapshot> = _live.asStateFlow()
  private val _pending = MutableStateFlow<WorkspaceSnapshot?>(null)
  val pending: StateFlow<WorkspaceSnapshot?> = _pending.asStateFlow()
  private val _serviceConnected = MutableStateFlow(false)
  val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()
  private val _restore = MutableStateFlow(RestoreProgress())
  val restoreProgress: StateFlow<RestoreProgress> = _restore.asStateFlow()
  private val _autoRestore = MutableStateFlow(repository.autoRestore)
  val autoRestore: StateFlow<Boolean> = _autoRestore.asStateFlow()

  fun setAutoRestore(enabled: Boolean) {
    repository.autoRestore = enabled
    _autoRestore.value = enabled
  }

  private val _frozen = MutableStateFlow<CaptureReason?>(null)
  val frozen: StateFlow<CaptureReason?> = _frozen.asStateFlow()

  private val _diagnosticsStatus = MutableStateFlow<String?>(null)
  val diagnosticsStatus: StateFlow<String?> = _diagnosticsStatus.asStateFlow()

  fun exportDiagnostics() {
    val s = service ?: run { _diagnosticsStatus.value = "Accessibility service not connected"; return }
    _diagnosticsStatus.value = "Exporting…"
    scope.launch {
      _diagnosticsStatus.value = try {
        "Saved to " + DiagnosticsExporter.export(s, this@WorkspaceRuntime)
      } catch (e: Exception) {
        "Export failed: ${e.message}"
      }
    }
  }

  /** Set by RestorePromptActivity / BootCountdownOverlay so restores start only after it closes. */
  @Volatile var promptVisible: Boolean = false
  /** Suppresses opportunistic event-driven captures while restore / deep-scan automation runs. */
  @Volatile var automationBusy: Boolean = false
  /** True until this boot's pending workspace prompt has been shown (or no pending snapshot exists). */
  @Volatile var needsBootPromptCheck: Boolean = true

  init {
    repository.promoteIfNewBoot()
    refreshPending()
    scope.launch(Dispatchers.Default) { reconciler.probeCapabilities(System.currentTimeMillis()) }
  }

  val service: WorkspaceCaptureAccessibilityService? get() = serviceRef?.get()

  fun maybeShowBootPrompt() {
    if (!needsBootPromptCheck) return
    val current = repository.currentBootToken()
    if (repository.wasPromptedThisBoot(current)) {
      needsBootPromptCheck = false
      return
    }
    if (_pending.value == null) {
      refreshPending()
      if (_pending.value == null) {
        needsBootPromptCheck = false
        return
      }
    }
    BootPromptLauncher.maybePrompt(appContext)
    if (repository.wasPromptedThisBoot(current)) {
      needsBootPromptCheck = false
    }
  }

  fun onServiceConnected(s: WorkspaceCaptureAccessibilityService) {
    serviceRef = WeakReference(s)
    _serviceConnected.value = true
    refreshPending()
    BootPromptLauncher.scheduleUnlockChecks(appContext)
  }

  fun onServiceDisconnected(s: WorkspaceCaptureAccessibilityService) {
    if (serviceRef?.get() === s) serviceRef = null
    _serviceConnected.value = false
  }

  fun onEngineMaybeChanged(changed: Boolean) {
    if (!changed) return
    _live.value = engine.snapshot(CaptureReason.LIVE, bootToken)
    persistJob?.cancel()
    persistJob = scope.launch {
      delay(PERSIST_DEBOUNCE_MS)
      val snap = _live.value
      withContext(Dispatchers.IO) { repository.saveLive(snap) }
    }
  }

  /** Synchronous flush (shutdown path): AtomicFile + fd.sync on the calling thread. */
  fun persistNow(reason: CaptureReason) {
    persistJob?.cancel()
    val snap = engine.snapshot(reason, bootToken)
    _live.value = snap
    repository.saveLive(snap)
  }

  fun onPowerMenuShown() {
    if (engine.isFrozen) return
    persistNow(CaptureReason.POWER_MENU_PRE_FREEZE)
    engine.freeze(CaptureReason.POWER_MENU_PRE_FREEZE)
    _frozen.value = engine.frozenReason
  }

  fun onPowerMenuGone() {
    if (engine.frozenReason == CaptureReason.POWER_MENU_PRE_FREEZE) {
      engine.unfreeze()
      _frozen.value = engine.frozenReason
    }
  }

  fun onShutdown() {
    persistNow(CaptureReason.SHUTDOWN_BROADCAST)
    engine.freeze(CaptureReason.SHUTDOWN_BROADCAST)
    _frozen.value = engine.frozenReason
  }

  fun onScreenOff() {
    if (engine.isFrozen) return
    persistNow(CaptureReason.SCREEN_OFF)
    engine.freeze(CaptureReason.SCREEN_OFF)
    _frozen.value = engine.frozenReason
  }

  fun onUserPresent() {
    if (engine.frozenReason == CaptureReason.SCREEN_OFF) {
      engine.unfreeze()
      _frozen.value = engine.frozenReason
    }
    if (needsBootPromptCheck) BootPromptLauncher.scheduleUnlockChecks(appContext)
  }

  fun manualCapture(deepScan: Boolean = true) {
    val s = service ?: return
    scope.launch { s.performManualCapture(deepScan) }
  }

  fun refreshPending() {
    _pending.value = repository.loadPending()
  }

  /** "Not Now" / "Dismiss": discard the previous-boot snapshot. */
  fun dismissPending() {
    BootCountdownOverlay.dismiss()
    repository.clearPending()
    Notifications.cancelRestoreNotification(appContext)
    refreshPending()
  }

  fun restore(snapshot: WorkspaceSnapshot, selectedKeys: Set<String>?, clearPendingAfter: Boolean) {
    if (_restore.value.running) return
    BootCountdownOverlay.dismiss()
    Notifications.cancelRestoreNotification(appContext)
    scope.launch {
      // Right after boot the accessibility service may bind a few seconds after unlock; desks,
      // minimize and geometry correction need it, so wait for it before falling back.
      if (service == null) {
        kotlinx.coroutines.withTimeoutOrNull(SERVICE_WAIT_MS) {
          serviceConnected.first { it }
        }
      }
      val s = service
      DiagnosticsLog.add("restore start: service=${s != null} windows=${selectedKeys?.size ?: snapshot.windows.size}")
      val driver = if (s != null) AccessibilityRestoreDriver(s, this@WorkspaceRuntime) else BasicRestoreDriver(this@WorkspaceRuntime)
      automationBusy = true
      try {
        WorkspaceRestoreCoordinator(driver, planner, DiagnosticsLog::add).restore(snapshot, selectedKeys) { _restore.value = it }
      } catch (e: Exception) {
        Log.e(TAG, "Restore failed", e)
        _restore.value = RestoreProgress(running = false)
      } finally {
        delay(800)
        automationBusy = false
        service?.captureNow()
      }
      if (clearPendingAfter) {
        repository.clearPending()
        refreshPending()
      }
    }
  }

  companion object {
    private const val TAG = "WorkspaceRuntime"
    private const val PERSIST_DEBOUNCE_MS = 400L
    private const val SERVICE_WAIT_MS = 15_000L

    @Volatile private var INSTANCE: WorkspaceRuntime? = null

    fun get(context: Context): WorkspaceRuntime =
      INSTANCE ?: synchronized(this) { INSTANCE ?: WorkspaceRuntime(context).also { INSTANCE = it } }
  }
}
