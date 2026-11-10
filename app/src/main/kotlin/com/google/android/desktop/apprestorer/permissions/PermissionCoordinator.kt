package com.google.android.desktop.apprestorer.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import com.google.android.desktop.apprestorer.service.WorkspaceCaptureAccessibilityService

enum class RequiredPermission { ACCESSIBILITY, OVERLAY, USAGE_ACCESS, NOTIFICATIONS }

data class PermissionState(val granted: Map<RequiredPermission, Boolean> = emptyMap()) {
  fun isGranted(p: RequiredPermission) = granted[p] == true
  val grantedCount: Int get() = RequiredPermission.entries.count { isGranted(it) }
  val allGranted: Boolean get() = grantedCount == RequiredPermission.entries.size
}

/**
 * Checks the four onboarding permissions and builds deep-linked Settings intents. Everything is
 * user-grantable on consumer builds — no adb, no root.
 */
class PermissionCoordinator(private val context: Context) {
  private val component = ComponentName(context, WorkspaceCaptureAccessibilityService::class.java)

  fun state(): PermissionState = PermissionState(
    RequiredPermission.entries.associateWith { isGranted(it) }
  )

  fun isGranted(p: RequiredPermission): Boolean = when (p) {
    RequiredPermission.ACCESSIBILITY -> isAccessibilityEnabled()
    RequiredPermission.OVERLAY -> Settings.canDrawOverlays(context)
    RequiredPermission.USAGE_ACCESS -> hasUsageAccess()
    RequiredPermission.NOTIFICATIONS ->
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }

  fun isAccessibilityEnabled(): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
  }

  private fun hasUsageAccess(): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    return if (mode == AppOpsManager.MODE_DEFAULT) {
      context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    } else mode == AppOpsManager.MODE_ALLOWED
  }

  /** Deep link for a Settings-granted permission (null for the runtime notification permission). */
  fun settingsIntent(p: RequiredPermission): Intent? {
    val pkgUri = Uri.parse("package:${context.packageName}")
    return when (p) {
      RequiredPermission.ACCESSIBILITY -> Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        .takeIf { it.resolveActivity(context.packageManager) != null }
        ?: Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(EXTRA_FRAGMENT_ARG_KEY, component.flattenToString())
      RequiredPermission.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
      RequiredPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri)
        .takeIf { it.resolveActivity(context.packageManager) != null }
        ?: Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
      RequiredPermission.NOTIFICATIONS -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  fun fallbackIntent(p: RequiredPermission): Intent = when (p) {
    RequiredPermission.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    RequiredPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    RequiredPermission.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    RequiredPermission.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
  }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /**
   * App info page: for sideloaded installs Android 13+ requires "Allow restricted settings" (⋮
   * menu) before the accessibility toggle can be enabled.
   */
  fun appDetailsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  companion object {
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    /** Pre-focuses our service's detail page where supported (falls back to the list). */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
  }
}
