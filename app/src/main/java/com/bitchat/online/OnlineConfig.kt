package com.bitchat.online

import android.content.Context
import com.google.firebase.FirebaseOptions

object OnlineConfig {

    private const val PREFS = "bitchat_online"
    private const val KEY_ID = "firebase_project_id"
    private const val KEY_API = "firebase_api_key"

    private const val DEFAULT_PROJECT_ID = "ghostwire-mesh"
    private const val DEFAULT_API_KEY = "AIzaSyREVOKED_SCRUBBED_XXXXXXXXXXX"

    /** Only people who know this can reveal/change the API key in settings. */
    internal const val CONFIG_PASSWORD = "qwertyuiop0987654321"

    fun getProjectId(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ID, "").orEmpty().trim()
        return stored.ifEmpty { DEFAULT_PROJECT_ID }
    }

    fun getApiKey(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "").orEmpty().trim()
        return stored.ifEmpty { DEFAULT_API_KEY }
    }

    fun setCredentials(context: Context, projectId: String, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, projectId.trim())
            .putString(KEY_API, apiKey.trim())
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        val id = getProjectId(context)
        val key = getApiKey(context)
        return id.length >= 3 && key.length >= 20
    }

    fun getFirebaseOptions(context: Context): FirebaseOptions? {
        if (!isConfigured(context)) return null
        return try {
            FirebaseOptions.Builder()
                .setProjectId(getProjectId(context))
                .setApiKey(getApiKey(context))
                .setApplicationId("com.bitchat")
                .build()
        } catch (_: Exception) {
            null
        }
    }
}