package com.google.android.desktop.apprestorer.inspect

import com.google.android.desktop.apprestorer.capture.AppCatalog
import com.google.android.desktop.apprestorer.capture.BrowserFacts
import com.google.android.desktop.apprestorer.capture.KnownPackages
import com.google.android.desktop.apprestorer.capture.ObservedWindow
import com.google.android.desktop.apprestorer.capture.ResolvedIdentity
import com.google.android.desktop.apprestorer.model.AppKind

/**
 * Classifies desktop windows: standalone TWA / WebAPK vs. Chrome browser, Gemini (Robin /
 * Floaty hosted in the Google app) vs. Search overlays, Files, Settings and regular apps.
 */
class TwaAndBrowserInspector(
  private val catalog: AppCatalog,
  private val ownPackageName: String,
) {

  fun resolve(window: ObservedWindow): ResolvedIdentity? {
    val pkg = window.rootPackage ?: return null
    if (pkg == ownPackageName) {
      return ResolvedIdentity(pkg, catalog.labelFor(pkg), AppKind.STANDARD, isAnchor = true)
    }
    if (pkg == KnownPackages.GOOGLE_QUICK_SEARCH_BOX) return classifyGoogleQuickSearchWindow(window)
    if (pkg == KnownPackages.PLAY_STORE) return null // background Play Store tasks
    if (catalog.isSystemSurface(pkg)) return null
    if (!KnownPackages.isChrome(pkg) &&
      KnownPackages.isExcludedCompanionApp(pkg, catalog.labelFor(pkg), window.captionAppLabel, window.title)
    ) return null
    if (KnownPackages.isChrome(pkg)) return classifyChromeWindow(pkg, window)
    val kind = when (pkg) {
      in KnownPackages.FILES_PACKAGES -> AppKind.FILES
      in KnownPackages.SETTINGS_PACKAGES -> AppKind.SETTINGS
      else -> if (KnownPackages.isWebApkPackage(pkg)) AppKind.TWA else AppKind.STANDARD
    }
    return ResolvedIdentity(pkg, catalog.labelFor(pkg), kind)
  }

  /** Gemini is captured as com.google.android.apps.bard; Search / Opa overlays are rejected. */
  fun classifyGoogleQuickSearchWindow(window: ObservedWindow): ResolvedIdentity? {
    if (window.isSearchOverlay || KnownPackages.isSearchOverlayClassName(window.activityClassName)) return null
    val looksGemini = KnownPackages.isGeminiRobinClassName(window.activityClassName) ||
      listOf(window.title, window.captionAppLabel).any { it?.contains("Gemini", ignoreCase = true) == true }
    if (!looksGemini) return null
    val pkg = if (catalog.isInstalled(KnownPackages.GEMINI)) KnownPackages.GEMINI else KnownPackages.GOOGLE_QUICK_SEARCH_BOX
    return ResolvedIdentity(pkg, "Gemini", AppKind.GEMINI)
  }

  private fun classifyChromeWindow(chromePkg: String, window: ObservedWindow): ResolvedIdentity? {
    val chromeLabel = catalog.labelFor(chromePkg)
    val facts = window.browser
    // A window with a browser omnibox / tab strip and no web-app header is always Chrome itself.
    if (facts != null && facts.hasBrowserChrome && !facts.hasWebAppHeader) {
      return ResolvedIdentity(chromePkg, chromeLabel, AppKind.CHROME_BROWSER)
    }
    // Caption ("Close Calendar" vs "Close Chrome") is the strongest signal, then window title.
    val candidates = listOfNotNull(window.captionAppLabel, facts?.webAppTitle, window.title)
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.equals(chromeLabel, ignoreCase = true) && !it.equals("Chrome", ignoreCase = true) }
    val webAppSignals = (facts?.hasWebAppHeader == true) ||
      KnownPackages.isChromeWebAppClassName(window.activityClassName) ||
      (facts != null && !facts.hasBrowserChrome && candidates.isNotEmpty())
    val captionSaysOtherApp = window.captionAppLabel != null &&
      !window.captionAppLabel.equals(chromeLabel, ignoreCase = true) &&
      !window.captionAppLabel.equals("Chrome", ignoreCase = true)

    if (webAppSignals || captionSaysOtherApp) {
      for (label in candidates) {
        if (KnownPackages.isExcludedCompanionLabel(label)) return null
        val twa = catalog.findWebAppPackageByLabel(label) ?: continue
        if (KnownPackages.isChrome(twa) || twa == ownPackageName) continue
        if (KnownPackages.isExcludedCompanionApp(twa, catalog.labelFor(twa))) return null
        return ResolvedIdentity(twa, catalog.labelFor(twa), AppKind.TWA, hostBrowserPackage = chromePkg)
      }
      // A custom tab of an unknown app: never mis-restore it as a browser window.
      if (facts?.hasBrowserChrome != true) return null
    }
    return ResolvedIdentity(chromePkg, chromeLabel, AppKind.CHROME_BROWSER)
  }

  companion object {
    /** Reads Chrome browser / web-app facts from a window root. */
    fun readBrowserFacts(root: UiNode): BrowserFacts {
      var urlBar: UiNode? = null
      var webAppHeader: UiNode? = null
      var suggestions = false
      var tabCount = 0
      val tabs = mutableListOf<Pair<String, Boolean>>()
      root.walk(maxNodes = 4000) { n, _ ->
        when {
          // Page content can be thousands of nodes; never let it starve the browser UI.
          isWebContent(n) -> return@walk false
          n.hasId("url_bar") -> urlBar = n
          n.hasAnyId("web_app_header_layout", "webapp_header", "web_app_header") -> webAppHeader = n
          n.hasAnyId("omnibox_results_container", "omnibox_suggestions_dropdown") && n.isVisibleToUser -> suggestions = true
          n.hasAnyId("tab_switcher_button", "tab_switcher_mode_tab_switcher_button") -> {
            tabCount = parseTabCount(n.contentDescription) ?: parseTabCount(n.text) ?: tabCount
          }
          isTabStripTab(n) -> n.label?.let { tabs += cleanTabTitle(it) to (n.isSelected || isSelectedLabel(it)) }
        }
        true
      }
      val bar = urlBar
      val titles = tabs.map { it.first }.filter { it.isNotBlank() }
      return BrowserFacts(
        hasWebAppHeader = webAppHeader != null,
        hasBrowserChrome = bar != null || titles.isNotEmpty(),
        urlBarText = bar?.text,
        urlBarFocused = bar?.isFocused == true,
        suggestionsOpen = suggestions,
        tabTitles = titles,
        selectedTabTitle = tabs.firstOrNull { it.second }?.first,
        tabCount = maxOf(tabCount, titles.size),
        webAppTitle = webAppHeader?.let { h -> h.findFirst(60) { it.text?.isNotBlank() == true }?.text },
      )
    }

    /** Chrome exposes page content under a WebView-classed root. */
    fun isWebContent(n: UiNode): Boolean {
      val cls = n.className ?: return false
      return cls == "android.webkit.WebView" || n.hasAnyId("compositor_view_holder_web_contents")
    }

    private val TAB_SUFFIX = Regex(",\\s*(selected\\s+)?tab(,.*)?$", RegexOption.IGNORE_CASE)

    private fun isSelectedLabel(s: String) = Regex(",\\s*selected\\s+tab", RegexOption.IGNORE_CASE).containsMatchIn(s)

    /** Chrome tab-strip tab nodes (title → node), in strip order. */
    fun findTabNodes(root: UiNode): List<Pair<String, UiNode>> {
      val out = mutableListOf<Pair<String, UiNode>>()
      root.walk(4000) { n, _ ->
        when {
          isWebContent(n) -> false
          isTabStripTab(n) -> { n.label?.let { out += cleanTabTitle(it) to n }; false }
          else -> true
        }
      }
      return out
    }

    private fun isTabStripTab(n: UiNode): Boolean {
      if (n.hasAnyId("tab_strip_item", "tab_view")) return true
      val cls = n.className ?: ""
      if (cls.endsWith(".Tab") || cls == "android.widget.Tab") return true
      val d = n.contentDescription ?: return false
      return TAB_SUFFIX.containsMatchIn(d)
    }

    fun cleanTabTitle(s: String): String = s.replace(TAB_SUFFIX, "").trim()

    internal fun parseTabCount(s: CharSequence?): Int? =
      s?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private val HOST_CHARS = Regex("^[a-z0-9.-]+$")

    /**
     * Turns omnibox display text into a safe openable web URL (strictly http/https), or null.
     * Rejects non-web schemes (javascript:, intent:, file:, content:, data:, chrome:, etc.) while
     * supporting bare hosts and host:port (e.g. "localhost:8080/app").
     */
    fun normalizeUrl(raw: String?): String? {
      val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 } ?: return null
      if (s.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
      val lower = s.lowercase()
      if (lower.startsWith("http://") || lower.startsWith("https://")) {
        val rest = lower.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        return s
      }
      if ("://" in lower) return null
      val authority = lower.substringBefore('/').substringBefore('?').substringBefore('#')
      val host = authority.substringBefore(':')
      val portPart = if (':' in authority) authority.substringAfter(':', "") else null
      if (portPart != null) {
        val port = portPart.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
      }
      val looksLikeHost = host == "localhost" ||
        (host.contains('.') && !host.startsWith('.') && !host.endsWith('.') && !host.contains("..") && HOST_CHARS.matches(host))
      return if (looksLikeHost) "https://$s" else null
    }
  }
}

