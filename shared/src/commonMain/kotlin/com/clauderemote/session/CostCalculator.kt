package com.clauderemote.session

import com.clauderemote.model.ClaudeModel

/**
 * Estimates API cost from token counts per model.
 * Prices in USD per million tokens (as of 2025).
 */
object CostCalculator {

    data class ModelPricing(
        val inputPerMillion: Double,
        val outputPerMillion: Double,
        val cacheWritePerMillion: Double,
        val cacheReadPerMillion: Double
    )

    private val PRICING = mapOf(
        "opus" to ModelPricing(15.0, 75.0, 18.75, 1.875),
        "sonnet" to ModelPricing(3.0, 15.0, 3.75, 0.375),
        "haiku" to ModelPricing(0.80, 4.0, 1.0, 0.1)
    )

    data class UsageTokens(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheCreationTokens: Long = 0,
        val cacheReadTokens: Long = 0,
        val model: String = "sonnet"
    )

    fun estimateCost(usage: UsageTokens): Double {
        val pricing = PRICING[usage.model.lowercase()] ?: PRICING["sonnet"]!!
        return (usage.inputTokens * pricing.inputPerMillion +
                usage.outputTokens * pricing.outputPerMillion +
                usage.cacheCreationTokens * pricing.cacheWritePerMillion +
                usage.cacheReadTokens * pricing.cacheReadPerMillion) / 1_000_000.0
    }

    fun formatCost(cost: Double): String = when {
        cost < 0.01 -> "<$0.01"
        cost < 1.0 -> "$${String.format("%.2f", cost)}"
        else -> "$${String.format("%.2f", cost)}"
    }

    fun modelFromClaudeModel(model: ClaudeModel): String = when (model) {
        ClaudeModel.OPUS -> "opus"
        ClaudeModel.SONNET -> "sonnet"
        ClaudeModel.HAIKU -> "haiku"
        ClaudeModel.DEFAULT -> "sonnet"
    }
}
