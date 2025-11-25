package com.example.videodownloader.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class WarmUpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        setContentView(wv)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Throwable) {}
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        try { s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW } catch (_: Throwable) {}
        val userAgent = intent.getStringExtra("ua") ?: "Mozilla/5.0 (Android) VideoDownloader/1.0"
        s.userAgentString = userAgent
        val pageUrl = intent.getStringExtra("pageUrl")
        val iframeUrl = intent.getStringExtra("iframeUrl")
        val originUrl = intent.getStringExtra("originUrl")
        val targetUrl = intent.getStringExtra("targetUrl")

        var step = 0
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.postDelayed({
                    val got = try { cm.getCookie(targetUrl ?: url) } catch (_: Throwable) { null }
                    if (!got.isNullOrBlank()) {
                        finish()
                        return@postDelayed
                    }
                    step += 1
                    when (step) {
                        1 -> if (!iframeUrl.isNullOrBlank()) view.loadUrl(iframeUrl) else view.loadUrl(originUrl ?: url)
                        2 -> if (!originUrl.isNullOrBlank()) view.loadUrl(originUrl) else finish()
                        else -> finish()
                    }
                }, 1500)
            }
        }
        when {
            !pageUrl.isNullOrBlank() -> wv.loadUrl(pageUrl)
            !iframeUrl.isNullOrBlank() -> wv.loadUrl(iframeUrl)
            !originUrl.isNullOrBlank() -> wv.loadUrl(originUrl)
            else -> finish()
        }
    }
}
