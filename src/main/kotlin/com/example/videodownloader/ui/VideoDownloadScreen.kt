package com.example.videodownloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloadScreen(
    viewModel: VideoDownloadViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showBrowser by remember { mutableStateOf(false) }
    var showCapturedDialog by remember { mutableStateOf(false) }
    var lastCount by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Başlık
        Text(
            text = "Video İndirici",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Arama çubuğu
        OutlinedTextField(
            value = uiState.urlText,
            onValueChange = viewModel::onUrlChange,
            label = { Text("Video linki girin") },
            placeholder = { Text("https://youtube.com/watch?v=...") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Arama")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Tek buton: Ara ve Tarayıcıyı Aç
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = {
                    if (uiState.urlText.isNotBlank()) {
                        viewModel.setCurrentPageUrl(uiState.urlText)
                        showBrowser = true
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        // UA: bazı sunucular mobil UA ister
                        settings.userAgentString = "Mozilla/5.0 (Android) VideoDownloader/1.0"

                        // Cookies (gerekli oturum/cf korumaları için)
                        val cookieMgr = CookieManager.getInstance()
                        cookieMgr.setAcceptCookie(true)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cookieMgr.setAcceptThirdPartyCookies(this, true)
                        }

                        // Enable remote debugging and console logging
                        WebView.setWebContentsDebuggingEnabled(true)

                        // JS bridge to receive URLs from injected script
                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun mediaFound(u: String) {
                                post { 
                                    android.util.Log.d("AndroidBridge", "Captured: $u")
                                    onMediaFound(u) 
                                }
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun subtitlesFound(jsonStr: String) {
                                post {
                                    android.util.Log.d("AndroidBridge", "Subtitles: $jsonStr")
                                    // JSON parse edip subtitle URL'lerini ekle
                                    try {
                                        val json = org.json.JSONObject(jsonStr)
                                        val subsArray = json.optJSONArray("subs")
                                        if (subsArray != null) {
                                            for (i in 0 until subsArray.length()) {
                                                val subUrl = subsArray.optString(i)
                                                if (subUrl.isNotBlank() && !subUrl.startsWith("#")) {
                                                    onMediaFound(subUrl)
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("AndroidBridge", "Subtitle parse error", e)
                                    }
                                }
                            }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    request?.url?.toString()?.let {
                                        onPageUrlChange(it)
                                        val ck = CookieManager.getInstance().getCookie(it)
                                        view?.post { onCookiesChange(ck) }
                                    }
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let {
                                    onPageUrlChange(it)
                                    val ck = CookieManager.getInstance().getCookie(it)
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
                                    val lower = url.lowercase()
                                    // Video ve subtitle URL'lerini yakala
                                    if (lower.contains(".mp4") || lower.contains(".m3u8") ||
                                        lower.contains(".vtt") || lower.contains(".srt") ||
                                        lower.contains(".ass") || lower.contains(".ssa")) {
                                        
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
                                val inject = """
                                    (function(){
                                      try {
                                        if (window.__hijacked) return; window.__hijacked = true;
                                        function report(u){ 
                                          try { 
                                            if(u && (u.toLowerCase().includes('.m3u8') || u.toLowerCase().includes('.mp4') || 
                                                     u.toLowerCase().includes('.vtt') || u.toLowerCase().includes('.srt') ||
                                                     u.toLowerCase().includes('.ass') || u.toLowerCase().includes('.ssa'))) { 
                                              console.log('[XHR Hijack] Reporting: ' + u);
                                              AndroidBridge.mediaFound(u); 
                                            } 
                                          } catch(e){
                                            console.error('[XHR Hijack] Error reporting: ' + e.message);
                                          }
                                        }
                                        var origFetch = window.fetch; if (origFetch) {
                                          window.fetch = function(){ try { var a = arguments; var u = a && a[0]; if (typeof u === 'string') report(u); else if (u && u.url) report(u.url); } catch(e){} return origFetch.apply(this, arguments); };
                                        }
                                        var OrigXHR = window.XMLHttpRequest; if (OrigXHR) {
                                          var open = OrigXHR.prototype.open; OrigXHR.prototype.open = function(method, url){ try { report(url); } catch(e){} return open.apply(this, arguments); };
                                        }
                                        // hls.js hook if available
                                        if (window.Hls && window.hls instanceof window.Hls) {
                                          try { window.hls.on(window.Hls.Events.MANIFEST_LOADED, function(ev, data){ try { if (data && data.networkDetails && data.networkDetails.responseURL) report(data.networkDetails.responseURL); } catch(e){} }); } catch(e){}
                                        }
                                        console.log('[XHR Hijack] Initialized successfully');
                                      } catch(e) {
                                        console.error('[XHR Hijack] Initialization error: ' + e.message);
                                      }
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(inject, null)
                                
                                // Playerjs subtitle extraction - interval ile tekrar dene
                                var attemptCount = 0
                                val maxAttempts = 3 // Sadece 3 deneme (shouldInterceptRequest ve XHR hijacking ana yöntemler)
                                val checkInterval = 3000L // 3 saniye
                                
                                fun tryExtractSubtitles() {
                                    attemptCount++
                                    val playerjsExtract = """
                                        (function(){
                                          try {
                                            console.log('[Subtitle Extractor] Attempt ' + $attemptCount + ' - Checking for Playerjs...');
                                            
                                            // Debug: Window'da neler var?
                                            var windowKeys = Object.keys(window).filter(function(k) {
                                              return k.toLowerCase().includes('player') || k.toLowerCase().includes('sub') || k === 'o';
                                            });
                                            console.log('[Subtitle Extractor] Window keys related to player/sub: ' + windowKeys.join(', '));
                                            
                                            // Method 1: Global Playerjs object
                                            if (window.Playerjs) {
                                              console.log('[Subtitle Extractor] Found window.Playerjs');
                                              console.log('[Subtitle Extractor] Playerjs type: ' + typeof window.Playerjs);
                                              
                                              var instances = window.Playerjs.instances || [];
                                              console.log('[Subtitle Extractor] Instances count: ' + instances.length);
                                              
                                              for (var i = 0; i < instances.length; i++) {
                                                var player = instances[i];
                                                console.log('[Subtitle Extractor] Instance ' + i + ' exists: ' + !!player);
                                                if (player && player.o) {
                                                  console.log('[Subtitle Extractor] player.o exists');
                                                  console.log('[Subtitle Extractor] player.o.subs exists: ' + !!player.o.subs);
                                                  console.log('[Subtitle Extractor] player.o.subs type: ' + typeof player.o.subs);
                                                  
                                                  if (player.o.subs && Array.isArray(player.o.subs)) {
                                                    var result = {
                                                      subs: player.o.subs,
                                                      files_subtitle: player.o.files_subtitle || []
                                                    };
                                                    console.log('[Subtitle Extractor] ✅ Subs found: ' + player.o.subs.length);
                                                    console.log('[Subtitle Extractor] Subs content: ' + JSON.stringify(player.o.subs));
                                                    AndroidBridge.subtitlesFound(JSON.stringify(result));
                                                    return 'FOUND:' + player.o.subs.length;
                                                  }
                                                }
                                              }
                                            }
                                            
                                            // Method 2: Direct window check
                                            if (window.o) {
                                              console.log('[Subtitle Extractor] Found window.o');
                                              console.log('[Subtitle Extractor] window.o.subs exists: ' + !!window.o.subs);
                                              
                                              if (window.o.subs) {
                                                console.log('[Subtitle Extractor] window.o.subs type: ' + typeof window.o.subs);
                                                console.log('[Subtitle Extractor] window.o.subs isArray: ' + Array.isArray(window.o.subs));
                                                
                                                var result = {
                                                  subs: window.o.subs,
                                                  files_subtitle: window.o.files_subtitle || []
                                                };
                                                console.log('[Subtitle Extractor] ✅ Subs found in window.o: ' + (Array.isArray(window.o.subs) ? window.o.subs.length : 'not array'));
                                                console.log('[Subtitle Extractor] Subs content: ' + JSON.stringify(window.o.subs));
                                                AndroidBridge.subtitlesFound(JSON.stringify(result));
                                                return 'FOUND:' + window.o.subs.length;
                                              }
                                            }
                                            
                                            // Method 3: Search in iframes
                                            var frames = document.getElementsByTagName('iframe');
                                            console.log('[Subtitle Extractor] Checking ' + frames.length + ' iframes...');
                                            for (var f = 0; f < frames.length; f++) {
                                              try {
                                                var frameWin = frames[f].contentWindow;
                                                if (frameWin && frameWin.o && frameWin.o.subs) {
                                                  console.log('[Subtitle Extractor] ✅ Found in iframe #' + f);
                                                  var result = {
                                                    subs: frameWin.o.subs,
                                                    files_subtitle: frameWin.o.files_subtitle || []
                                                  };
                                                  AndroidBridge.subtitlesFound(JSON.stringify(result));
                                                  return 'FOUND_IFRAME:' + frameWin.o.subs.length;
                                                }
                                              } catch (e) { 
                                                console.log('[Subtitle Extractor] Iframe #' + f + ' cross-origin error');
                                              }
                                            }
                                            
                                            console.log('[Subtitle Extractor] ❌ No subtitles found (attempt ' + $attemptCount + ')');
                                            return 'NOT_FOUND';
                                          } catch(e) {
                                            console.error('[Subtitle Extractor] Error: ' + e.message);
                                            return 'ERROR:' + e.message;
                                          }
                                        })();
                                    """.trimIndent()

                                    if (view != null) {
                                        view?.evaluateJavascript(playerjsExtract) { result ->
                                            android.util.Log.d("PlayerjsExtract", "Attempt $attemptCount result: $result")
                                            
                                            // Eğer bulunamadıysa ve henüz max'e ulaşmadıysak tekrar dene
                                            if (result?.contains("NOT_FOUND") == true && attemptCount < maxAttempts) {
                                                view?.postDelayed({ tryExtractSubtitles() }, checkInterval)
                                            }
                                        }
                                    }
                                }
                                
                                // İlk deneme 5 saniye sonra başlasın (video yüklensin diye)
                                // NOT: shouldInterceptRequest ve XHR hijacking zaten subtitle'ları yakalıyor
                                // Bu sadece ekstra kontrol için
                                view?.postDelayed({ tryExtractSubtitles() }, 5000)
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

                        val extraHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Android) VideoDownloader/1.0",
                            "Accept-Language" to "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4",
                            "Upgrade-Insecure-Requests" to "1"
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
