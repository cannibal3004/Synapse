package com.aiassistant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object SettingsRepository {
    private val API_KEY_KEY = stringPreferencesKey("api_key")
    private val API_BASE_URL_KEY = stringPreferencesKey("api_base_url")
    private val DEFAULT_MODEL_KEY = stringPreferencesKey("default_model")
    private val SYSTEM_PROMPT_KEY = stringPreferencesKey("system_prompt")
    private val EMBEDDING_MODEL_KEY = stringPreferencesKey("embedding_model")

    suspend fun saveSettings(
        apiKey: String?,
        apiBaseUrl: String?,
        defaultModel: String?,
        systemPrompt: String?,
        embeddingModel: String?
    ) {
        // This will be implemented in the DI layer with proper Context injection
    }

    data class AppSettings(
        val apiKey: String? = null,
        val apiBaseUrl: String? = null,
        val defaultModel: String? = "gpt-3.5-turbo",
        val systemPrompt: String? = null,
        val embeddingModel: String? = "text-embedding-3-small",
        val exaApiKey: String? = null
    )

    fun getSettings(): Flow<AppSettings> {
        // Placeholder - will be implemented with proper DI
        return kotlinx.coroutines.flow.flow {
            emit(AppSettings())
        }
    }
}
