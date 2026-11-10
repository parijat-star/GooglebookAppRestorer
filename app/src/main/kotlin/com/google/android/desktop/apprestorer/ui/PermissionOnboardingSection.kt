package com.google.android.desktop.apprestorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.desktop.apprestorer.permissions.PermissionState
import com.google.android.desktop.apprestorer.permissions.RequiredPermission

data class PermissionCopy(val title: String, val purpose: String)

fun permissionCopy(p: RequiredPermission): PermissionCopy = when (p) {
  RequiredPermission.ACCESSIBILITY -> PermissionCopy(
    "Window inspection & desk automation",
    "Reads open desktop windows (bounds, Z-order, Virtual Desks, Chrome tabs) and recreates desks in Overview after reboot. Nothing leaves your device.",
  )
  RequiredPermission.OVERLAY -> PermissionCopy(
    "Display over other apps",
    "Opens the Restore prompt automatically after boot and allows background freeform window launches.",
  )
  RequiredPermission.USAGE_ACCESS -> PermissionCopy(
    "Usage access",
    "Tells windows minimized to the taskbar or alive on another desk apart from apps you closed.",
  )
  RequiredPermission.NOTIFICATIONS -> PermissionCopy(
    "Notifications",
    "Posts a restore reminder after boot in case you dismiss or defer the popup.",
  )
}

/** Guided first-run onboarding: one card per permission with a deep-linked Grant action. */
@Composable
fun PermissionOnboardingSection(
  state: PermissionState,
  onGrant: (RequiredPermission) -> Unit,
  onOpenAppInfo: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Setup · ${state.grantedCount} of ${RequiredPermission.entries.size} granted", style = MaterialTheme.typography.titleMedium)
    for (p in RequiredPermission.entries) {
      val granted = state.isGranted(p)
      val copy = permissionCopy(p)
      Card(
        modifier = Modifier.fillMaxWidth().testTag("perm_card_${p.name}"),
        colors = CardDefaults.cardColors(
          containerColor = if (granted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(copy.title, fontWeight = FontWeight.SemiBold)
            Text(copy.purpose, style = MaterialTheme.typography.bodySmall)
            if (p == RequiredPermission.ACCESSIBILITY && !granted) {
              TextButton(onClick = onOpenAppInfo, modifier = Modifier.testTag("restricted_settings_help")) {
                Text("Toggle greyed out? App info → ⋮ → Allow restricted settings")
              }
            }
          }
          Spacer(Modifier.width(12.dp))
          if (granted) {
            AssistChip(onClick = {}, label = { Text("Granted") }, modifier = Modifier.testTag("perm_granted_${p.name}"))
          } else {
            Button(onClick = { onGrant(p) }, modifier = Modifier.testTag("perm_grant_${p.name}")) { Text("Grant") }
          }
        }
      }
    }
  }
}
