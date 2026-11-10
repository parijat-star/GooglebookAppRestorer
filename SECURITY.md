# Security & Privacy Policy

## Security Model

**Googlebook App Restorer** operates strictly on-device with **zero network permissions**:

- **No `INTERNET` permission**: Workspace snapshots, window positions, desk layouts, and open Chrome tab URLs never leave your device.
- **No Root or ADB required**: Uses exclusively standard user-grantable Android APIs (`AccessibilityService`, `SYSTEM_ALERT_WINDOW`, `UsageStatsManager`, `POST_NOTIFICATIONS`).
- **Device-Protected Storage**: Snapshots (`live_workspace.json` and `pending_restore.json`) are stored inside the app's private Device-Protected Storage sandbox using `AtomicFile` + `fsync`.
- **WebView & Password Exclusion**:
  - `TwaAndBrowserInspector` skips `android.webkit.WebView` page DOM subtrees, inspecting only Chrome's top-level browser toolbar and tab strip.
  - `TwaAndBrowserInspector.normalizeUrl` enforces a strict `http://` / `https://` scheme allowlist, blocking `javascript:`, `intent:`, `file:`, `content:`, and `data:` URI injection.
  - `DiagnosticsExporter` explicitly skips web page content and redacts `isPassword` accessibility nodes.

## Reporting a Vulnerability

Please report security issues privately via GitHub Security Advisories ("Report a vulnerability" in the repository's **Security** tab) rather than opening a public issue. Include reproduction steps and affected Android / Desktop OS build details.
