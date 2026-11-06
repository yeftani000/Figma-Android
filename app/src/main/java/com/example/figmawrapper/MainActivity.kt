package com.example.figmawrapper

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private class Tab(val webView: WebView, val isHome: Boolean) {
        var title: String = if (isHome) "Home" else "Figma"
        var url: String? = null          // last known URL (used for saving / reopening)
        var pendingUrl: String? = null   // loaded lazily the first time the tab is shown
    }

    private companion object {
        const val HOME_URL = "https://www.figma.com/files"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        // First path segment of URLs that are actual documents (open in their own tab)
        val FILE_SEGMENTS = setOf("file", "design", "board", "slides", "deck", "proto", "make", "site", "buzz")
        val BAR: Int = Color.parseColor("#2C2C2C")
        val ACTIVE: Int = Color.parseColor("#1E1E1E")
        val TEXT_ON: Int = Color.WHITE
        val TEXT_OFF: Int = Color.parseColor("#B3B3B3")
    }

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private val closedUrls = ArrayDeque<String>()
    private val swallowedKeys = mutableSetOf<Int>()

    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var content: FrameLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = if (result.resultCode == RESULT_OK) {
                result.data?.let { d ->
                    d.clipData?.let { c -> Array(c.itemCount) { i -> c.getItemAt(i).uri } }
                        ?: d.data?.let { arrayOf(it) }
                }
            } else null
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BAR
        buildUi()
        CookieManager.getInstance().setAcceptCookie(true)
        restoreTabs()
        intent?.data?.let { openInNewTab(it.toString()) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current ?: run { finish(); return }
                when {
                    t.webView.canGoBack() -> t.webView.goBack()
                    !t.isHome -> closeTab(t)
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { openInNewTab(it.toString()) }
    }

    override fun onPause() {
        saveTabs()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), 0, dp(6), 0)
        }
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        val plus = TextView(this).apply {
            text = "+"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(TEXT_ON)
            setOnClickListener { openInNewTab(HOME_URL) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
            addView(tabScroll, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(plus, LinearLayout.LayoutParams(dp(46), dp(42)))
        }
        content = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ACTIVE)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun refreshTabBar() {
        tabStrip.removeAllViews()
        var activeChip: View? = null
        val r = dp(8).toFloat()
        for (tab in tabs) {
            val active = tab === current
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
                background = GradientDrawable().apply {
                    setColor(if (active) ACTIVE else BAR)
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                setOnClickListener { switchTo(tab) }
            }
            val label = TextView(this).apply {
                text = if (tab.isHome) "\u2302  Home" else tab.title
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(170)
                setTextColor(if (active) TEXT_ON else TEXT_OFF)
                if (active) setTypeface(typeface, Typeface.BOLD)
            }
            chip.addView(label)
            if (tab.isHome) {
                chip.setPadding(dp(12), 0, dp(12), 0)
            } else {
                val close = TextView(this).apply {
                    text = "\u2715"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(TEXT_OFF)
                    setOnClickListener { closeTab(tab) }
                }
                chip.addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))
            }
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, dp(34)).apply { marginEnd = dp(2) }
            tabStrip.addView(chip, lp)
            if (active) activeChip = chip
        }
        activeChip?.let { c -> tabScroll.post { tabScroll.smoothScrollTo(c.left - dp(24), 0) } }
    }

    // ---------------------------------------------------------------- tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(isHome: Boolean): Tab {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        val tab = Tab(wv, isHome)
        wv.tag = tab
        wv.webViewClient = client
        wv.webChromeClient = chromeClient
        wv.visibility = View.GONE
        content.addView(wv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tabs.add(tab)
        return tab
    }

    private fun openInNewTab(url: String) {
        val t = createTab(false)
        t.pendingUrl = url
        t.url = url
        switchTo(t)
    }

    private fun switchTo(tab: Tab) {
        for (t in tabs) t.webView.visibility = if (t === tab) View.VISIBLE else View.GONE
        current = tab
        tab.pendingUrl?.let { tab.pendingUrl = null; tab.webView.loadUrl(it) }
        tab.webView.requestFocus()
        refreshTabBar()
        saveTabs()
    }

    private fun closeTab(tab: Tab) {
        if (tab.isHome) return
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tab.url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let {
            closedUrls.addLast(it)
            if (closedUrls.size > 20) closedUrls.removeFirst()
        }
        tabs.removeAt(idx)
        content.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (current === tab) {
            switchTo(tabs[(idx - 1).coerceIn(0, tabs.lastIndex)])
        } else {
            refreshTabBar()
            saveTabs()
        }
    }

    private fun reopenClosedTab() {
        closedUrls.removeLastOrNull()?.let { openInNewTab(it) }
    }

    private fun cycle(delta: Int) {
        val i = tabs.indexOf(current)
        if (i < 0) return
        switchTo(tabs[(i + delta + tabs.size) % tabs.size])
    }

    private fun saveTabs() {
        val arr = JSONArray()
        for (t in tabs) {
            if (t.isHome) continue
            val u = t.url
            if (u.isNullOrBlank() || u == "about:blank") continue
            arr.put(JSONObject().put("url", u).put("title", t.title))
        }
        getSharedPreferences("tabs", MODE_PRIVATE).edit()
            .putString("tabs", arr.toString())
            .putInt("active", tabs.indexOf(current).coerceAtLeast(0))
            .apply()
    }

    private fun restoreTabs() {
        val home = createTab(true)
        home.pendingUrl = HOME_URL
        home.url = HOME_URL
        val prefs = getSharedPreferences("tabs", MODE_PRIVATE)
        try {
            val arr = JSONArray(prefs.getString("tabs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = createTab(false)
                t.pendingUrl = o.getString("url")   // loaded lazily when selected
                t.url = t.pendingUrl
                t.title = o.optString("title", "Figma")
            }
        } catch (_: Exception) { /* ignore corrupt state */ }
        switchTo(tabs.getOrNull(prefs.getInt("active", 0)) ?: home)
    }

    // ---------------------------------------------------------------- web clients

    private fun isFigmaHost(host: String?) =
        host != null && (host == "figma.com" || host.endsWith(".figma.com"))

    private fun isFileUrl(uri: Uri) =
        isFigmaHost(uri.host) && uri.pathSegments.firstOrNull() in FILE_SEGMENTS

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val tab = view.tag as? Tab ?: return false
            val uri = request.url

            if (!isFigmaHost(uri.host)) {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {}
                // A popup tab that only existed to redirect elsewhere: close it
                if (!tab.isHome && (view.url.isNullOrBlank() || view.url == "about:blank")) {
                    view.post { closeTab(tab) }
                }
                return true
            }
            // Clicking a file on the Home tab opens it in its own tab (like the desktop app)
            if (tab.isHome && isFileUrl(uri)) {
                openInNewTab(uri.toString())
                return true
            }
            return false
        }

        // Catches single-page-app navigations that never hit shouldOverrideUrlLoading
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            val tab = view.tag as? Tab ?: return
            if (url.isNullOrBlank() || url == "about:blank") return
            if (tab.isHome && isFileUrl(Uri.parse(url))) {
                view.post {
                    if (view.canGoBack()) view.goBack()
                    openInNewTab(url)
                }
                return
            }
            tab.url = url
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
            saveTabs()
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            val tab = view.tag as? Tab ?: return
            if (tab.isHome || !tabs.contains(tab)) return
            val clean = (title ?: "").replace(Regex("\\s*[\u2013\u2014-]\\s*Figma$"), "").trim()
            tab.title = clean.ifEmpty { "Figma" }
            refreshTabBar()
        }

        // window.open() / target=_blank -> new tab
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            val tab = createTab(false)
            (resultMsg.obj as WebView.WebViewTransport).webView = tab.webView
            resultMsg.sendToTarget()
            switchTo(tab)
            return true
        }

        override fun onCloseWindow(window: WebView) {
            (window.tag as? Tab)?.let { t -> window.post { closeTab(t) } }
        }

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            filePicker.launch(params.createIntent())
            return true
        }
    }

    // ---------------------------------------------------------------- keyboard shortcuts

    private fun act(block: () -> Unit): () -> Unit = block

    /** Returns the action for a Ctrl/Cmd shortcut, or null to let the key reach the Figma editor. */
    private fun shortcutFor(e: KeyEvent): (() -> Unit)? {
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        if (e.keyCode == KeyEvent.KEYCODE_F5 && !ctrl) return act { current?.webView?.reload() }
        if (!ctrl || e.isAltPressed) return null

        return when (e.keyCode) {
            KeyEvent.KEYCODE_T ->
                act { if (shift) reopenClosedTab() else openInNewTab(HOME_URL) }
            KeyEvent.KEYCODE_N ->
                if (!shift) act { openInNewTab(HOME_URL) } else null
            KeyEvent.KEYCODE_W ->
                if (!shift) act { current?.let { closeTab(it) } } else null
            KeyEvent.KEYCODE_TAB ->
                act { cycle(if (shift) -1 else 1) }
            KeyEvent.KEYCODE_PAGE_DOWN -> act { cycle(1) }
            KeyEvent.KEYCODE_PAGE_UP -> act { cycle(-1) }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val n = e.keyCode - KeyEvent.KEYCODE_1          // 0..8
                act { tabs.getOrNull(if (n == 8) tabs.lastIndex else n)?.let { switchTo(it) } }
            }
            else -> null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val action = shortcutFor(event)
            if (action != null) {
                if (event.repeatCount == 0) action()
                swallowedKeys.add(event.keyCode)
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP && swallowedKeys.remove(event.keyCode)) {
            return true
        }
        // Everything else (V, R, F, Ctrl+C, Ctrl+Z, ...) goes straight to the Figma editor
        return super.dispatchKeyEvent(event)
    }
}
