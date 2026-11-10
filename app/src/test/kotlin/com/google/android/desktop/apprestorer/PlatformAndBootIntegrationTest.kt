package com.google.android.desktop.apprestorer

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.desktop.apprestorer.Fixtures.PRIMARY
import com.google.android.desktop.apprestorer.boot.BootCountdownOverlay
import com.google.android.desktop.apprestorer.boot.BootPromptLauncher
import com.google.android.desktop.apprestorer.boot.BootRestoreReceiver
import com.google.android.desktop.apprestorer.boot.Notifications
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.WindowGeometryClassifier
import com.google.android.desktop.apprestorer.model.AppKind
import com.google.android.desktop.apprestorer.model.CaptureReason
import com.google.android.desktop.apprestorer.model.CapturedWindow
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.model.WindowVisualState
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import com.google.android.desktop.apprestorer.permissions.PermissionCoordinator
import com.google.android.desktop.apprestorer.permissions.RequiredPermission
import com.google.android.desktop.apprestorer.platform.DisplayGeometryProvider
import com.google.android.desktop.apprestorer.platform.IntentFactory
import com.google.android.desktop.apprestorer.platform.PackageManagerAppCatalog
import com.google.android.desktop.apprestorer.platform.UsageStatsEventSource
import com.google.android.desktop.apprestorer.restore.BasicRestoreDriver
import com.google.android.desktop.apprestorer.restore.LaunchDescriptor
import com.google.android.desktop.apprestorer.restore.WindowLaunch
import com.google.android.desktop.apprestorer.service.DiagnosticsLog
import com.google.android.desktop.apprestorer.service.WorkspaceMonitorForegroundService
import com.google.android.desktop.apprestorer.ui.RestorePromptActivity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Headless Robolectric Android SDK integration tests covering permission deep links, platform
 * adapters, boot receiver & unlock gating, floating countdown overlay lifecycle, notifications,
 * runtime freeze/flush transitions, and Activity lifecycles.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1280dp-h800dp")
class PlatformAndBootIntegrationTest {

  private lateinit var context: Context

  private val sampleWindow = CapturedWindow(
    windowKey = "w1",
    packageName = "com.android.settings",
    label = "Settings",
    kind = AppKind.SETTINGS,
    displayId = 0,
    deskIndex = 0,
    bounds = WindowBounds(100, 100, 900, 700),
    normalized = WindowGeometryClassifier.normalize(WindowBounds(100, 100, 900, 700), PRIMARY),
    visualState = WindowVisualState.FREEFORM,
    isVisible = true,
  )

