package me.wanttobee.openblock.ai.sessions

import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.ai.providers.AiProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AiTargetManager {
	private val selectedTargets = ConcurrentHashMap<UUID, AiTarget>()
	private val lastSelectedTargetsByPlayer = ConcurrentHashMap<UUID, ConcurrentHashMap<String, AiTarget>>()

	fun currentTarget(playerId: UUID): Result<AiTarget> {
		return selectedTargets[playerId]?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("No AI model selected."))
	}

	fun selectTarget(playerId: UUID, providerName: String, modelId: String?, reasoningValue: String? = null): Result<AiTarget> {
		val provider = Providers.getProviderByName(providerName).getOrElse { return Result.failure(it) }
		val playerTargets = lastSelectedTargetsFor(playerId)
		val target = modelId?.trim().takeUnless { it.isNullOrEmpty() }?.let { requestedModel ->
			val baseModel = Providers.resolveModel(providerName, requestedModel).getOrElse { AiModel(requestedModel, requestedModel) }
			val selectedModel = provider.resolveReasoning(baseModel, reasoningValue).getOrElse { return Result.failure(it) }
			AiTarget(provider, selectedModel)
		} ?: playerTargets[provider.name]?.let { existingTarget ->
			if (reasoningValue.isNullOrBlank()) {
				existingTarget
			} else {
				val selectedModel = provider.resolveReasoning(existingTarget.model, reasoningValue).getOrElse { return Result.failure(it) }
				AiTarget(provider, selectedModel)
			}
		}
			?: defaultTargetFor(provider.name, provider)

		playerTargets[provider.name] = target
		selectedTargets[playerId] = target
		return Result.success(target)
	}

	private fun lastSelectedTargetsFor(playerId: UUID): ConcurrentHashMap<String, AiTarget> {
		return lastSelectedTargetsByPlayer.computeIfAbsent(playerId) {
			ConcurrentHashMap<String, AiTarget>().apply {
				for (provider in Providers.all) {
					this[provider.name] = defaultTargetFor(provider.name, provider)
				}
			}
		}
	}

	private fun defaultTargetFor(providerName: String, provider: AiProvider): AiTarget {
		val defaultModel = Providers.getModel(providerName, provider.defaultModel).getOrNull()
		return if (defaultModel != null) {
			AiTarget(provider, provider.resolveReasoning(defaultModel, null).getOrElse { defaultModel })
		} else {
			AiTarget(provider, AiModel(provider.defaultModel, provider.defaultModel))
		}
	}

	data class AiTarget(
		val provider: AiProvider,
		val model: AiModel,
	)
}
