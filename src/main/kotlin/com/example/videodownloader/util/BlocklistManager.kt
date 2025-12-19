package com.example.videodownloader.util

/**
 * Telif korumalı ve DRM'li platformları engelleyen blocklist sistemi.
 * Play Store policy uyumu için kritik.
 */
object BlocklistManager {
    
    // Yasaklı domain'ler - telif korumalı platformlar
    private val blockedDomains = setOf(
        // Video platformları
        "youtube.com", "youtu.be", "m.youtube.com", "music.youtube.com",
        "netflix.com", "www.netflix.com",
        "primevideo.com", "amazon.com", "amazon.co.uk", "amazon.de",
        "disneyplus.com", "hotstar.com",
        "hulu.com", "hbomax.com", "hbo.com",
        "twitch.tv", "www.twitch.tv",
        "spotify.com", "open.spotify.com",
        "apple.com", "music.apple.com", "tv.apple.com",
        "crunchyroll.com", "funimation.com",
        "paramount.com", "paramountplus.com",
        "peacocktv.com", "nbc.com",
        "discovery.com", "discoveryplus.com",
        "max.com", "cnnplus.com",
        
        // Türk platformları
        "exxen.com", "gain.tv", "puhu.tv", "blutv.com",
        "netflix.com.tr", "primevideo.com.tr",
        "digiturk.com.tr", "dsmart.com.tr",
        "trtizle.com", "atv.com.tr", "show.com.tr",
        "kanal7.com.tr", "kanald.com.tr", "startv.com.tr",
        
        // Sosyal medya (telif korumalı içerik)
        "instagram.com", "facebook.com", "tiktok.com",
        "twitter.com", "x.com"
    )
    
    // DRM pattern'leri
    private val drmPatterns = listOf(
        "widevine", "playready", "fairplay",
        "drm", "encrypted", "protected",
        "license", "manifest.mpd",
        "dash", "smooth",
        ".ism/", ".isml/",
        "protection", "contentprotection"
    )
    
    // Şüpheli URL pattern'leri
    private val suspiciousPatterns = listOf(
        "blob:", "data:",
        "chrome-extension://", "moz-extension://",
        "javascript:", "vbscript:",
        "file://", "ftp://"
    )
    
    /**
     * URL'nin engellenip engellenmeyeceğini kontrol eder
     */
    fun isBlocked(url: String): Boolean {
        val cleanUrl = url.lowercase().trim()
        
        // Boş URL
        if (cleanUrl.isBlank()) return true
        
        // Şüpheli protokoller
        if (suspiciousPatterns.any { cleanUrl.startsWith(it) }) {
            return true
        }
        
        // Domain kontrolü
        val domain = extractDomain(cleanUrl)
        if (domain != null && isBlockedDomain(domain)) {
            return true
        }
        
        // DRM pattern kontrolü
        if (drmPatterns.any { cleanUrl.contains(it) }) {
            return true
        }
        
        return false
    }
    
    /**
     * Engelleme nedenini döndürür
     */
    fun getBlockReason(url: String): String? {
        if (!isBlocked(url)) return null
        
        val cleanUrl = url.lowercase().trim()
        
        // Şüpheli protokol
        suspiciousPatterns.forEach { pattern ->
            if (cleanUrl.startsWith(pattern)) {
                return "Desteklenmeyen protokol: $pattern"
            }
        }
        
        // Engellenmiş domain
        val domain = extractDomain(cleanUrl)
        if (domain != null && isBlockedDomain(domain)) {
            return "Telif korumalı platform: $domain"
        }
        
        // DRM koruması
        drmPatterns.forEach { pattern ->
            if (cleanUrl.contains(pattern)) {
                return "DRM korumalı içerik tespit edildi"
            }
        }
        
        return "Bilinmeyen engelleme nedeni"
    }
    
    private fun extractDomain(url: String): String? {
        return try {
            val cleanUrl = if (!url.startsWith("http")) "https://$url" else url
            val uri = java.net.URI(cleanUrl)
            uri.host?.lowercase()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun isBlockedDomain(domain: String): Boolean {
        // Tam eşleşme
        if (blockedDomains.contains(domain)) return true
        
        // Subdomain kontrolü (örn: m.youtube.com, www.netflix.com)
        return blockedDomains.any { blocked ->
            domain.endsWith(".$blocked") || domain == blocked
        }
    }
    
    /**
     * Test amaçlı - blocklist'e domain ekle
     */
    fun addBlockedDomainForTesting(domain: String) {
        // Production'da kullanılmamalı
        // Test amaçlı geçici ekleme için
    }
}
