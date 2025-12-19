# Legal Altyazı Bulma Yöntemleri

Bu uygulama artık **tamamen legal yöntemlerle** video ve altyazı dosyalarını buluyor.

## ❌ Kaldırılan Yöntem: JavaScript Injection

**Önceki sorunlu yöntem:**
- `window.XMLHttpRequest` ve `window.fetch` hijacking
- `window.Playerjs` ve `window.o` global objelerine erişim
- `evaluateJavascript` ile DOM manipülasyonu

**Neden kaldırıldı:**
- Sitelerin güvenlik politikalarını ihlal ediyor
- CSP (Content Security Policy) tarafından engellenebilir
- Her sitede çalışmayabilir
- Etik olmayan bir yöntem

## ✅ Kullanılan Legal Yöntemler

### 1. **shouldInterceptRequest (WebView API)**
```kotlin
override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?
): WebResourceResponse?
```

**Ne yapar:**
- WebView'ın resmi API'si
- Tüm network isteklerini yasal olarak intercept eder
- Video ve subtitle URL'lerini yakalar

**Desteklenen formatlar:**
- Video: `.mp4`, `.m3u8`, `.webm`, `.mkv`, `.ts`, `.mpd`
- Altyazı: `.vtt`, `.srt`, `.ass`, `.ssa`, `.sub`, `.sbv`, `.ttml`, `.dfxp`, `.smi`, `.sami`

### 2. **HLS Manifest Parsing**
```kotlin
private fun parseSubtitleRenditions(master: String, baseUrl: String): List<SubtitleRendition>
```

**Ne yapar:**
- M3U8 (HLS) master playlist dosyalarını parse eder
- `#EXT-X-MEDIA TYPE=SUBTITLES` satırlarını bulur
- Altyazı URL'lerini legal olarak çıkarır

**Örnek HLS manifest:**
```m3u8
#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,URI="sub-en.m3u8",NAME="English",LANGUAGE="en"
#EXT-X-MEDIA:TYPE=SUBTITLES,URI="sub-tr.m3u8",NAME="Türkçe",LANGUAGE="tr"
```

### 3. **SubtitleInspector (ExoPlayer Integration)**
```kotlin
fun listSubtitleTracksWithUrls(player: ExoPlayer): List<SubtitleTrackInfo>
```

**Ne yapar:**
- ExoPlayer'ın resmi API'sini kullanır
- Video oynatıcıdan altyazı track'lerini okur
- HLS rendition'larını fetch ederek parse eder

### 4. **Content-Type Detection**
```kotlin
private fun detectUrlKind(url: String): UrlKind
```

**Ne yapar:**
- HTTP HEAD request ile Content-Type header'ı okur
- MIME type'a göre dosya formatını tespit eder
- Referer ve Cookie header'larını düzgün şekilde kullanır

**Örnek Content-Type'lar:**
- `text/vtt` → WebVTT altyazı
- `application/x-subrip` → SRT altyazı
- `application/ttml+xml` → TTML altyazı

### 5. **HTML/JavaScript Asset Scanning**
```kotlin
private fun fetchAndScanAssets(html: String, baseUrl: String): List<Pair<String, String>>
```

**Ne yapar:**
- HTML içindeki `<script src="">` ve JSON dosyalarını tarar
- İçlerinde altyazı URL'lerini regex ile arar
- Hiçbir DOM manipülasyonu yapmaz (sadece okur)

## 🔒 Güvenlik ve Etik

Tüm yöntemler:
- ✅ Android WebView ve ExoPlayer API'lerini kullanıyor
- ✅ Sadece public network trafiğini izliyor
- ✅ Hiçbir güvenlik mekanizmasını bypass etmiyor
- ✅ CSP ile uyumlu
- ✅ Her sitede aynı şekilde çalışıyor

## 📊 Performans

**Eski yöntem (JavaScript Injection):**
- 3-5 saniye bekleme süresi
- Her sayfa için tekrarlı injection
- Sitede hata riski

**Yeni yöntem (Legal API'ler):**
- Anında yakalama
- Tek seferlik network intercept
- Hiçbir hata riski yok

## 🎯 Kullanım

Uygulama artık otomatik olarak:
1. WebView'da gezindiğiniz sayfalarda medya isteklerini yakalar
2. M3U8 master playlist'lerini parse eder
3. Altyazı dosyalarını listeler
4. İndirmeye hazır hale getirir

**Hiçbir ek ayar gerekmez!**
