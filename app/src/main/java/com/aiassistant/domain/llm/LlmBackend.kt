package com.aiassistant.domain.llm

/**
 * Compute backend for on-device inference.
 *
 * Choosing a backend is not enough on its own: bundles are built per backend. litert-community
 * publishes a separate `-gpu` bundle for gemma-4-E2B, and `Backend.GPU()` against a CPU-built
 * bundle is what an earlier attempt at this got wrong.
 */
enum class LlmBackend {
    CPU,
    GPU,

    /** Qualcomm/other vendor NPU. Needs a bundle built for the specific SoC. */
    NPU,

    /** Pixel Tensor. Needs a `_Google_Tensor_*` bundle. */
    GOOGLE_TENSOR;

    companion object {
        fun fromName(name: String?): LlmBackend =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CPU
    }
}
