package com.aiassistant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val MAX_TOOL_ROUNDS = intPreferencesKey("max_tool_rounds")
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
                exaApiKey = preferences[SettingsKeys.EXA_API_KEY],
                maxToolRounds = preferences[SettingsKeys.MAX_TOOL_ROUNDS]
                    ?: DEFAULT_MAX_TOOL_ROUNDS
            )
        }

    /**
     * Writes the whole settings screen at once.
     *
     * One transaction, so the flow emits once with every key already in place. Written key by
     * key it emitted after each one, and readers -- the settings screen included -- saw a run
     * of half-saved states on the way past.
     *
     * A null is a cleared field, not an untouched one: it removes the key, so the value falls
     * back to whatever the read side defaults to. There is no partial update here; callers pass
     * the complete form.
     */
    suspend fun saveAll(
        apiKey: String?,
        apiBaseUrl: String?,
        defaultModel: String?,
        systemPrompt: String?,
        embeddingModel: String?,
        exaApiKey: String?,
        maxToolRounds: Int?
    ) {
        dataStore.edit { preferences ->
            preferences.put(SettingsKeys.API_KEY, apiKey)
            preferences.put(SettingsKeys.API_BASE_URL, apiBaseUrl)
            preferences.put(SettingsKeys.DEFAULT_MODEL, defaultModel)
            preferences.put(SettingsKeys.SYSTEM_PROMPT, systemPrompt)
            preferences.put(SettingsKeys.EMBEDDING_MODEL, embeddingModel)
            preferences.put(SettingsKeys.EXA_API_KEY, exaApiKey)
            preferences.put(SettingsKeys.MAX_TOOL_ROUNDS, maxToolRounds?.coerceIn(1, 50))
        }
    }

    suspend fun saveApiKey(key: String?) {
        dataStore.edit { it.put(SettingsKeys.API_KEY, key) }
    }

    suspend fun saveApiBaseUrl(url: String?) {
        dataStore.edit { it.put(SettingsKeys.API_BASE_URL, url) }
    }

    suspend fun saveDefaultModel(model: String?) {
        dataStore.edit { it.put(SettingsKeys.DEFAULT_MODEL, model) }
    }

    suspend fun saveSystemPrompt(prompt: String?) {
        dataStore.edit { it.put(SettingsKeys.SYSTEM_PROMPT, prompt) }
    }

    suspend fun saveEmbeddingModel(model: String?) {
        dataStore.edit { it.put(SettingsKeys.EMBEDDING_MODEL, model) }
    }

    suspend fun saveExaApiKey(key: String?) {
        dataStore.edit { it.put(SettingsKeys.EXA_API_KEY, key) }
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

    suspend fun saveMaxToolRounds(rounds: Int?) {
        dataStore.edit { it.put(SettingsKeys.MAX_TOOL_ROUNDS, rounds?.coerceIn(1, 50)) }
    }

    suspend fun getMaxToolRounds(): Int {
        return dataStore.data
            .map { it[SettingsKeys.MAX_TOOL_ROUNDS] ?: DEFAULT_MAX_TOOL_ROUNDS }
            .first()
    }
}

/** Stores [value], or removes the key when it is null -- an absent key reads back as the default. */
private fun <T : Any> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else set(key, value)
}