/**
 * Tracks steady-state omnibox URLs and tab-strip titles per Chrome window, so each window's open
 * tabs are known at shutdown even though only the selected tab's URL is ever visible.
 */
class ChromeWindowTabTracker {

  data class TabState(val urls: List<String>, val titles: List<String>, val activeIndex: Int, val tabCount: Int)

  private class State {
    val urlByTitle = LinkedHashMap<String, String>()
    val looseUrls = LinkedHashSet<String>()
    var orderedTitles: List<String> = emptyList()
    var selectedTitle: String? = null
    var lastUrl: String? = null
    var tabCount = 0
  }

  private val states = HashMap<Int, State>()

  fun update(windowId: Int, facts: BrowserFacts) {
    val st = states.getOrPut(windowId) { State() }
    if (facts.tabTitles.isNotEmpty()) {
      st.orderedTitles = facts.tabTitles
      st.urlByTitle.keys.retainAll(facts.tabTitles.toSet()) // closed tabs drop out
    }
    if (facts.tabCount > 0) st.tabCount = facts.tabCount
    st.selectedTitle = facts.selectedTabTitle ?: st.selectedTitle
    // Only trust the omnibox when the user is not typing (unfocused, no dropdown).
    if (facts.urlBarFocused || facts.suggestionsOpen) return
    val url = TwaAndBrowserInspector.normalizeUrl(facts.urlBarText) ?: return
    st.lastUrl = url
    val title = facts.selectedTabTitle
    if (title != null) {
      st.urlByTitle[title] = url
    } else {
      st.looseUrls.remove(url)
      st.looseUrls.add(url)
      if (st.tabCount == 1) st.looseUrls.retainAll(setOf(url))
    }
  }

