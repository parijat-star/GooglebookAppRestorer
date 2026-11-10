package com.google.android.desktop.apprestorer.boot

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import com.google.android.desktop.apprestorer.R
import com.google.android.desktop.apprestorer.WorkspaceRuntime
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import com.google.android.desktop.apprestorer.service.DiagnosticsLog
import com.google.android.desktop.apprestorer.service.WorkspaceCaptureAccessibilityService
import java.lang.ref.WeakReference
import kotlin.math.ceil

/**
 * Compact centered floating pop-up shown on fresh boot unlock:
 * "Workspace restoring automatically in Xs… — Press Cancel to skip" with a smooth countdown bar,
 * prominent Cancel button (and Esc shortcut), Restore now, and Choose… (full window selector).
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY (when the accessibility service is bound) or
 * TYPE_APPLICATION_OVERLAY so it is never blocked by Background Activity Launch rules, never gets
 * wrapped in a desktop window frame, and disappears in 0ms before restore automation begins.
 */
object BootCountdownOverlay {
  private const val TAG = "BootCountdownOverlay"
  const val AUTO_COUNTDOWN_SECONDS = 6
  private const val TICK_MS = 50L

  private val main = Handler(Looper.getMainLooper())
  private var activeViewRef: WeakReference<View>? = null
  private var activeWmRef: WeakReference<WindowManager>? = null
  private var tickRunnable: Runnable? = null

  val isShowing: Boolean get() = activeViewRef?.get() != null

  fun formatCountdownHeadline(seconds: Int): String =
    "Workspace restoring automatically in ${seconds.coerceAtLeast(1)}s…"

  fun formatCompactSummary(snapshot: WorkspaceSnapshot): String {
    val windows = snapshot.windows.filterNot { KnownPackages.isExcludedCompanionApp(it.packageName, it.label) }
    val winCount = windows.size
    val deskCount = ((windows.maxOfOrNull { it.deskIndex } ?: 0) + 1).coerceAtLeast(1)
    val tabs = windows.sumOf { maxOf(it.tabCount, it.tabUrls.size) }
    val winWord = if (winCount == 1) "1 window" else "$winCount windows"
    val deskWord = if (deskCount == 1) "1 desk" else "$deskCount desks"
    val tabPart = if (tabs > 0) " · $tabs Chrome ${if (tabs == 1) "tab" else "tabs"}" else ""
    return "$winWord across $deskWord$tabPart"
  }

  /** Shows the floating reboot popup on the primary display. Must run on the main thread. */
  fun show(context: Context, snapshot: WorkspaceSnapshot, autoCountdown: Boolean): Boolean {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post { show(context, snapshot, autoCountdown) }
      return true
    }
    dismiss()

    val (windowCtx, windowType) = resolveOverlayContext(context) ?: return false
    val wm = windowCtx.getSystemService(WindowManager::class.java) ?: return false
    val runtime = WorkspaceRuntime.get(context)
    val density = windowCtx.resources.displayMetrics.density
    val screenWidth = windowCtx.resources.displayMetrics.widthPixels
    val dark = (windowCtx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    val bgColor = if (dark) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()
    val borderColor = if (dark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
    val titleColor = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    val bodyColor = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    val accentColor = if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
    val trackColor = if (dark) 0xFF3C4043.toInt() else 0xFFE8F0FE.toInt()
    val cancelBg = if (dark) 0xFFF28B82.toInt() else 0xFFD93025.toInt()
    val cancelFg = if (dark) 0xFF202124.toInt() else Color.WHITE
    val tonalBg = if (dark) 0xFF303134.toInt() else 0xFFF1F3F4.toInt()

    var finished = false
    fun onCancel() {
      if (finished) return
      finished = true
      DiagnosticsLog.add("boot-prompt: auto-restore cancelled by user")
      Notifications.cancelRestoreNotification(context.applicationContext)
      dismiss()
    }

    fun onRestoreNow() {
      if (finished) return
      finished = true
      DiagnosticsLog.add("boot-prompt: starting workspace restore")
      Notifications.cancelRestoreNotification(context.applicationContext)
      dismiss()
      runtime.restore(snapshot, selectedKeys = null, clearPendingAfter = true)
    }

    fun onChooseWindows() {
      if (finished) return
      finished = true
      DiagnosticsLog.add("boot-prompt: switched to manual window selection")
      dismiss()
      BootPromptLauncher.launchPrompt(context.applicationContext, manualReview = true)
    }

    val root = object : FrameLayout(windowCtx) {
      override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_UP) {
          onCancel()
          return true
        }
        return super.dispatchKeyEvent(event)
      }
    }.apply {
      isFocusable = true
      isFocusableInTouchMode = true
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    val card = LinearLayout(windowCtx).apply {
      orientation = LinearLayout.VERTICAL
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(bgColor)
        setStroke(dp(1).coerceAtLeast(1), borderColor)
      }
      elevation = dp(14).toFloat()
      setPadding(dp(24), dp(20), dp(24), dp(20))
    }

