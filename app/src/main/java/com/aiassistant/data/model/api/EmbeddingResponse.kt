package com.aiassistant.data.model.api

import com.google.gson.annotations.SerializedName

data class EmbeddingRequest(
    val model: String,
    val input: String
)

data class EmbeddingResponse(
    val data: List<EmbeddingData>,
    val usage: Usage
)

data class EmbeddingData(
    val embedding: List<Float>
)
