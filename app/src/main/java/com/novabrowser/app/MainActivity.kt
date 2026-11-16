package com.novabrowser.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale

open class BrowserActivity : Activity() {
    private data class BrowserTab(val webView: WebView, val privateTab: Boolean)
    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private lateinit var pageHost: FrameLayout
    private lateinit var addressBar: EditText
    private lateinit var tabCounter: TextView
    private lateinit var statusText: TextView
    private var darkMode = false
    private val isPrivateWindow: Boolean get() = this is IncognitoActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        addTab("https://www.google.com")
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 252))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 6)
        }

        addressBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            hint = "ابحث أو اكتب عنوان موقع"
            setPadding(14, 4, 14, 4)
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    navigateFromBar()
                    true
                } else false
            }
        }
        top.addView(addressBar, LinearLayout.LayoutParams(0, 48, 1f))
        top.addView(makeButton("اذهب") { navigateFromBar() })
        outer.addView(top)

        statusText = TextView(this).apply {
            text = if (isPrivateWindow) "التصفح الخفي — لا يُحفظ سجل التصفح" else "Nova Browser"
            textSize = 11f
            setPadding(12, 2, 12, 4)
            setTextColor(Color.DKGRAY)
        }
        outer.addView(statusText)

        pageHost = FrameLayout(this)
        outer.addView(pageHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(2, 5, 2, 5)
            setBackgroundColor(Color.rgb(238, 240, 245))
        }
        controls.addView(makeButton("رجوع") { currentWebView()?.let { if (it.canGoBack()) it.goBack() } })
        controls.addView(makeButton("التالي") { currentWebView()?.let { if (it.canGoForward()) it.goForward() } })
        controls.addView(makeButton("الرئيسية") { loadHome() })
        controls.addView(makeButton("＋") { addTab("https://www.google.com") })
        tabCounter = TextView(this).apply {
            text = "1"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
            setOnClickListener { showTabs() }
        }
        controls.addView(tabCounter)
        controls.addView(makeButton("خاص") { openPrivateWindow() })
        controls.addView(makeButton("⋮") { showMenu() })
        outer.addView(controls)

        setContentView(outer)
        applyTheme()
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            setPadding(5, 2, 5, 2)
            setOnClickListener { action() }
        }

    private fun navigateFromBar() {
        val raw = addressBar.text.toString().trim()
        if (raw.isEmpty()) return
        val target = if (raw.contains(" ") || (!raw.contains(".") && !raw.startsWith("http"))) {
            "https://www.google.com/search?q=" + Uri.encode(raw)
        } else if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }
        currentWebView()?.loadUrl(target)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun addTab(url: String) {
        val web = WebView(this)
        configureWebView(web)
        val tab = BrowserTab(web, isPrivateWindow)
        tabs.add(tab)
        switchTab(tabs.lastIndex)
        web.loadUrl(url)
    }

    private fun configureWebView(web: WebView) {
        web.setBackgroundColor(Color.WHITE)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (isPrivateWindow) {
                cacheMode = WebSettings.LOAD_NO_CACHE
                saveFormData = false
                savePassword = false
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "mailto" || uri.scheme == "tel" || uri.scheme == "intent") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (AdTrackerBlocker.shouldBlock(request.url.host ?: "")) {
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        204, "No Content",
                        mapOf("Access-Control-Allow-Origin" to "*"),
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (view == currentWebView()) addressBar.setText(url ?: "")
                statusText.text = if (isPrivateWindow) "التصفح الخفي" else "جارٍ تحميل الصفحة…"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view == currentWebView()) {
                    addressBar.setText(url ?: "")
                    statusText.text = if (isPrivateWindow) "التصفح الخفي — لا يُحفظ السجل" else (view.title ?: "Nova Browser")
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view == currentWebView()) statusText.text =
                    if (isPrivateWindow) "التصفح الخفي • $newProgress%" else "تحميل الصفحة • $newProgress%"
            }
        }
        web.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        })
    }

    private fun enqueueDownload(url: String, userAgent: String, disposition: String, mime: String?) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val filename = URLUtil.guessFileName(url, disposition, mime)
            request.setTitle(filename)
            request.setDescription("Nova Browser")
            request.setMimeType(mime)
            request.addRequestHeader("User-Agent", userAgent)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "بدأ التحميل: $filename", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر بدء التحميل: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        currentWebView()?.let { pageHost.removeView(it) }
        activeIndex = index
        val web = tabs[index].webView
        (web.parent as? ViewGroup)?.removeView(web)
        pageHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        addressBar.setText(web.url ?: "")
        tabCounter.text = tabs.size.toString()
    }

    private fun currentWebView(): WebView? = tabs.getOrNull(activeIndex)?.webView

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val wasActive = index == activeIndex
        val removed = tabs.removeAt(index)
        pageHost.removeView(removed.webView)
        removed.webView.stopLoading()
        removed.webView.loadUrl("about:blank")
        removed.webView.clearHistory()
        removed.webView.removeAllViews()
        removed.webView.destroy()
        if (tabs.isEmpty()) {
            if (isPrivateWindow) finish() else addTab("https://www.google.com")
        } else {
            if (wasActive) activeIndex = -1
            else if (index < activeIndex) activeIndex--
            switchTab(if (wasActive) index.coerceAtMost(tabs.lastIndex) else activeIndex)
        }
        tabCounter.text = tabs.size.toString()
    }

    private fun showTabs() {
        val labels = tabs.mapIndexed { i, tab -> "${i + 1}. ${tab.webView.title ?: tab.webView.url ?: "صفحة جديدة"}" }
        val items = labels.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("التبويبات — اضغط للاختيار")
            .setItems(items) { _, which -> switchTab(which) }
            .setPositiveButton("تبويب جديد") { _, _ -> addTab("https://www.google.com") }
            .setNegativeButton("إغلاق التبويب الحالي") { _, _ -> closeTab(activeIndex) }
            .show()
    }

    private fun showMenu() {
        val actions = arrayOf(
            "ترجمة الصفحة إلى العربية",
            "إعادة تحميل",
            "مشاركة الرابط",
            "إضافة للمفضلة",
            "الوضع الليلي / النهاري",
            "مسح بيانات التصفح",
            "حول Nova Browser"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Nova Browser")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> translatePage()
                    1 -> currentWebView()?.reload()
                    2 -> sharePage()
                    3 -> Toast.makeText(this, "ميزة المفضلة ستضاف في التحديث التالي", Toast.LENGTH_SHORT).show()
                    4 -> { darkMode = !darkMode; applyTheme() }
                    5 -> clearBrowsingData()
                    6 -> android.app.AlertDialog.Builder(this).setMessage(
                        "Nova Browser 1.0\nمتصفح مبني على Android WebView.\nحجب الإعلانات والتتبع يعتمد على قائمة نطاقات قابلة للتحديث."
                    ).setPositiveButton("حسنًا", null).show()
                }
            }.show()
    }

    private fun translatePage() {
        val url = currentWebView()?.url ?: return
        val target = "https://translate.google.com/translate?sl=auto&tl=ar&u=" +
            URLEncoder.encode(url, "UTF-8")
        addTab(target)
    }

    private fun sharePage() {
        val url = currentWebView()?.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, "مشاركة الرابط"))
    }

    private fun loadHome() {
        currentWebView()?.loadUrl("https://www.google.com")
    }

    private fun clearBrowsingData() {
        tabs.forEach {
            it.webView.clearHistory()
            it.webView.clearCache(true)
            it.webView.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        Toast.makeText(this, "تم طلب مسح السجل والكوكيز وذاكرة التخزين المؤقت", Toast.LENGTH_LONG).show()
    }

    private fun openPrivateWindow() {
        if (isPrivateWindow) {
            addTab("https://www.google.com")
        } else {
            startActivity(Intent(this, IncognitoActivity::class.java))
        }
    }

    private fun applyTheme() {
        val bg = if (darkMode) Color.rgb(28, 30, 35) else Color.rgb(248, 249, 252)
        window.decorView.setBackgroundColor(bg)
        addressBar.setBackgroundColor(if (darkMode) Color.rgb(55, 58, 65) else Color.WHITE)
        addressBar.setTextColor(if (darkMode) Color.WHITE else Color.BLACK)
        statusText.setTextColor(if (darkMode) Color.LTGRAY else Color.DKGRAY)
    }

    override fun onBackPressed() {
        val web = currentWebView()
        if (web != null && web.canGoBack()) web.goBack()
        else if (isPrivateWindow) finish()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { tab ->
            try { tab.webView.stopLoading(); tab.webView.destroy() } catch (_: Exception) {}
        }
        tabs.clear()
        super.onDestroy()
    }
}

class MainActivity : BrowserActivity()

class IncognitoActivity : BrowserActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // This activity runs in its own process, so it can use a separate WebView data directory.
        try { WebView.setDataDirectorySuffix("incognito") } catch (_: IllegalStateException) {}
        super.onCreate(savedInstanceState)
    }
}

private object AdTrackerBlocker {
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", " taboola.com".trim(),
        "outbrain.com", "criteo.com", "adsrvr.org", "adform.net",
        "scorecardresearch.com", "quantserve.com", "hotjar.com",
        "mixpanel.com", "segment.io", "analytics.google.com",
        "facebook.net", "connect.facebook.net", "bat.bing.com"
    )

    fun shouldBlock(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        return blockedHosts.any { normalized == it || normalized.endsWith(".$it") }
    }
}