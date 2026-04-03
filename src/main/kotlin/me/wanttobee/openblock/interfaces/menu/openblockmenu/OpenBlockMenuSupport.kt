package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.ai.providers.AiProvider
import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import net.minecraft.world.item.Items
import net.minecraft.world.level.ItemLike
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.cos

internal object OpenBlockMenuSupport {
	fun providerWool(provider: AiProvider): ItemLike {
		return when (provider.name) {
			"openai" -> Items.LIGHT_BLUE_WOOL
			"claude" -> Items.ORANGE_WOOL
			"google" -> Items.MAGENTA_WOOL
			else -> Items.WHITE_WOOL
		}
	}

	fun currentReasoningValue(playerId: UUID, providerName: String, modelName: String): String? {
		val currentTarget = AiService.currentTarget(playerId).getOrNull()
		if (
			currentTarget != null &&
			currentTarget.provider.name.equals(providerName, ignoreCase = true) &&
			currentTarget.model.apiName.equals(modelName, ignoreCase = true)
		) {
			return currentTarget.model.reasoningKey()
		}

		val provider = Providers.getProviderByName(providerName).getOrNull() ?: return null
		val model = Providers.resolveModel(providerName, modelName).getOrNull() ?: return null
		return provider.resolveReasoning(model, null).getOrNull()?.reasoningKey()
	}

	fun reasoningOptions(provider: AiProvider, model: AiModel): List<ReasoningOption> {
		val suggestions = provider.reasoningSuggestions(model).getOrElse { emptyList() }
		if (suggestions.isEmpty()) {
			return emptyList()
		}

		val noneSuggestion = suggestions.firstOrNull { it.value.equals("none", ignoreCase = true) }
		val positiveSuggestions = suggestions.filterNot { it.value.equals("none", ignoreCase = true) }
		val options = mutableListOf<ReasoningOption>()

		if (noneSuggestion != null) {
			options += ReasoningOption(
				value = noneSuggestion.value,
				label = "Reasoning: none",
				description = noneSuggestion.description,
				item = Items.TINTED_GLASS,
			)
		}

		val positiveItems = reasoningItems(positiveSuggestions.size, noneSuggestion != null)
		for ((index, suggestion) in positiveSuggestions.withIndex()) {
			options += ReasoningOption(
				value = suggestion.value,
				label = "Reasoning: ${reasoningDisplayName(suggestion)}",
				description = suggestion.description,
				item = positiveItems.getOrElse(index) { Items.GOLD_BLOCK },
			)
		}

		return options
	}

	fun reasoningSupportLabel(model: AiModel): String {
		if (!model.reasoningSupport.supportsReasoning()) {
			return "not supported"
		}
		return when (model.reasoningSupport.kind) {
			AiModel.ReasoningSupport.Kind.TEXT -> model.reasoningSupport.values.joinToString(", ")
			AiModel.ReasoningSupport.Kind.NUMBER -> model.reasoningSupport.numericExamples.joinToString(", ")
			AiModel.ReasoningSupport.Kind.UNSUPPORTED -> "not supported"
		}
	}

	fun reasoningLabel(provider: AiProvider?, model: AiModel?): String {
		if (provider == null || model == null) {
			return "none"
		}
		return provider.describeReasoning(model).getOrElse { "not supported" }
	}

	fun sessionLabel(session: SessionSummary): String {
		return "Session ${session.id.toString().take(8)}"
	}

	fun trimPreview(content: String): String {
		val singleLine = content.replace('\n', ' ').trim()
		return if (singleLine.length <= 48) singleLine else singleLine.take(45) + "..."
	}

