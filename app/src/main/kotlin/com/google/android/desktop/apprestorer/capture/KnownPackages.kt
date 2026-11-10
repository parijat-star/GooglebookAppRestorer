package com.google.android.desktop.apprestorer.capture

/** Well-known Googlebook package / class names. */
object KnownPackages {
  val CHROME_PACKAGES = setOf(
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "org.chromium.chrome",
  )
  const val CHROME = "com.android.chrome"
  const val CHROME_MAIN_ACTIVITY = "com.google.android.apps.chrome.Main"

  const val GEMINI = "com.google.android.apps.bard"
  const val GOOGLE_QUICK_SEARCH_BOX = "com.google.android.googlequicksearchbox"

  val FILES_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
  const val FILES_ACTIVITY = "com.android.documentsui.files.FilesActivity"

  val SETTINGS_PACKAGES = setOf("com.android.settings", "com.google.android.settings")

  const val PLAY_STORE = "com.android.vending"
  const val CALCULATOR = "com.google.android.calculator"

  const val SYSTEM_UI = "com.android.systemui"

  /** Transient system dialogs and cross-device companion surfaces excluded from workspace restore. */
  val TRANSIENT_SYSTEM_PACKAGES = setOf(
    "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.android.intentresolver", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    "com.google.ambient.streaming", "com.android.companiondevicemanager", "com.google.android.companiondevicesupport",
  )

  private val EXCLUDED_COMPANION_PACKAGE_TOKENS = listOf(
    "devicelink", "device_link", "crossdevice", "cross_device",
    "connectedphone", "connected_phone", "phonehub", "phone_hub",
    "com.google.ambient.", ".eche",
  )

  private val EXCLUDED_COMPANION_LABEL_TOKENS = listOf(
    "device link", "devicelink", "connected phone",
    "cross-device", "cross device", "phone hub",
  )

  /**
   * True for Connected Phone / Cross-Device Services / Device Link Debug popups & apps, which open
   * transient surfaces or dedicated streaming desks and must not be captured or restored.
   */
  fun isExcludedCompanionApp(
    pkg: String?,
    appLabel: String? = null,
    captionLabel: String? = null,
    windowTitle: String? = null,
  ): Boolean {
    if (pkg != null) {
      if (isChrome(pkg) || pkg in SETTINGS_PACKAGES || pkg in FILES_PACKAGES) return false
      val p = pkg.lowercase()
      if (p in TRANSIENT_SYSTEM_PACKAGES || EXCLUDED_COMPANION_PACKAGE_TOKENS.any { it in p }) return true
    }
    return isExcludedCompanionLabel(appLabel) ||
      isExcludedCompanionLabel(captionLabel) ||
      (pkg != null && !isChrome(pkg) && isExcludedCompanionLabel(windowTitle))
  }

  fun isExcludedCompanionLabel(label: String?): Boolean {
    val l = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    return EXCLUDED_COMPANION_LABEL_TOKENS.any { it in l }
  }

  fun isChrome(pkg: String?) = pkg != null && pkg in CHROME_PACKAGES

  fun isWebApkPackage(pkg: String) = pkg.startsWith("org.chromium.webapk.") || pkg.endsWith(".twa")

  /** Gemini Robin desktop window or floaty hosted inside the Google app. */
  fun isGeminiRobinClassName(className: String?): Boolean {
    if (className == null) return false
    val c = className.lowercase()
    return ".robin." in c || "floatyactivity" in c || "bardentrypoint" in c || ".bard." in c
  }

  /** Google Search / Assistant overlays that must never be restored. */
  fun isSearchOverlayClassName(className: String?): Boolean {
    if (className == null) return false
    val c = className.lowercase()
    return "opaactivity" in c || "searchactivity" in c || "searchnow" in c || "velvet" in c
  }

  /** Chrome classes that host a TWA / WebAPK / custom tab instead of a browser window. */
  fun isChromeWebAppClassName(className: String?): Boolean {
    if (className == null) return false
    return listOf("CustomTabActivity", "WebappActivity", "WebApkActivity", "TrustedWebActivity", "SameTaskWebApkActivity")
      .any { className.contains(it) }
  }
}
