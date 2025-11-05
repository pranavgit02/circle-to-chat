package com.example.geminichat.llm

data class Stat(
    val id: String,
    val label: String,
    val unit: String,
)

data class ChatMessageBenchmarkLlmResult(
    val orderedStats: List<Stat>,
    val statValues: MutableMap<String, Float> = mutableMapOf(),
    val running: Boolean,
    val latencyMs: Float,
    val accelerator: String?,
) {
    val timeToFirstToken: Float
        get() = statValues["time_to_first_token"] ?: 0f

    val prefillSpeed: Float
        get() = statValues["prefill_speed"] ?: 0f

    val decodeSpeed: Float
        get() = statValues["decode_speed"] ?: 0f

    val latency: Float
        get() = statValues["latency"] ?: 0f
}