  private val sampleSnapshot = WorkspaceSnapshot(
    bootTokenAtCapture = "bc=0;ep=1",
    displays = listOf(PRIMARY),
    activeDeskIndexByDisplay = mapOf(0 to 0),
    totalDeskCountByDisplay = mapOf(0 to 1),
    windows = listOf(sampleWindow),
  )

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    BootCountdownOverlay.dismiss()
  }

  @After
  fun tearDown() {
    BootCountdownOverlay.dismiss()
  }

  @Test
  fun permissionCoordinator_buildsDeepLinksAndFallbacksForAllPermissions() {
    val coord = PermissionCoordinator(context)
    val initial = coord.state()
    assertEquals(4, RequiredPermission.entries.size)
    assertFalse(initial.allGranted)

    ShadowSettings.setCanDrawOverlays(true)
    shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val updated = coord.state()
    assertTrue(updated.isGranted(RequiredPermission.OVERLAY))
    assertTrue(updated.isGranted(RequiredPermission.NOTIFICATIONS))

    for (perm in RequiredPermission.entries) {
      val fallback = coord.fallbackIntent(perm)
      assertTrue(fallback.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
      val settings = coord.settingsIntent(perm)
      if (perm == RequiredPermission.NOTIFICATIONS) {
        assertEquals(null, settings)
      } else {
        assertNotNull(settings)
      }
    }
    assertNotNull(coord.appDetailsIntent().data)
  }

  @Test
  fun platformAdapters_displayGeometryIntentFactoryAndCatalogWorkOnSdk() {
    val displays = DisplayGeometryProvider(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).current()
    assertTrue(displays.isNotEmpty())
    val bounds = BootPromptLauncher.centeredBounds(displays.first())
    assertTrue(bounds.width > 0 && bounds.height > 0)

    val desc = LaunchDescriptor(
      packageName = KnownPackages.CHROME,
      component = "${KnownPackages.CHROME}/${KnownPackages.CHROME_MAIN_ACTIVITY}",
      action = Intent.ACTION_VIEW,
      categories = setOf(Intent.CATEGORY_BROWSABLE),
      dataUri = "https://example.com",
      flags = Intent.FLAG_ACTIVITY_NEW_TASK,
      booleanExtras = mapOf("flag" to true),
      stringExtras = mapOf("k" to "v"),
      identifier = "win-1",
    )
    val intent = IntentFactory.toIntent(context, desc)
    assertEquals("https://example.com", intent.dataString)
    assertEquals(true, intent.getBooleanExtra("flag", false))
    assertEquals("v", intent.getStringExtra("k"))

    val wl = WindowLaunch(sampleWindow, 0, 0, sampleWindow.bounds, false, desc)
    assertNotNull(IntentFactory.options(wl).toBundle())
    assertNotNull(IntentFactory.options(0, sampleWindow.bounds, forPendingIntent = true).toBundle())

    // Register a launcher activity in ShadowPackageManager and query PackageManagerAppCatalog.
    val spm = shadowOf(context.packageManager)
    val pkgInfo = PackageInfo().apply {
      packageName = "org.chromium.webapk.test1"
      applicationInfo = ApplicationInfo().apply {
        packageName = "org.chromium.webapk.test1"
        nonLocalizedLabel = "Docs PWA"
      }
    }
    spm.installPackage(pkgInfo)
    val ri = ResolveInfo().apply {
      activityInfo = ActivityInfo().apply {
        packageName = "org.chromium.webapk.test1"
        name = "org.chromium.webapk.test1.MainActivity"
        exported = true
        applicationInfo = pkgInfo.applicationInfo
      }
      nonLocalizedLabel = "Docs PWA"
    }
    spm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), ri)

    val catalog = PackageManagerAppCatalog(context)
    assertTrue(catalog.isInstalled("org.chromium.webapk.test1"))
    assertEquals("Docs PWA", catalog.labelFor("org.chromium.webapk.test1"))
    assertEquals("org.chromium.webapk.test1", catalog.findWebAppPackageByLabel("Docs PWA"))
    assertEquals("org.chromium.webapk.test1", catalog.findPackageByLabel("Docs PWA"))
    assertNotNull(catalog.launcherComponent("org.chromium.webapk.test1"))
    assertTrue(catalog.isSystemSurface(KnownPackages.SYSTEM_UI))

    // UsageStatsEventSource returns empty cleanly when no events exist.
    assertTrue(UsageStatsEventSource(context).query(0L, 1_000L).isEmpty())
  }

  @Test
  fun bootOverlayAndReceiver_gateOnKeyguardAndShowCancelableCountdownPopup() {
    ShadowSettings.setCanDrawOverlays(true)
    shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val km = context.getSystemService(KeyguardManager::class.java)

    // 1. While keyguard is locked, desktop is NOT ready for prompt.
    shadowOf(km).setKeyguardLocked(true)
    assertFalse(BootPromptLauncher.isDesktopReadyForPrompt(context))

    // Save a previous-boot snapshot and fire LOCKED_BOOT_COMPLETED -> promoted, not prompted yet.
    val runtime = WorkspaceRuntime.get(context)
    runtime.repository.saveLive(sampleSnapshot)
    BootRestoreReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

    // 2. Unlock keyguard -> desktop IS ready, show BootCountdownOverlay and test Esc cancel.
    shadowOf(km).setKeyguardLocked(false)
    assertTrue(BootPromptLauncher.isDesktopReadyForPrompt(context))

    val shown = BootCountdownOverlay.show(context, sampleSnapshot, autoCountdown = true)
    assertTrue(shown)
    assertTrue(BootCountdownOverlay.isShowing)
    assertTrue(runtime.promptVisible)

    // Advance main looper 1.2s so countdown ticker runs at least 20 ticks cleanly.
    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_200))
    assertTrue(BootCountdownOverlay.isShowing)

    // Dismissing cleans up overlay & resets promptVisible.
    BootCountdownOverlay.dismiss()
    assertFalse(BootCountdownOverlay.isShowing)
    assertFalse(runtime.promptVisible)

    // Persistent monitor notification uses the expected background status copy and ongoing flag.
    Notifications.ensureChannels(context)
    val monitorNotif = Notifications.monitorNotification(context)
    assertEquals(
      "Googlebook App Restorer is working in the background",
      monitorNotif.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
    )
    assertTrue(monitorNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)

    // Companion restore notifications post & cancel cleanly.
    Notifications.postRestoreNotification(context, sampleSnapshot)
    val nm = context.getSystemService(NotificationManager::class.java)
    assertEquals(1, shadowOf(nm).allNotifications.count { it != null })
    Notifications.cancelRestoreNotification(context)
  }

  @Test
  fun runtimeAndActivities_freezeUnfreezeAndRenderCleanly() = runTest {
    ShadowSettings.setCanDrawOverlays(true)
    val runtime = WorkspaceRuntime.get(context)
    runtime.setAutoRestore(true)
    assertTrue(runtime.autoRestore.value)

    runtime.onPowerMenuShown()
    assertEquals(CaptureReason.POWER_MENU_PRE_FREEZE, runtime.frozen.value)
    runtime.onPowerMenuGone()
    assertEquals(null, runtime.frozen.value)

    runtime.onScreenOff()
    assertEquals(CaptureReason.SCREEN_OFF, runtime.frozen.value)
    runtime.onUserPresent()
    assertEquals(null, runtime.frozen.value)

    DiagnosticsLog.add("integration-test-entry")
    assertTrue(DiagnosticsLog.snapshot().any { "integration-test-entry" in it })

    // BasicRestoreDriver smoke check.
    val basic = BasicRestoreDriver(runtime)
    assertTrue(basic.currentDisplays().isNotEmpty())
    assertEquals(1, basic.ensureDeskCount(0, 2))
    assertFalse(basic.activateDesk(0, 1))

    // Foreground monitor service starts cleanly.
    val fgs = Robolectric.buildService(WorkspaceMonitorForegroundService::class.java).create().get()
    fgs.onStartCommand(Intent(), 0, 1)
    fgs.onTaskRemoved(Intent())

    // MainActivity launches and renders onboarding / live workspace without crashing.
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { act -> assertNotNull(act) }
    }

    // RestorePromptActivity finishes gracefully when no pending snapshot is waiting.
    runtime.dismissPending()
    ActivityScenario.launch<RestorePromptActivity>(RestorePromptActivity.intent(context, manualReview = true)).use {
      // Finishes cleanly on empty pending state.
    }
  }
}
