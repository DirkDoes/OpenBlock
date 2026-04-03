package me.wanttobee.openblock.ai

import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.providers.AiProvider
import me.wanttobee.openblock.ai.providers.AnthropicProvider
import me.wanttobee.openblock.ai.providers.GoogleAiProvider
import me.wanttobee.openblock.ai.providers.OpenAiProvider

object Providers {
	val all: List<AiProvider> = listOf(
		OpenAiProvider,
		AnthropicProvider,
		GoogleAiProvider,
	)

	fun providerNames(): List<String> {
		return all.map { provider -> provider.name }.sorted()
	}

	fun modelList(providerName: String): Result<List<AiModel>> {
		return getProviderByName(providerName).map { provider ->
			provider.models.sortedBy { model -> model.displayName }
		}
	}

	fun resolveModel(providerName: String, modelName: String): Result<AiModel> {
		getProviderByName(providerName).getOrElse { return Result.failure(it) }
		return Result.success(getModel(providerName, modelName).getOrElse { AiModel(modelName, modelName) })
	}

	fun reasoningSuggestions(providerName: String, modelName: String): Result<List<AiProvider.ReasoningSuggestion>> {
		val provider = getProviderByName(providerName).getOrElse { return Result.failure(it) }
		val model = resolveModel(providerName, modelName).getOrElse { return Result.failure(it) }
		return provider.reasoningSuggestions(model)
	}

	fun getModel(providerName: String, modelName: String): Result<AiModel> {
		val provider = getProviderByName(providerName).getOrElse { return Result.failure(it) }
		return provider.models
			.firstOrNull { model ->
				model.apiName.equals(modelName, ignoreCase = true) ||
					model.displaySlug.equals(modelName, ignoreCase = true)
			}
			?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Unknown model '$modelName' for provider '$providerName'."))
	}

	fun getProviderByName(providerName: String): Result<AiProvider> {
		return all.firstOrNull { provider -> provider.name.equals(providerName, ignoreCase = true) }
			?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Unknown AI provider: $providerName"))
	}
}