	fun toolDisplayName(tool: AiTool): String {
		return tool.name
			.split('_')
			.joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) }
	}

	fun animatedProviderColor(provider: AiProvider): Int {
		val radians = (System.currentTimeMillis() % 1600L).toDouble() / 1600.0 * (Math.PI * 2.0)
		val phase = ((1.0 - cos(radians)) / 2.0).toFloat()
		return interpolateColor(provider.progressColorA, provider.progressColorB, phase)
	}

	fun providerStatuses(): Map<String, ProviderStatus> = ProviderPingCache.statuses()

	fun requestProviderPingRefresh() {
		ProviderPingCache.requestRefresh()
	}

	private fun reasoningItems(positiveCount: Int, hasNone: Boolean): List<ItemLike> {
		return if (hasNone) {
			when (positiveCount) {
				4 -> listOf(Items.GILDED_BLACKSTONE, Items.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD_BLOCK, Items.GOLD_BLOCK)
				3 -> listOf(Items.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD_BLOCK, Items.GOLD_BLOCK)
				2 -> listOf(Items.DEEPSLATE_GOLD_ORE, Items.GOLD_BLOCK)
				1 -> listOf(Items.GOLD_BLOCK)
				else -> listOf(Items.GOLD_BLOCK)
			}
		} else {
			when (positiveCount) {
				5 -> listOf(Items.TINTED_GLASS, Items.GILDED_BLACKSTONE, Items.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD_BLOCK, Items.GOLD_BLOCK)
				4 -> listOf(Items.GILDED_BLACKSTONE, Items.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD_BLOCK, Items.GOLD_BLOCK)
				3 -> listOf(Items.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD_BLOCK, Items.GOLD_BLOCK)
				2 -> listOf(Items.DEEPSLATE_GOLD_ORE, Items.GOLD_BLOCK)
				1 -> listOf(Items.GOLD_BLOCK)
				else -> emptyList()
			}
		}
	}

	private fun reasoningDisplayName(suggestion: AiProvider.ReasoningSuggestion): String {
		val description = suggestion.description?.substringAfter(':', suggestion.description).orEmpty().trim()
		return when {
			suggestion.value.equals("none", ignoreCase = true) -> "none"
			description.isNotBlank() && !description.equals("Disable thinking", ignoreCase = true) -> description
			else -> suggestion.value
		}
	}

	private fun interpolateColor(colorA: Int, colorB: Int, t: Float): Int {
		val red = interpolateChannel(colorA shr 16 and 0xFF, colorB shr 16 and 0xFF, t)
		val green = interpolateChannel(colorA shr 8 and 0xFF, colorB shr 8 and 0xFF, t)
		val blue = interpolateChannel(colorA and 0xFF, colorB and 0xFF, t)
		return (red shl 16) or (green shl 8) or blue
	}

	private fun interpolateChannel(a: Int, b: Int, t: Float): Int {
		return (a + ((b - a) * t)).toInt().coerceIn(0, 255)
	}

	private fun AiModel.reasoningKey(): String? {
		val reasoning = reasoning ?: return null
		return reasoning.value ?: reasoning.budgetTokens?.toString()
	}

	private object ProviderPingCache {
		private const val CACHE_MILLIS = 5 * 60 * 1000L
		private val executor = Executors.newSingleThreadExecutor { runnable ->
			Thread(runnable, "openblock-provider-ping").apply {
				isDaemon = true
			}
		}
		private val cachedStatuses = ConcurrentHashMap<String, ProviderStatus>()
		@Volatile private var lastRefreshAt = 0L
		@Volatile private var refreshInFlight = false

		fun statuses(): Map<String, ProviderStatus> = cachedStatuses.toMap()

		fun requestRefresh() {
			val now = System.currentTimeMillis()
			if (refreshInFlight || (cachedStatuses.isNotEmpty() && now - lastRefreshAt < CACHE_MILLIS)) {
				return
			}

			refreshInFlight = true
			executor.submit {
				try {
					val statuses = AiService.pingProviders().mapIndexed { index, result ->
						val provider = result.getOrNull() ?: Providers.all[index]
						provider.name to ProviderStatus(reachable = result.isSuccess)
					}.toMap()
					cachedStatuses.clear()
					cachedStatuses.putAll(statuses)
					lastRefreshAt = System.currentTimeMillis()
				} finally {
					refreshInFlight = false
				}
			}
		}
	}
}

internal data class ProviderStatus(
	val reachable: Boolean,
)

internal data class ReasoningOption(
	val value: String,
	val label: String,
	val description: String?,
	val item: ItemLike,
)
