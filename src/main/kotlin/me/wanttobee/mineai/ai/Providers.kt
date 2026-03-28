package me.wanttobee.mineai.ai

import me.wanttobee.mineai.ai.sessions.AiModel
import me.wanttobee.mineai.ai.providers.AiProvider
import me.wanttobee.mineai.ai.providers.AnthropicProvider
import me.wanttobee.mineai.ai.providers.GoogleAiProvider
import me.wanttobee.mineai.ai.providers.OpenAiProvider

object Providers {
	val all: List<AiProvider> = listOf(
		OpenAiProvider,
		AnthropicProvider,
		GoogleAiProvider,
	)

	fun providerNames(): List<String> {
		return all.map { provider -> provider.name }.sorted()
	}

	fun modelList(providerName: String): List<AiModel> {
		return getProviderByName(providerName)?.models?.sortedBy { model -> model.displayName } ?: emptyList()
	}

	fun resolveModel(providerName: String, modelName: String): AiModel? {
		val provider = getProviderByName(providerName) ?: return null
		return getModel(providerName, modelName) ?: AiModel(modelName, modelName)
	}

	fun reasoningSuggestions(providerName: String, modelName: String): List<AiProvider.ReasoningSuggestion> {
		val provider = getProviderByName(providerName) ?: return emptyList()
		val model = resolveModel(providerName, modelName) ?: return emptyList()
		return provider.reasoningSuggestions(model)
	}

	fun getModel(providerName: String, modelName: String): AiModel? {
		return getProviderByName(providerName)
			?.models
			?.firstOrNull { model ->
				model.apiName.equals(modelName, ignoreCase = true) ||
					model.displaySlug.equals(modelName, ignoreCase = true)
			}
	}

	fun getProviderByName(providerName: String): AiProvider? {
		return all.firstOrNull { provider -> provider.name.equals(providerName, ignoreCase = true) }
	}
}
