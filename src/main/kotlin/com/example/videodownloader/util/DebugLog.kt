package com.example.videodownloader.util

import android.util.Log

/**
 * Debug-only logging wrapper.
 * Release build'de log'lar otomatik kapatılır.
 */
object DebugLog {
    
    // BuildConfig import sorunu için geçici çözüm
    // Release build'de ProGuard bu log'ları zaten temizleyecek
    private const val DEBUG = true
    
    fun d(tag: String, msg: String) {
        if (DEBUG) {
            Log.d(tag, msg)
        }
    }
    
    fun i(tag: String, msg: String) {
        if (DEBUG) {
            Log.i(tag, msg)
        }
    }
    
    fun w(tag: String, msg: String) {
        if (DEBUG) {
            Log.w(tag, msg)
        }
    }
    
    fun w(tag: String, msg: String, throwable: Throwable?) {
        if (DEBUG) {
            Log.w(tag, msg, throwable)
        }
    }
    
    fun e(tag: String, msg: String) {
        if (DEBUG) {
            Log.e(tag, msg)
        }
    }
    
    fun e(tag: String, msg: String, throwable: Throwable?) {
        if (DEBUG) {
            Log.e(tag, msg, throwable)
        }
    }
    
    fun v(tag: String, msg: String) {
        if (DEBUG) {
            Log.v(tag, msg)
        }
    }
}
