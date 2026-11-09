package com.example.figmawrapper

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
        val FILE_SEGMENTS = setOf("file", "design", "board", "slides", "deck", "proto", "make", "site", "buzz")
        const val PEN_GRACE_MS = 500L    // finger touches this soon after the pen was near are ignored
        val BAR: Int = Color.parseColor("#2C2C2C")
        val ACTIVE: Int = Color.parseColor("#1E1E1E")
        val TEXT_ON: Int = Color.WHITE
        val TEXT_OFF: Int = Color.parseColor("#B3B3B3")
    }

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private val closedUrls = ArrayDeque<String>()
    private val swallowedKeys = mutableSetOf<Int>()

    // Pen settings (persisted)
    private var palmRejection = true
    private var barrelPan = true
    private var keepAwake = true

    // Pen runtime state
    private var lastPenTime = 0L
    private var rejectingGesture = false
    private var spaceHeld = false

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
        loadSettings()
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
        setSpace(false)
        saveTabs()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    // ---------------------------------------------------------------- settings

    private fun loadSettings() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        palmRejection = p.getBoolean("palm", true)
        barrelPan = p.getBoolean("barrel", true)
        keepAwake = p.getBoolean("awake", true)
    }

    private fun saveSettings() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("palm", palmRejection)
            .putBoolean("barrel", barrelPan)
            .putBoolean("awake", keepAwake)
            .apply()
    }

    private fun showPenSettings() {
        val labels = arrayOf(
            "Palm rejection (ignore touch while the pen is near)",
            "Pen button = hold Space (pan)",
            "Keep screen on while editing"
        )
        val checked = booleanArrayOf(palmRejection, barrelPan, keepAwake)
        AlertDialog.Builder(this)
            .setTitle("Pen settings")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                when (which) {
                    0 -> palmRejection = isChecked
                    1 -> { barrelPan = isChecked; if (!isChecked) setSpace(false) }
                    2 -> { keepAwake = isChecked; applyKeepAwake() }
                }
                saveSettings()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun applyKeepAwake() {
        val on = keepAwake && current?.isHome == false
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun icon(res: Int, tint: Int, pad: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun buildUi() {
        tabStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(4), 0, dp(4), 0)
        }
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        val plus = icon(R.drawable.ic_add, TEXT_ON, 7).apply {
            setOnClickListener { openInNewTab(HOME_URL) }
        }
        val pen = icon(R.drawable.ic_pen, TEXT_ON, 7).apply {
            setOnClickListener { showPenSettings() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
            addView(tabScroll, LinearLayout.LayoutParams(0, dp(30), 1f))
            addView(plus, LinearLayout.LayoutParams(dp(32), dp(30)))
            addView(pen, LinearLayout.LayoutParams(dp(32), dp(30)))
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
        val r = dp(6).toFloat()
        for (tab in tabs) {
            val active = tab === current
            val tint = if (active) TEXT_ON else TEXT_OFF
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (active) ACTIVE else BAR)
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                setOnClickListener { switchTo(tab) }
            }
            if (tab.isHome) {
                // Icon-only Home tab to save width
                chip.addView(icon(R.drawable.ic_home, tint, 5), LinearLayout.LayoutParams(dp(32), dp(26)))
            } else {
                val label = TextView(this).apply {
                    text = tab.title
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(110)
                    setTextColor(tint)
                    setPadding(dp(8), 0, 0, 0)
                }
                val close = icon(R.drawable.ic_close, tint, 7).apply {
                    setOnClickListener { closeTab(tab) }
                }
                chip.addView(label)
                chip.addView(close, LinearLayout.LayoutParams(dp(24), dp(26)))
            }
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, dp(26)).apply { marginEnd = dp(2) }
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
        // Pen friendly: no long-press menus / haptics while holding the pen still, no overscroll glow
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.isHapticFeedbackEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER

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
        setSpace(false)
        for (t in tabs) t.webView.visibility = if (t === tab) View.VISIBLE else View.GONE
        current = tab
        tab.pendingUrl?.let { tab.pendingUrl = null; tab.webView.loadUrl(it) }
        tab.webView.requestFocus()
        refreshTabBar()
        applyKeepAwake()
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
                t.pendingUrl = o.getString("url")
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
                if (!tab.isHome && (view.url.isNullOrBlank() || view.url == "about:blank")) {
                    view.post { closeTab(tab) }
                }
                return true
            }
            if (tab.isHome && isFileUrl(uri)) {
                openInNewTab(uri.toString())
                return true
            }
            return false
        }

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

    // ---------------------------------------------------------------- stylus handling

    private fun hasPen(ev: MotionEvent): Boolean {
        for (i in 0 until ev.pointerCount) {
            val t = ev.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return true
        }
        return false
    }

    /** Copy of the event with the stylus button bits cleared, so the page doesn't see a right-click. */
    private fun withoutButtons(ev: MotionEvent): MotionEvent {
        val n = ev.pointerCount
        val props = Array(n) { i -> MotionEvent.PointerProperties().also { ev.getPointerProperties(i, it) } }
        val coords = Array(n) { i -> MotionEvent.PointerCoords().also { ev.getPointerCoords(i, it) } }
        return MotionEvent.obtain(
            ev.downTime, ev.eventTime, ev.action, n, props, coords, ev.metaState, 0,
            ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags, ev.source, ev.flags
        )
    }

    /** Hold/release the Space key in the current tab (Figma: hold Space + drag = pan). */
    private fun setSpace(down: Boolean) {
        if (spaceHeld == down) return
        spaceHeld = down
        val wv = current?.webView ?: return
        val t = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(
            KeyEvent(t, t, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0)
        )
    }

    private fun updateSpaceFromPen(ev: MotionEvent) {
        val pressed = (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT -> setSpace(false)
            else -> setSpace(pressed)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val pen = hasPen(ev)
        if (pen) lastPenTime = ev.eventTime

        // Palm rejection: ignore finger gestures that begin while the pen is touching/hovering
        if (palmRejection) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                rejectingGesture = !pen && (ev.eventTime - lastPenTime) < PEN_GRACE_MS
            }
            if (rejectingGesture) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    rejectingGesture = false
                }
                return true
            }
        }

        if (pen && barrelPan) {
            updateSpaceFromPen(ev)
            if (ev.buttonState != 0) {
                val out = withoutButtons(ev)
                try { return super.dispatchTouchEvent(out) } finally { out.recycle() }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // Pen hovering above the screen arrives as generic motion events
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (hasPen(ev)) {
            lastPenTime = ev.eventTime
            if (barrelPan) {
                updateSpaceFromPen(ev)
                val a = ev.actionMasked
                if (a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) return true
                if (ev.buttonState != 0) {
                    val out = withoutButtons(ev)
                    try { return super.dispatchGenericMotionEvent(out) } finally { out.recycle() }
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---------------------------------------------------------------- keyboard shortcuts

    private fun act(block: () -> Unit): () -> Unit = block

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
                val n = e.keyCode - KeyEvent.KEYCODE_1
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
        return super.dispatchKeyEvent(event)
    }
}
