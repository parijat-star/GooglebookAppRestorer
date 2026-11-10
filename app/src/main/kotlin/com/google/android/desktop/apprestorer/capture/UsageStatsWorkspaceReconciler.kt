package com.google.android.desktop.apprestorer.capture

/** A UsageEvents.Event reduced to the fields we need. */
data class UsageEventRecord(val packageName: String, val className: String?, val type: Int, val timeMs: Long)

/** Abstracts UsageStatsManager.queryEvents so reconciliation logic is JVM-testable. */
fun interface UsageEventSource {
  fun query(fromMs: Long, toMs: Long): List<UsageEventRecord>
}

/**
 * Uses Usage Access to tell windows that merely disappeared (minimized to the taskbar or alive on
 * an inactive Virtual Desk) from windows the user explicitly closed.
 */
class UsageStatsWorkspaceReconciler(private val source: UsageEventSource?) {

  enum class VanishVerdict { CLOSED, MINIMIZED, UNKNOWN }

  /**
   * Whether this platform reports ACTIVITY_DESTROYED (type 24) to unprivileged usage queries.
   * Only then does "stopped but not destroyed" reliably mean "still alive".
   */
  var destroyedEventsSupported: Boolean = false
    private set

  val isAvailable: Boolean get() = source != null

  fun probeCapabilities(nowMs: Long) {
    val events = safeQuery(nowMs - 6 * HOUR_MS, nowMs)
    if (events.any { it.type == ACTIVITY_DESTROYED }) destroyedEventsSupported = true
  }

  fun classifyVanished(packageName: String, vanishedAtMs: Long, nowMs: Long): VanishVerdict {
    val all = safeQuery(vanishedAtMs - 3_000, nowMs)
    if (all.any { it.type == ACTIVITY_DESTROYED }) destroyedEventsSupported = true
    val events = all.filter { it.packageName == packageName }
    val after = events.filter { it.timeMs >= vanishedAtMs - 1_500 }
    if (after.any { it.type == ACTIVITY_DESTROYED }) return VanishVerdict.CLOSED
    // Re-resumed elsewhere (e.g. moved to another desk / display) — still alive.
    if (after.any { it.type == ACTIVITY_RESUMED }) return VanishVerdict.MINIMIZED
    if (destroyedEventsSupported && after.any { it.type == ACTIVITY_STOPPED }) return VanishVerdict.MINIMIZED
    return VanishVerdict.UNKNOWN
  }

  /** Packages whose last activity event since [sinceMs] is DESTROYED (closed while hidden). */
  fun packagesClosedSince(sinceMs: Long, nowMs: Long): Set<String> {
    val events = safeQuery(sinceMs, nowMs)
    if (events.any { it.type == ACTIVITY_DESTROYED }) destroyedEventsSupported = true
    return events.groupBy { it.packageName }
      .filterValues { evs -> evs.maxByOrNull { it.timeMs }?.type == ACTIVITY_DESTROYED }
      .keys
  }

  private fun safeQuery(from: Long, to: Long): List<UsageEventRecord> =
    try {
      source?.query(from.coerceAtLeast(0), to) ?: emptyList()
    } catch (_: RuntimeException) {
      emptyList()
    }

  companion object {
    // UsageEvents.Event constants (24 is @SystemApi but delivered on many builds).
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val ACTIVITY_STOPPED = 23
    const val ACTIVITY_DESTROYED = 24
    private const val HOUR_MS = 3_600_000L
  }
}
