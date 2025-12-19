package com.example.videodownloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.videodownloader.model.VideoItem
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.os.Build
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.view.ViewGroup
import android.content.pm.ApplicationInfo
import com.example.videodownloader.util.PreferencesManager
import com.example.videodownloader.util.BlocklistManager
import kotlin.system.exitProcess
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloadScreen(
    viewModel: VideoDownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val uiState by viewModel.uiState.collectAsState()
    var showBrowser by remember { mutableStateOf(false) }
    var showCapturedDialog by remember { mutableStateOf(false) }
    var lastCount by remember { mutableStateOf(0) }
    var showTermsDialog by remember { mutableStateOf(!prefsManager.isTermsAccepted()) }
    var showSettings by remember { mutableStateOf(false) }
    
    // Terms dialog
    if (showTermsDialog) {
        TermsDialog(
            onAccept = {
                prefsManager.setTermsAccepted(true)
                showTermsDialog = false
            },
            onDecline = {
                // Uygulamayı kapat
                exitProcess(0)
            }
        )
        return // Terms kabul edilene kadar ana ekranı gösterme
    }
    
    // Settings screen
    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Başlık ve ayarlar butonu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Video İndirici",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { showSettings = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Ayarlar",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Arama çubuğu
        OutlinedTextField(
            value = uiState.urlText,
            onValueChange = viewModel::onUrlChange,
            label = { Text("Video linki girin") },
            placeholder = { Text("https://example.com/video...") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Arama")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Butonlar: Ara ve Aç + Test Bildirimi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            // Test bildirimi butonu (debug için)
            Button(
                onClick = {
                    viewModel.testNotification()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Test Bildirim")
            }
            
            Button(
                onClick = {
                    if (uiState.urlText.isNotBlank()) {
                        // URL'yi açmadan önce blocklist kontrolü
                        if (BlocklistManager.isBlocked(uiState.urlText)) {
                            val reason = BlocklistManager.getBlockReason(uiState.urlText) ?: "Engellenmiş platform"
                            Toast.makeText(context, "⚠️ $reason", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.setCurrentPageUrl(uiState.urlText)
                            showBrowser = true
                        }
                    }
                },
                enabled = !uiState.isLoading && uiState.urlText.isNotBlank()
            ) {
                Text("Ara ve Aç")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Loading durumu
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // Video listesi
        if (uiState.videos.isNotEmpty()) {
            Text(
                text = "İndirilebilir Videolar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.videos) { video ->
                    VideoItemCard(
                        video = video,
                        onDownloadClick = { viewModel.downloadVideo(video) }
                    )
                }
            }
        }
    }

    if (showBrowser) {
        MiniBrowserDialog(
            startUrl = uiState.urlText,
            onClose = { showBrowser = false },
            onMediaFound = { url -> viewModel.onMediaFound(url) },
            onPageUrlChange = { url -> viewModel.setCurrentPageUrl(url) },
            onCookiesChange = { ck -> viewModel.setCurrentCookies(ck) }
        )
    }

    // Yeni medya yakalanınca bildirim dialogu göster
    LaunchedEffect(uiState.videos.size) {
        if (uiState.videos.size > lastCount) {
            lastCount = uiState.videos.size
            showCapturedDialog = true
        }
    }
    if (showCapturedDialog && uiState.videos.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showCapturedDialog = false },
            confirmButton = {
                TextButton(onClick = { showCapturedDialog = false }) { Text("Tamam") }
            },
            title = { Text("Yeni Videolar Bulundu") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    uiState.videos.forEach { video ->
                        val sizeLabel = video.sizeBytes?.let { String.format("%.1f MB", it.toDouble() / (1024*1024)) } ?: "?"
                        Text(text = "• ${video.title} ($sizeLabel)")
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        )
    }
}

@Composable
fun MiniBrowserDialog(
    startUrl: String,
    onClose: () -> Unit,
    onMediaFound: (String) -> Unit,
    onPageUrlChange: (String) -> Unit,
    onCookiesChange: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text("Kapat") }
        },
        title = { Text("Mini Tarayıcı") },
        text = {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        // WebView ayarlarını gerçek tarayıcıya benzet (bot/VPN koruması bypass)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.allowFileAccess = false
                        settings.allowContentAccess = true
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        
                        // Gerçek Chrome/Android tarayıcı User-Agent (Cloudflare/bot koruması bypass)
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        // Cookies (gerekli oturum/cf korumaları için)
                        val cookieMgr = CookieManager.getInstance()
                        cookieMgr.setAcceptCookie(true)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cookieMgr.setAcceptThirdPartyCookies(this, true)
                        }

                        // Enable remote debugging and console logging (debug builds only)
                        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        WebView.setWebContentsDebuggingEnabled(isDebug)

                        // Blocklist for DRM/license and major streaming platforms (Play policy safety)
                        val blockedHosts = listOf(
                            "youtube.com", "youtu.be", "googlevideo.com",
                            "netflix.com", "nflxvideo.net",
                            "amazon.com", "primevideo.com", "media-amazon.com",
                            "disneyplus.com", "hulu.com", "hbomax.com",
                            "paramountplus.com", "tv.apple.com", "apple.com"
                        )
                        val blockedKeywords = listOf(
                            "widevine", "drm", "license", "clearkey", "playready", "fairplay"
                        )
                        fun isBlocked(url: String): Boolean {
                            return try {
                                val uri = android.net.Uri.parse(url)
                                val host = uri.host?.lowercase()
                                val hBlocked = host != null && blockedHosts.any { host.contains(it) }
                                val kBlocked = blockedKeywords.any { url.lowercase().contains(it) }
                                hBlocked || kBlocked
                            } catch (_: Exception) { false }
                        }

                        // Legal yöntem: sadece shouldInterceptRequest kullan
                        // JavaScript injection KULLANMIYORUZ - legal değil ve gereksiz

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    request?.url?.toString()?.let { url ->
                                        // Engellenmiş domainleri kontrol et - sayfa açılmasın
                                        if (BlocklistManager.isBlocked(url)) {
                                            val reason = BlocklistManager.getBlockReason(url) ?: "Engellenmiş platform"
                                            view?.post {
                                                Toast.makeText(view.context, "⚠️ $reason", Toast.LENGTH_LONG).show()
                                            }
                                            return true // URL yüklenmesini engelle
                                        }
                                        
                                        onPageUrlChange(url)
                                        val ck = CookieManager.getInstance().getCookie(url)
                                        view?.post { onCookiesChange(ck) }
                                    }
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { pageUrl ->
                                    // Sayfa yüklenmeye başlarken de kontrol et
                                    if (BlocklistManager.isBlocked(pageUrl)) {
                                        view?.stopLoading()
                                        val reason = BlocklistManager.getBlockReason(pageUrl) ?: "Engellenmiş platform"
                                        view?.post {
                                            Toast.makeText(view.context, "⚠️ $reason", Toast.LENGTH_LONG).show()
                                        }
                                        return
                                    }
                                    
                                    onPageUrlChange(pageUrl)
                                    val ck = CookieManager.getInstance().getCookie(pageUrl)
                                    view?.post { onCookiesChange(ck) }
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    request?.url?.toString()
                                } else null
                                if (url != null) {
                                    // Skip DRM/license and blocked platforms
                                    if (isBlocked(url)) {
                                        android.util.Log.d("InterceptRequest", "Blocked URL: $url")
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    val lower = url.lowercase()
                                    // LEGAL YÖNTEM: WebView API ile medya dosyalarını intercept et
                                    // Video formatları
                                    val isVideo = lower.contains(".mp4") || lower.contains(".m3u8") || 
                                                  lower.contains(".webm") || lower.contains(".mkv") ||
                                                  lower.contains(".ts") || lower.contains(".mpd")
                                    
                                    // Altyazı formatları (genişletilmiş)
                                    val isSubtitle = lower.contains(".vtt") || lower.contains(".srt") ||
                                                     lower.contains(".ass") || lower.contains(".ssa") ||
                                                     lower.contains(".sub") || lower.contains(".sbv") ||
                                                     lower.contains(".ttml") || lower.contains(".dfxp") ||
                                                     lower.contains(".smi") || lower.contains(".sami")
                                    
                                    if (isVideo || isSubtitle) {
                                        
                                        android.util.Log.d("InterceptRequest", "Captured URL: $url")
                                        
                                        // Eğer bu istek bir alt kaynak ise ve Referer başlığı varsa, ViewModel'e aktar
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                            val ref = request?.requestHeaders?.get("Referer")
                                            if (!ref.isNullOrBlank()) {
                                                view?.post { onPageUrlChange(ref) }
                                            }
                                        }
                                        // UI thread'e post et
                                        view?.post { onMediaFound(url) }
                                    }
                                    // Akış sırasında çerezleri güncelle
                                    val ck = CookieManager.getInstance().getCookie(url)
                                    view?.post { onCookiesChange(ck) }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Legal yöntem: shouldInterceptRequest zaten tüm medya dosyalarını yakalıyor
                                // JavaScript injection kullanmıyoruz
                                android.util.Log.d("WebView", "Page loaded: $url")
                            }
                        }

                        // Console logger
                        setWebChromeClient(object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                android.util.Log.i(
                                    "WebConsole",
                                    "${'$'}{consoleMessage?.message()} (source: ${'$'}{consoleMessage?.sourceId()}, line: ${'$'}{consoleMessage?.lineNumber()})"
                                )
                                return super.onConsoleMessage(consoleMessage)
                            }
                        })

                        // Gerçek tarayıcı header'ları (Cloudflare/bot koruması bypass)
                        val extraHeaders = mapOf(
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                            "Accept-Encoding" to "gzip, deflate, br",
                            "Upgrade-Insecure-Requests" to "1",
                            "Sec-Fetch-Dest" to "document",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "none",
                            "Sec-Fetch-User" to "?1",
                            "Cache-Control" to "max-age=0"
                        )
                        loadUrl(startUrl, extraHeaders)
                        onPageUrlChange(startUrl)
                        val ck = CookieManager.getInstance().getCookie(startUrl)
                        onCookiesChange(ck)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            )
        }
    )
}

@Composable
fun VideoItemCard(
    video: VideoItem,
    onDownloadClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(video.thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Video thumbnail",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Başlık
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Boyut
            if (video.sizeBytes != null && video.sizeBytes > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                val mb = video.sizeBytes.toDouble() / (1024 * 1024)
                Text(
                    text = String.format("%.1f MB", mb),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // İndir butonu
            Button(
                onClick = onDownloadClick,
                modifier = Modifier.wrapContentSize()
            ) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = "İndir",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("İndir")
            }
        }
    }
}
