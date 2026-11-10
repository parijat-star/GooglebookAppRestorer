package com.google.android.desktop.apprestorer.data

import com.google.android.desktop.apprestorer.model.WORKSPACE_SCHEMA_VERSION
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import kotlinx.serialization.json.Json

/** JSON (de)serialization with forward/backward schema migration. Pure JVM. */
object WorkspaceSnapshotCodec {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
  }

  fun encode(snapshot: WorkspaceSnapshot): String = json.encodeToString(WorkspaceSnapshot.serializer(), snapshot)

  fun decode(text: String): WorkspaceSnapshot? =
    try {
      migrate(json.decodeFromString(WorkspaceSnapshot.serializer(), text))
    } catch (_: Exception) {
      null
    }

  /** Upgrades pre-v6 snapshots (no per-display desk maps) to the current schema. */
  fun migrate(s: WorkspaceSnapshot): WorkspaceSnapshot {
    if (s.schemaVersion >= WORKSPACE_SCHEMA_VERSION) return s
    val ids = (s.displays.map { it.displayId } + s.windows.map { it.displayId }).toSortedSet()
    val totals = ids.associateWith { id ->
      maxOf(s.totalDeskCountByDisplay[id] ?: 1, (s.windows.filter { it.displayId == id }.maxOfOrNull { it.deskIndex } ?: 0) + 1)
    }
    val active = ids.associateWith { id -> (s.activeDeskIndexByDisplay[id] ?: 0).coerceIn(0, totals.getValue(id) - 1) }
    return s.copy(schemaVersion = WORKSPACE_SCHEMA_VERSION, totalDeskCountByDisplay = totals, activeDeskIndexByDisplay = active)
  }
}

/**
 * Identifies a boot session. Settings.Global.BOOT_COUNT is authoritative when readable; otherwise
 * the wall-clock boot epoch (currentTimeMillis - elapsedRealtime) with a tolerance for NTP drift.
 */
data class BootToken(val bootCount: Int, val bootEpochMs: Long) {
  fun encode(): String = "bc=$bootCount;ep=$bootEpochMs"

  fun isSameBootAs(other: BootToken): Boolean =
    if (bootCount >= 0 && other.bootCount >= 0) bootCount == other.bootCount
    else kotlin.math.abs(bootEpochMs - other.bootEpochMs) <= EPOCH_TOLERANCE_MS

  companion object {
    const val EPOCH_TOLERANCE_MS = 90_000L

    fun parse(s: String?): BootToken? {
      if (s.isNullOrBlank()) return null
      val parts = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
      val bc = parts["bc"]?.toIntOrNull() ?: return null
      val ep = parts["ep"]?.toLongOrNull() ?: return null
      return BootToken(bc, ep)
    }

    /** True when [snapshotToken] was captured during a boot other than [current]. */
    fun isNewBoot(snapshotToken: String?, current: BootToken): Boolean {
      val t = parse(snapshotToken) ?: return true
      return !t.isSameBootAs(current)
    }
  }
}
