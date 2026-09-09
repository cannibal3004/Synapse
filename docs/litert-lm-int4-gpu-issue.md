# int4 bundles decode invalid logits on the GPU backend (sampled token cast to 0); executor stays poisoned across Conversation recreation

## Summary

On the GPU backend, int4-quantized `.litertlm` bundles produce invalid logits from the **first
decode step**. The runtime detects this and casts the sampled token to 0, logging once per step:

```
llm_litert_compiled_model_executor.cc:1190] Invalid decode and sample result.
The sampled token is casted to 0 to avoid crash.
```

Because token 0 is `<|start_of_sentence|>` in this model's tokenizer, the user-visible output is
that marker repeated until the output limit is reached. Decode throughput looks *normal*
(14.9 tok/s for 2048 tokens) — every token is simply invalid.

The same bundles decode correctly on **CPU**, and an **int8** bundle of the same model decodes
correctly on the **same GPU**, which isolates this to the int4 GPU decode path.

Secondly, once it occurs the failure persists for the lifetime of the `Engine`: creating a new
`Conversation` does not clear it, and every subsequent turn fails the same way until the process
is restarted.

## Environment

| | |
|---|---|
| litert-lm | 0.17.0 (`com.google.ai.edge.litertlm:litertlm-android:0.17.0`) |
| Device | Samsung Galaxy S24+ (SM-S926U), Snapdragon 8 Gen 3 |
| GPU | Adreno, OpenCL via ML Drift (`Created OpenCL device from provided device id and platform id`) |
| OS | Android 16 |
| Model | Spark-X2.5-1.7B (28 layers, 2 kv-heads, head_dim 256) |
| Quantization | int4 blockwise-32, externalized embedding table |

## Reproduction

1. Load an int4 bundle with `EngineConfig(backend = Backend.GPU(), maxNumTokens = 4096, cacheDir = ...)`.
2. Create a conversation and send any message that leads to a second prefill (in our case a tool
   round: the model emits a tool call, the result is appended as
   `Message.tool(Contents.of(Content.ToolResponse(...)))`, and generation continues).
3. From the first decode step of that turn onward, every sampled token is invalid.

Not every turn triggers it. The first turn after process start frequently succeeds; the failure
becomes reliable after a longer generation. Once triggered it never recovers within the process.

## What we ruled out

**Not context pressure.** Reproduced with a token count of 1523 before the tool result and one
~500-token result, i.e. roughly 2023 tokens in a 4096-token context, with the output limit set to
2048. Nothing was near the KV limit, and no `FAILED_PRECONDITION` prefill error was raised.

**Not the conversion.** Two independently produced int4 bundles fail identically:

- `litert-community/Spark-X2.5-1.7B` (published int4)
- our own conversion via `hf-to-litertlm` with different quantizer settings, exported at
  `CACHE=4096` and again at `CACHE=16384`

**Not the chat template.** The same bundles and the same template run correctly on CPU, completing
multi-round tool calls.

**Not quantization in general.** The int8 bundle of the same model runs correctly on the same GPU
(11.7 tok/s decode, 111 tok/s prefill), with tool calling.

| bundle | backend | result |
|---|---|---|
| litert-community int4 | GPU | invalid logits, every step |
| own int4 (`CACHE=4096`) | GPU | invalid logits, every step |
| own int4 (`CACHE=16384`) | GPU | invalid logits, every step |
| all of the above | CPU | correct; multi-round tool calls complete |
| Spark int8 | GPU | correct |

## Logs

Successful prefill, then invalid decode for the whole turn:

```
D  Loading .../Spark-X2.5-1.7B_int4.litertlm backend=GPU contextTokens=4096
I  [gpu_environment.cc:220] Created OpenCL device from provided device id and platform id.
I  [gpu_registry.cc:109] Statically linked GPU accelerator registered.
D  Round 0: 1 tool call(s)
D  Tool budget: used=1523 ceiling=2048 -> 1500 chars x 1 call(s)
W  [llm_litert_compiled_model_executor.cc:1190] Invalid decode and sample result.
   The sampled token is casted to 0 to avoid crash.
   ... (repeats once per decode step)
D  Generation: 14.9 tok/s · 2048 tokens · 2.3s to first · prefill 433 tok/s
```

Note `prefill 433 tok/s` and a healthy decode rate: the pipeline is running at full speed and
producing invalid results, rather than stalling.

## Second issue: the failure is engine-scoped and unrecoverable

`llm_litert_compiled_model_executor` belongs to the `Engine`, not the `Conversation`. After the
first occurrence:

- `conversation.close()` followed by `engine.createConversation(...)` does **not** clear it
- every subsequent turn in that process fails identically, including short prompts with no tools
- only tearing down the `Engine` (in practice, restarting the process) restores correct behaviour

From an integrator's point of view this is the more damaging half: there is no API-level signal
that the engine is unusable, and no documented way to recover short of rebuilding it. The
`BenchmarkInfo` decode count is *not* a usable signal, since the failing turn reports a normal
count at a normal rate.

Two things that would help regardless of the root cause:

1. Surface the invalid-decode condition through the API (a terminal error on the response flow, or
   a queryable engine health flag), instead of only a native log line.
2. Reset or invalidate the executor state when the condition is detected, so a new `Conversation`
   starts clean.

## Related: `use_ringbuffers_local_attention` is not reachable from Kotlin

Separate and much smaller. `AbstractEngine` in `python/litert_lm/interfaces.py` and the CLI's
`--ringbuffers-local-attention` expose this, and `GpuArtisanConfig` has
`use_autosized_ringbuffers`, but the Kotlin `EngineConfig` has no equivalent field and
`Engine.initialize()` passes a fixed JNI argument list without one.

It matters for memory on Android specifically. This model has a 512-token sliding window on 21 of
its 28 layers, but the export allocates a full-length fp32 cache for every layer — measured at
112 KiB/token, so 7.5 GB at a 65536 context, which the kernel OOM-kills on a 12 GB phone. With
ringbuffers only the 7 full-attention layers would scale, cutting that roughly 4x. Exposing the
flag (and `activation_data_type`) in the Android binding would make long contexts viable on device.
