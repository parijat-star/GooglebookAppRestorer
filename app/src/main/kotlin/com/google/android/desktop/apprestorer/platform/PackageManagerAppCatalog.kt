package com.google.android.desktop.apprestorer.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import com.google.android.desktop.apprestorer.capture.AppCatalog
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.restore.LaunchCapabilities

/** PackageManager-backed [AppCatalog] + [LaunchCapabilities] with a short-lived launcher cache. */
class PackageManagerAppCatalog(context: Context) : AppCatalog, LaunchCapabilities {
  private val appContext = context.applicationContext
  private val pm: PackageManager = appContext.packageManager

  private data class Entry(val pkg: String, val label: String, val component: String, val isWebApp: Boolean)

  @Volatile private var entries: List<Entry> = emptyList()
  @Volatile private var systemSurfaces: Set<String> = emptySet()
  @Volatile private var homePackages: Set<String> = emptySet()
  @Volatile private var loadedAt = 0L

  private fun ensureLoaded() {
    val now = System.currentTimeMillis()
    if (entries.isNotEmpty() && now - loadedAt < CACHE_TTL_MS) return
    synchronized(this) {
      if (entries.isNotEmpty() && now - loadedAt < CACHE_TTL_MS) return
      val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      entries = try {
        pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
          .map { ri ->
            val ai = ri.activityInfo
            val meta = ai.metaData
            val webApp = KnownPackages.isWebApkPackage(ai.packageName) ||
              meta?.containsKey(TWA_DEFAULT_URL) == true ||
              meta?.keySet()?.any { it.startsWith("org.chromium.webapk") } == true
            Entry(ai.packageName, ri.loadLabel(pm).toString(), "${ai.packageName}/${ai.name}", webApp)
          }
      } catch (_: RuntimeException) {
        emptyList()
      }
      val home = try {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.ResolveInfoFlags.of(0))
          .map { it.activityInfo.packageName }
          // Settings registers FallbackHome for CATEGORY_HOME; it is a regular desktop app.
          .filter { it !in KnownPackages.SETTINGS_PACKAGES }
      } catch (_: RuntimeException) {
        emptyList()
      }
      val imes = try {
        appContext.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }.orEmpty()
      } catch (_: RuntimeException) {
        emptyList()
      }
      homePackages = home.toSet()
      systemSurfaces = (home + imes + KnownPackages.TRANSIENT_SYSTEM_PACKAGES + listOf(KnownPackages.SYSTEM_UI, "android")).toSet()
      loadedAt = now
    }
  }

  override fun labelFor(packageName: String): String {
    ensureLoaded()
    entries.firstOrNull { it.pkg == packageName }?.let { return it.label }
    return try {
      pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
    } catch (_: PackageManager.NameNotFoundException) {
      packageName
    }
  }

  override fun isInstalled(packageName: String): Boolean =
    try {
      pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)); true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

  override fun isSystemSurface(packageName: String): Boolean {
    ensureLoaded()
    return packageName in systemSurfaces
  }

  fun isHomePackage(packageName: String): Boolean {
    ensureLoaded()
    return packageName in homePackages
  }

  override fun findWebAppPackageByLabel(label: String): String? {
    ensureLoaded()
    return entries.filter { it.label.equals(label.trim(), ignoreCase = true) && !KnownPackages.isChrome(it.pkg) }
      .sortedBy { if (it.isWebApp) 0 else 1 }
      .firstOrNull()?.pkg
  }

  override fun findPackageByLabel(label: String): String? {
    ensureLoaded()
    return entries.filter { it.label.equals(label.trim(), ignoreCase = true) }
      .sortedBy { if (KnownPackages.isChrome(it.pkg)) 0 else 1 }
      .firstOrNull()?.pkg
  }

  override fun launcherComponent(packageName: String): String? {
    ensureLoaded()
    entries.firstOrNull { it.pkg == packageName }?.let { return it.component }
    return pm.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
  }

  override fun isExportedComponent(component: String): Boolean {
    val cn = ComponentName.unflattenFromString(component) ?: return false
    return try {
      pm.getActivityInfo(cn, PackageManager.ComponentInfoFlags.of(0)).exported
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
  }

  companion object {
    private const val CACHE_TTL_MS = 60_000L
    private const val TWA_DEFAULT_URL = "android.support.customtabs.trusted.DEFAULT_URL"
  }
}
