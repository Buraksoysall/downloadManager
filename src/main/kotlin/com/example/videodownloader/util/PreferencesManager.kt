package com.example.videodownloader.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Kullanıcı tercihlerini yöneten sınıf.
 * Terms onayı ve diğer ayarları saklar.
 */
class PreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "video_downloader_prefs", 
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"
        private const val KEY_TERMS_VERSION = "terms_version"
        private const val CURRENT_TERMS_VERSION = 1
    }
    
    /**
     * Terms kabul edilmiş mi kontrol eder
     */
    fun isTermsAccepted(): Boolean {
        val accepted = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        val version = prefs.getInt(KEY_TERMS_VERSION, 0)
        
        // Terms güncellenirse tekrar onay istenir
        return accepted && version >= CURRENT_TERMS_VERSION
    }
    
    /**
     * Terms onayını kaydeder
     */
    fun setTermsAccepted(accepted: Boolean) {
        prefs.edit()
            .putBoolean(KEY_TERMS_ACCEPTED, accepted)
            .putInt(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
            .apply()
    }
    
    /**
     * Terms onayını sıfırlar (test amaçlı)
     */
    fun resetTermsAcceptance() {
        prefs.edit()
            .remove(KEY_TERMS_ACCEPTED)
            .remove(KEY_TERMS_VERSION)
            .apply()
    }
}