  /** Seeds a window from a previously captured state (e.g. after a desk switch re-shows it). */
  fun seed(windowId: Int, urls: List<String>, titles: List<String>) {
    if (states.containsKey(windowId) || urls.isEmpty()) return
    val st = State()
    urls.forEachIndexed { i, u ->
      val t = titles.getOrNull(i)?.takeIf { it.isNotBlank() }
      if (t != null) st.urlByTitle[t] = u else st.looseUrls.add(u)
    }
    st.orderedTitles = titles.filter { it.isNotBlank() }
    st.tabCount = urls.size
    st.lastUrl = urls.lastOrNull()
    states[windowId] = st
  }

  fun tabsFor(windowId: Int): TabState? {
    val st = states[windowId] ?: return null
    val urls = mutableListOf<String>()
    val titles = mutableListOf<String>()
    if (st.orderedTitles.isNotEmpty()) {
      for (t in st.orderedTitles) {
        val u = st.urlByTitle[t] ?: continue
        if (u !in urls) { urls += u; titles += t }
      }
    }
    var loose = st.looseUrls.filter { it !in urls }
    val room = if (st.tabCount > 0) (st.tabCount - urls.size).coerceAtLeast(0) else loose.size
    loose = loose.takeLast(room)
    for (u in loose) { urls += u; titles += "" }
    if (urls.isEmpty()) return null
    val active = st.lastUrl?.let { urls.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    return TabState(urls, titles, active, maxOf(st.tabCount, urls.size))
  }

  fun unknownTitles(windowId: Int, titles: List<String>): List<String> {
    val st = states[windowId] ?: return titles
    return titles.filter { it !in st.urlByTitle }
  }

  fun forget(windowId: Int) {
    states.remove(windowId)
  }
}
