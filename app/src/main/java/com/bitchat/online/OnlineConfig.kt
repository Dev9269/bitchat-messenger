package com.bitchat.online

import android.content.Context

object OnlineConfig {

    private const val PREFS = "bitchat_online"
    private const val KEY_ID = "firebase_project_id"
    private const val KEY_API = "firebase_api_key"

    fun getProjectId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ID, "").orEmpty().trim()

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty().trim()

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
}