    val badge = TextView(windowCtx).apply {
      text = windowCtx.getString(R.string.app_name).uppercase()
      setTextColor(accentColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
      letterSpacing = 0.06f
    }
    card.addView(badge)

    val headlineView = TextView(windowCtx).apply {
      text = if (autoCountdown) formatCountdownHeadline(AUTO_COUNTDOWN_SECONDS) else "Restore previous workspace?"
      setTextColor(titleColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
      typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
      setPadding(0, dp(6), 0, dp(4))
    }
    card.addView(headlineView)

    val summaryView = TextView(windowCtx).apply {
      val detail = formatCompactSummary(snapshot)
      text = if (autoCountdown) "$detail · Press Cancel (or Esc) to skip" else detail
      setTextColor(bodyColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
    }
    card.addView(summaryView)

    var progressBar: ProgressBar? = null
    if (autoCountdown) {
      val totalMs = AUTO_COUNTDOWN_SECONDS * 1_000
      val bar = ProgressBar(windowCtx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = totalMs
        progress = totalMs
        val track = GradientDrawable().apply {
          cornerRadius = dp(4).toFloat()
          setColor(trackColor)
        }
        val fill = ClipDrawable(
          GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(accentColor)
          },
          Gravity.START,
          ClipDrawable.HORIZONTAL,
        )
        progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
          setId(0, android.R.id.background)
          setId(1, android.R.id.progress)
        }
      }
      card.addView(
        bar,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
          topMargin = dp(14)
        },
      )
      progressBar = bar
    }

    val actions = LinearLayout(windowCtx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }

    fun pillButton(label: String, bg: Int, fg: Int, strokeColor: Int? = null, bold: Boolean = false, onClick: () -> Unit): TextView =
      TextView(windowCtx).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = dp(18).toFloat()
          setColor(bg)
          if (strokeColor != null) setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }
        setOnClickListener { onClick() }
      }

    val chooseBtn = pillButton("Choose…", Color.TRANSPARENT, accentColor, onClick = ::onChooseWindows)
    actions.addView(chooseBtn)
    actions.addView(Space(windowCtx), LinearLayout.LayoutParams(0, 1, 1f))

    val restoreBtn = pillButton(
      label = if (autoCountdown) "Restore now" else "Restore",
      bg = tonalBg,
      fg = titleColor,
      strokeColor = borderColor,
      bold = true,
      onClick = ::onRestoreNow,
    )
    actions.addView(restoreBtn)

    val cancelBtn = pillButton(
      label = if (autoCountdown) "Cancel" else "Not now",
      bg = cancelBg,
      fg = cancelFg,
      bold = true,
      onClick = ::onCancel,
    )
    actions.addView(
      cancelBtn,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(10)
      },
    )

    card.addView(
      actions,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(18)
      },
    )

    val cardWidth = minOf(dp(480), (screenWidth * 0.88f).toInt().coerceAtLeast(dp(320)))
    root.addView(card, FrameLayout.LayoutParams(cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      windowType,
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.CENTER
      y = -dp(32)
      title = "GooglebookRestoreCountdown"
    }

    return try {
      wm.addView(root, lp)
      activeViewRef = WeakReference(root)
      activeWmRef = WeakReference(wm)
      runtime.promptVisible = true
      root.requestFocus()
      DiagnosticsLog.add("boot-prompt overlay shown (auto=$autoCountdown, type=$windowType, windows=${snapshot.windows.size})")

      if (autoCountdown) {
        val totalMs = AUTO_COUNTDOWN_SECONDS * 1_000L
        var remainingMs = totalMs
        var lastTickUptime = SystemClock.uptimeMillis()
        val km = context.getSystemService(KeyguardManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val ticker = object : Runnable {
          override fun run() {
            if (finished || activeViewRef?.get() !== root) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastTickUptime).coerceIn(0L, 250L)
            lastTickUptime = now
            // Only count down while the screen is interactive and unlocked.
            val screenReady = (pm?.isInteractive != false) && (km?.isKeyguardLocked != true)
            if (screenReady) {
              remainingMs = (remainingMs - dt).coerceAtLeast(0L)
              val secsLeft = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)
              headlineView.text = formatCountdownHeadline(secsLeft)
              progressBar?.progress = remainingMs.toInt()
              if (remainingMs <= 0L) {
                onRestoreNow()
                return
              }
            }
            main.postDelayed(this, TICK_MS)
          }
        }
        tickRunnable = ticker
        main.postDelayed(ticker, TICK_MS)
      }
      true
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unable to attach boot countdown overlay", e)
      false
    }
  }

  fun dismiss() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post { dismiss() }
      return
    }
    tickRunnable?.let { main.removeCallbacks(it) }
    tickRunnable = null
    val v = activeViewRef?.get()
    val wm = activeWmRef?.get()
    activeViewRef = null
    activeWmRef = null
    if (v != null && wm != null) {
      val appCtx = v.context.applicationContext
      runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
      runCatching { WorkspaceRuntime.get(appCtx).promptVisible = false }
    }
  }

  private fun resolveOverlayContext(context: Context): Pair<Context, Int>? {
    val dm = context.getSystemService(DisplayManager::class.java)
    val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
    val a11y = WorkspaceCaptureAccessibilityService.instance
    if (a11y != null) {
      val ctx = if (defaultDisplay != null) {
        runCatching { a11y.createWindowContext(defaultDisplay, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null) }.getOrDefault(a11y)
      } else a11y
      return ctx to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }
    if (Settings.canDrawOverlays(context)) {
      val app = context.applicationContext
      val ctx = if (defaultDisplay != null) {
        runCatching { app.createWindowContext(defaultDisplay, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) }.getOrDefault(app)
      } else app
      return ctx to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }
    return null
  }
}
