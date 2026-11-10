package com.google.android.desktop.apprestorer.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import java.io.File
import java.io.FileOutputStream

/**
 * Persists snapshots in Device-Protected Storage (readable before first unlock).
 *
 * - `live_workspace.json`: continuously updated snapshot of the current boot.
 * - `pending_restore.json`: the previous boot's final snapshot, promoted once per boot and kept for
 *   that boot unless restored or dismissed.
 *
 * Writes use AtomicFile + fsync so a power cut can never leave a truncated file.
 */
class WorkspaceRepository(
  context: Context,
  private val bootTokenProvider: (() -> BootToken)? = null,
) {
  private val appContext = context.applicationContext
  private val dpContext: Context = appContext.createDeviceProtectedStorageContext()
  private val dir: File = File(dpContext.filesDir, "workspace").apply { mkdirs() }
  private val liveFile = AtomicFile(File(dir, "live_workspace.json"))
  private val pendingFile = AtomicFile(File(dir, "pending_restore.json"))
  private val prefs = dpContext.getSharedPreferences("restorer_state", Context.MODE_PRIVATE)
  private val lock = Any()

  fun currentBootToken(): BootToken {
    bootTokenProvider?.let { return it() }
    val bootCount = try {
      Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
      -1
    }
    val epoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    return BootToken(bootCount, epoch)
  }

  fun loadLive(): WorkspaceSnapshot? = read(liveFile)

  fun loadPending(): WorkspaceSnapshot? = read(pendingFile)?.let(::sanitizeRestorable)?.takeIf { !it.isEmpty }

  private fun sanitizeRestorable(snapshot: WorkspaceSnapshot): WorkspaceSnapshot {
    val kept = snapshot.windows.filterNot { KnownPackages.isExcludedCompanionApp(it.packageName, it.label) }
    return if (kept.size == snapshot.windows.size) snapshot else snapshot.copy(windows = kept)
  }

  /**
   * Saves the live snapshot. Before overwriting a snapshot from a previous boot it is promoted to
   * the pending-restore slot so a fresh boot can never clobber the pre-reboot workspace.
   */
  fun saveLive(snapshot: WorkspaceSnapshot) = synchronized(lock) {
    promoteIfNewBootLocked()
    prefs.edit().putString(KEY_LAST_SEEN_BOOT, currentBootToken().encode()).apply()
    write(liveFile, snapshot)
  }

  /** Idempotent; safe to call from boot receivers, the service and the UI. */
  fun promoteIfNewBoot(): Boolean = synchronized(lock) { promoteIfNewBootLocked() }

  private fun promoteIfNewBootLocked(): Boolean {
    val current = currentBootToken()
    val lastSeen = prefs.getString(KEY_LAST_SEEN_BOOT, null)
    val live = read(liveFile)
    if (live != null && BootToken.isNewBoot(live.bootTokenAtCapture, current)) {
      val clean = sanitizeRestorable(live)
      if (!clean.isEmpty) {
        write(pendingFile, clean)
        prefs.edit()
          .putString(KEY_PENDING_FROM_BOOT, clean.bootTokenAtCapture)
          .putString(KEY_LAST_SEEN_BOOT, current.encode())
          .commit()
        Log.i(TAG, "Promoted ${clean.windows.size} windows from previous boot to pending restore")
      } else {
        clearPendingLocked()
        prefs.edit().putString(KEY_LAST_SEEN_BOOT, current.encode()).commit()
      }
      liveFile.delete()
      return !clean.isEmpty
    }
    // Crossed into a new boot without any live snapshot from the intermediate boot (e.g. user
    // cancelled restore on the previous boot and rebooted again without opening apps): expire
    // older pending state so cancelled windows don't come back on later boots.
    if (lastSeen != null && BootToken.isNewBoot(lastSeen, current)) {
      clearPendingLocked()
      prefs.edit().putString(KEY_LAST_SEEN_BOOT, current.encode()).commit()
      return false
    }
    if (lastSeen == null) {
      prefs.edit().putString(KEY_LAST_SEEN_BOOT, current.encode()).apply()
    }
    return false
  }

  fun clearPending() = synchronized(lock) { clearPendingLocked() }

  private fun clearPendingLocked() {
    pendingFile.delete()
    prefs.edit().remove(KEY_PENDING_FROM_BOOT).apply()
  }

  /** Records that the restore prompt was shown during [token]'s boot. */
  fun markPrompted(token: BootToken) = prefs.edit().putString(KEY_PROMPTED_BOOT, token.encode()).commit()

  fun wasPromptedThisBoot(current: BootToken): Boolean =
    BootToken.parse(prefs.getString(KEY_PROMPTED_BOOT, null))?.isSameBootAs(current) == true

  /** "Restore automatically after reboot" (enabled by default; 6-second cancelable countdown). */
  var autoRestore: Boolean
    get() = prefs.getBoolean(KEY_AUTO_RESTORE, true)
    set(value) { prefs.edit().putBoolean(KEY_AUTO_RESTORE, value).apply() }

  private fun read(file: AtomicFile): WorkspaceSnapshot? =
    try {
      if (!file.baseFile.exists()) null
      else WorkspaceSnapshotCodec.decode(String(file.readFully(), Charsets.UTF_8))
    } catch (e: Exception) {
      Log.w(TAG, "Failed to read ${file.baseFile.name}", e)
      null
    }

  private fun write(file: AtomicFile, snapshot: WorkspaceSnapshot) {
    var out: FileOutputStream? = null
    try {
      out = file.startWrite()
      out.write(WorkspaceSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
      out.flush()
      out.fd.sync()
      file.finishWrite(out)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write ${file.baseFile.name}", e)
      out?.let { file.failWrite(it) }
    }
  }

  companion object {
    private const val TAG = "WorkspaceRepository"
    private const val KEY_PROMPTED_BOOT = "prompted_boot_token"
    private const val KEY_PENDING_FROM_BOOT = "pending_from_boot"
    private const val KEY_LAST_SEEN_BOOT = "last_seen_boot_token"
    private const val KEY_AUTO_RESTORE = "auto_restore_on_reboot"
  }
}
