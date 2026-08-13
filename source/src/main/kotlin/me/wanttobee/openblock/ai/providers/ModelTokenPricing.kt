package me.wanttobee.openblock.ai.providers

import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage

data class ModelTokenPricing(
	val tiers: List<Tier>,
) {
	fun estimateCost(usages: List<SessionTokenUsage>): Double {
		if (usages.isEmpty()) {
			return 0.0
		}

		return usages
			.groupBy(::tierForUsage)
			.entries
			.sumOf { (tier, groupedUsages) ->
				estimateCost(sumUsage(groupedUsages), tier)
			}
	}

	fun estimateCost(usage: SessionTokenUsage): Double {
		return estimateCost(listOf(usage))
	}

	private fun estimateCost(usage: SessionTokenUsage, tier: Tier): Double {
		val inputTokens = usage.inputTokens ?: 0L
		val outputTokens = usage.outputTokens ?: 0L
		val cachedInputTokens = usage.cachedInputTokens ?: 0L
		val reasoningTokens = usage.reasoningTokens ?: 0L
		val billableInputTokens = (inputTokens - cachedInputTokens).coerceAtLeast(0L)
		val billableOutputTokens = (outputTokens - reasoningTokens).coerceAtLeast(0L)
		return (
			billableInputTokens * tier.inputUsdPerMillionTokens +
				billableOutputTokens * tier.outputUsdPerMillionTokens +
				cachedInputTokens * tier.cachedInputUsdPerMillionTokens() +
				reasoningTokens * tier.reasoningUsdPerMillionTokens()
			) / 1_000_000.0
	}

	private fun tierForUsage(usage: SessionTokenUsage): Tier {
		val inputTokens = usage.inputTokens ?: 0L
		return tiers
			.sortedBy { tier -> tier.inputTokensThreshold ?: Long.MIN_VALUE }
			.lastOrNull { tier -> inputTokens >= (tier.inputTokensThreshold ?: Long.MIN_VALUE) }
			?: tiers.first()
	}

	private fun sumUsage(usages: List<SessionTokenUsage>): SessionTokenUsage {
		return SessionTokenUsage(
			inputTokens = usages.sumNullable(SessionTokenUsage::inputTokens),
			outputTokens = usages.sumNullable(SessionTokenUsage::outputTokens),
			totalTokens = usages.sumNullable(SessionTokenUsage::totalTokens),
			cachedInputTokens = usages.sumNullable(SessionTokenUsage::cachedInputTokens),
			reasoningTokens = usages.sumNullable(SessionTokenUsage::reasoningTokens),
		)
	}

	private fun List<SessionTokenUsage>.sumNullable(selector: (SessionTokenUsage) -> Long?): Long? {
		val values = mapNotNull(selector)
		return values.sum().takeIf { values.isNotEmpty() }
	}

	data class Tier(
		val inputUsdPerMillionTokens: Double,
		val outputUsdPerMillionTokens: Double,
		val cachedInputUsdPerMillionTokens: Double? = null,
		val reasoningUsdPerMillionTokens: Double? = null,
		val inputTokensThreshold: Long? = null,
		val cacheWrite5mUsdPerMillionTokens: Double? = null,
		val cacheWrite1hUsdPerMillionTokens: Double? = null,
	)

	private fun Tier.cachedInputUsdPerMillionTokens(): Double {
		return cachedInputUsdPerMillionTokens ?: inputUsdPerMillionTokens
	}

	private fun Tier.reasoningUsdPerMillionTokens(): Double {
		return reasoningUsdPerMillionTokens ?: outputUsdPerMillionTokens
	}
}
