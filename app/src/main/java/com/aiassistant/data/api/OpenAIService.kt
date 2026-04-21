package com.aiassistant.data.api

import com.aiassistant.data.model.api.ChatCompletionRequest
import com.aiassistant.data.model.api.ChatCompletionResponse
import com.aiassistant.data.model.api.EmbeddingRequest
import com.aiassistant.data.model.api.EmbeddingResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface OpenAIService {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse

    @POST("chat/completions")
    @Streaming
    suspend fun chatCompletionStream(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: ChatCompletionRequest
    ): okhttp3.ResponseBody

    @POST("embeddings")
    suspend fun createEmbedding(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: EmbeddingRequest
    ): EmbeddingResponse
}
