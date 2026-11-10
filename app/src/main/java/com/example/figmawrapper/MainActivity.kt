package com.example.figmawrapper

import android.annotation.SuppressLint
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    private class Tab(val webView: WebView, val isHome: Boolean, val isFigCraft: Boolean = false) {
        val pinned: Boolean get() = isHome || isFigCraft
        var title: String = if (isHome) "Home" else if (isFigCraft) "FigCraft" else "Figma"
        var url: String? = null          // last known URL (used for saving / reopening)
        var pendingUrl: String? = null   // loaded lazily the first time the tab is shown
    }

    private companion object {
        const val HOME_URL = "https://www.figma.com/files"
        const val REPO = "yeftani000/Figma-Android"   // GitHub releases are checked here
        const val UPDATE_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
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
    private var figcraftEnabled = false
    private var figcraftEager = true
    private var figcraftUrl = ""

    // Pen runtime state
    private var lastPenTime = 0L
    private var rejectingGesture = false
    private var spaceHeld = false

    // Updates
    private var downloadId = -1L
    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }
    }

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

        ContextCompat.registerReceiver(
            this, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        autoCheckForUpdates()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current ?: run { finish(); return }
                when {
                    t.webView.canGoBack() -> t.webView.goBack()
                    !t.pinned -> closeTab(t)
                    t.isFigCraft -> switchTo(tabs[0])
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

    override fun onDestroy() {
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- settings

    private fun loadSettings() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        palmRejection = p.getBoolean("palm", true)
        barrelPan = p.getBoolean("barrel", true)
        keepAwake = p.getBoolean("awake", true)
        figcraftEnabled = p.getBoolean("fc_on", false)
        figcraftEager = p.getBoolean("fc_eager", true)
        figcraftUrl = p.getString("fc_url", "") ?: ""
    }

    private fun saveSettings() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("palm", palmRejection)
            .putBoolean("barrel", barrelPan)
            .putBoolean("awake", keepAwake)
            .putBoolean("fc_on", figcraftEnabled)
            .putBoolean("fc_eager", figcraftEager)
            .putString("fc_url", figcraftUrl)
            .apply()
    }

    private fun showSettings() {
        fun cb(text: String, on: Boolean) = CheckBox(this).apply { this.text = text; isChecked = on }
        fun header(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(14), 0, dp(2))
        }
        val palm = cb("Palm rejection (ignore touch while the pen is near)", palmRejection)
        val barrel = cb("Pen button = hold Space (pan)", barrelPan)
        val awake = cb("Keep screen on while editing", keepAwake)
        val fcOn = cb("Show FigCraft tab (Figma alternative)", figcraftEnabled)
        val fcEager = cb("Load it at startup, alongside Figma (uses more memory)", figcraftEager)
        val fcUrl = EditText(this).apply {
            hint = "FigCraft web URL (https://...)"
            setText(figcraftUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(header("PEN"))
            addView(palm)
            addView(barrel)
            addView(header("EDITING"))
            addView(awake)
            addView(header("FIGCRAFT"))
            addView(fcOn)
            addView(fcEager)
            addView(fcUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("Settings  \u00b7  v$versionName")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done") { _, _ ->
                palmRejection = palm.isChecked
                barrelPan = barrel.isChecked
                if (!barrelPan) setSpace(false)
                keepAwake = awake.isChecked
                applyKeepAwake()

                val url = fcUrl.text.toString().trim()
                val valid = url.startsWith("https://") || url.startsWith("http://")
                if (fcOn.isChecked && !valid) toast("Enter a FigCraft URL starting with http:// or https://")
                figcraftUrl = url
                figcraftEager = fcEager.isChecked
                figcraftEnabled = fcOn.isChecked && valid
                saveSettings()
                applyFigCraft()
            }
            .setNeutralButton("Check for updates") { _, _ -> checkForUpdates(true) }
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
        val reload = icon(R.drawable.ic_refresh, TEXT_ON, 7).apply {
            setOnClickListener { reloadCurrent() }
        }
        val plus = icon(R.drawable.ic_add, TEXT_ON, 7).apply {
            setOnClickListener { openInNewTab(HOME_URL) }
        }
        val gear = icon(R.drawable.ic_settings, TEXT_ON, 7).apply {
            setOnClickListener { showSettings() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
            addView(tabScroll, LinearLayout.LayoutParams(0, dp(30), 1f))
            addView(reload, LinearLayout.LayoutParams(dp(32), dp(30)))
            addView(plus, LinearLayout.LayoutParams(dp(32), dp(30)))
            addView(gear, LinearLayout.LayoutParams(dp(32), dp(30)))
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
            } else if (tab.isFigCraft) {
                chip.addView(TextView(this).apply {
                    text = "FigCraft"
                    textSize = 12f
                    maxLines = 1
                    setTextColor(tint)
                    setPadding(dp(10), 0, dp(10), 0)
                })
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
    private fun createTab(isHome: Boolean, isFigCraft: Boolean = false): Tab {
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
        val tab = Tab(wv, isHome, isFigCraft)
        wv.tag = tab
        wv.webViewClient = client
        wv.webChromeClient = chromeClient
        wv.visibility = View.INVISIBLE   // INVISIBLE keeps its size, so canvas apps can load in the background
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
        for (t in tabs) t.webView.visibility = if (t === tab) View.VISIBLE else View.INVISIBLE
        current = tab
        tab.pendingUrl?.let { tab.pendingUrl = null; tab.webView.loadUrl(it) }
        tab.webView.requestFocus()
        refreshTabBar()
        applyKeepAwake()
        saveTabs()
    }

    private fun closeTab(tab: Tab) {
        if (tab.pinned) return
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

    private fun addFigCraftTab() {
        val t = createTab(false, true)
        // keep it right after Home
        tabs.remove(t)
        tabs.add(1.coerceAtMost(tabs.size), t)
        t.url = figcraftUrl
        if (figcraftEager) t.webView.loadUrl(figcraftUrl) else t.pendingUrl = figcraftUrl
    }

    private fun removeFigCraftTab() {
        val t = tabs.firstOrNull { it.isFigCraft } ?: return
        tabs.remove(t)
        content.removeView(t.webView)
        t.webView.stopLoading()
        t.webView.destroy()
        if (current === t) switchTo(tabs[0]) else { refreshTabBar(); saveTabs() }
    }

    /** Called after Settings: add, remove or re-point the FigCraft tab. */
    private fun applyFigCraft() {
        val existing = tabs.firstOrNull { it.isFigCraft }
        if (!figcraftEnabled) { removeFigCraftTab(); return }
        if (existing == null) {
            addFigCraftTab()
            refreshTabBar()
        } else if (existing.url != figcraftUrl) {
            existing.url = figcraftUrl
            existing.pendingUrl = figcraftUrl
            if (current === existing) switchTo(existing)
        }
    }

    private fun reloadCurrent() {
        val t = current ?: return
        if (t.webView.url.isNullOrBlank()) t.webView.loadUrl(t.url ?: HOME_URL) else t.webView.reload()
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
            if (t.pinned) continue
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
        if (figcraftEnabled && figcraftUrl.isNotBlank()) addFigCraftTab()
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

            val allowed = if (tab.isFigCraft) uri.host != null && uri.host == Uri.parse(figcraftUrl).host
                          else isFigmaHost(uri.host)
            if (!allowed) {
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
            if (tab.pinned || !tabs.contains(tab)) return
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

    // ---------------------------------------------------------------- updates (GitHub releases)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun numbers(v: String) = Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun isNewer(latest: String, installed: String): Boolean {
        val a = numbers(latest)
        val b = numbers(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun autoCheckForUpdates() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastUpdateCheck", 0L) < UPDATE_CHECK_INTERVAL_MS) return
        p.edit().putLong("lastUpdateCheck", now).apply()
        checkForUpdates(false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) toast("Checking for updates\u2026")
        Thread {
            try {
                val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                if (conn.responseCode == 404) {
                    if (manual) runOnUiThread { toast("No releases published yet") }
                    return@Thread
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.getString("tag_name")
                val notes = if (json.isNull("body")) "" else json.getString("body")
                var apkUrl: String? = null
                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) { apkUrl = a.getString("browser_download_url"); break }
                }
                runOnUiThread { onUpdateInfo(tag, notes, apkUrl, manual) }
            } catch (e: Exception) {
                if (manual) runOnUiThread { toast("Update check failed: ${e.message}") }
            }
        }.start()
    }

    private fun onUpdateInfo(tag: String, notes: String, apkUrl: String?, manual: Boolean) {
        if (!isNewer(tag, versionName)) {
            if (manual) toast("You're up to date (v$versionName)")
            return
        }
        if (apkUrl == null) {
            if (manual) toast("$tag has no APK attached yet - the build may still be running")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Update available: $tag")
            .setMessage("Installed: v$versionName\n\n" + notes.take(500))
            .setPositiveButton("Download & install") { _, _ -> downloadUpdate(apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadUpdate(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Allow installing from this app, then tap \"Check for updates\" again")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk").delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Figma Wrapper update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "update.apk")
        downloadId = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        toast("Downloading update\u2026")
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L || id != downloadId) return
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst() ||
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL
                ) {
                    toast("Update download failed")
                    return
                }
            }
            installDownloadedApk()
        }
    }

    private fun installDownloadedApk() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (!file.exists()) { toast("Downloaded file not found"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
        if (e.keyCode == KeyEvent.KEYCODE_F5 && !ctrl) return act { reloadCurrent() }
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
