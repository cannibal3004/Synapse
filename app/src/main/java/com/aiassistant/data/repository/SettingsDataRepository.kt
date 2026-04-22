package com.aiassistant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

object SettingsKeys {
    val API_KEY = stringPreferencesKey("api_key")
    val API_BASE_URL = stringPreferencesKey("api_base_url")
    val DEFAULT_MODEL = stringPreferencesKey("default_model")
    val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
    val EXA_API_KEY = stringPreferencesKey("exa_api_key")
}

@Singleton
class SettingsDataRepository @javax.inject.Inject constructor(
    private val context: Context
) {
    private val dataStore = context.settingsDataStore

    val settings: Flow<SettingsRepository.AppSettings> = dataStore.data
        .map { preferences ->
            SettingsRepository.AppSettings(
                apiKey = preferences[SettingsKeys.API_KEY],
                apiBaseUrl = preferences[SettingsKeys.API_BASE_URL],
                defaultModel = preferences[SettingsKeys.DEFAULT_MODEL],
                systemPrompt = preferences[SettingsKeys.SYSTEM_PROMPT],
                embeddingModel = preferences[SettingsKeys.EMBEDDING_MODEL] ?: "text-embedding-3-small",
                exaApiKey = preferences[SettingsKeys.EXA_API_KEY]
            )
        }

    suspend fun saveApiKey(key: String?) {
        dataStore.edit { preferences ->
            if (key != null) {
                preferences[SettingsKeys.API_KEY] = key
            } else {
                preferences.remove(SettingsKeys.API_KEY)
            }
        }
    }

    suspend fun saveApiBaseUrl(url: String?) {
        dataStore.edit { preferences ->
            if (url != null) {
                preferences[SettingsKeys.API_BASE_URL] = url
            } else {
                preferences.remove(SettingsKeys.API_BASE_URL)
            }
        }
    }

    suspend fun saveDefaultModel(model: String?) {
        dataStore.edit { preferences ->
            if (model != null) {
                preferences[SettingsKeys.DEFAULT_MODEL] = model
            } else {
                preferences.remove(SettingsKeys.DEFAULT_MODEL)
            }
        }
    }

    suspend fun saveSystemPrompt(prompt: String?) {
        dataStore.edit { preferences ->
            if (prompt != null) {
                preferences[SettingsKeys.SYSTEM_PROMPT] = prompt
            } else {
                preferences.remove(SettingsKeys.SYSTEM_PROMPT)
            }
        }
    }

    suspend fun saveEmbeddingModel(model: String?) {
        dataStore.edit { preferences ->
            if (model != null) {
                preferences[SettingsKeys.EMBEDDING_MODEL] = model
            } else {
                preferences.remove(SettingsKeys.EMBEDDING_MODEL)
            }
        }
    }

    suspend fun saveExaApiKey(key: String?) {
        dataStore.edit { preferences ->
            if (key != null) {
                preferences[SettingsKeys.EXA_API_KEY] = key
            } else {
                preferences.remove(SettingsKeys.EXA_API_KEY)
            }
        }
    }

    suspend fun getApiKey(): String? {
        return dataStore.data.map { it[SettingsKeys.API_KEY] }.first()
    }

    suspend fun getApiBaseUrl(): String? {
        return dataStore.data.map { it[SettingsKeys.API_BASE_URL] }.first()
    }

    suspend fun getDefaultModel(): String? {
        return dataStore.data.map { it[SettingsKeys.DEFAULT_MODEL] }.first()
    }

    suspend fun getSystemPrompt(): String? {
        return dataStore.data.map { it[SettingsKeys.SYSTEM_PROMPT] }.first()
    }
}
