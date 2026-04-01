package me.wanttobee.openblock.ai.sessions

import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.ai.providers.AiProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AiTargetManager {
	private val selectedTargets = ConcurrentHashMap<UUID, AiTarget>()
	private val lastSelectedTargetsByPlayer = ConcurrentHashMap<UUID, ConcurrentHashMap<String, AiTarget>>()

	fun currentTarget(playerId: UUID): AiTarget? = selectedTargets[playerId]

	fun selectTarget(playerId: UUID, providerName: String, modelId: String?, reasoningValue: String? = null): AiTarget? {
		val provider = Providers.getProviderByName(providerName) ?: return null
		val playerTargets = lastSelectedTargetsFor(playerId)
		val target = modelId?.trim().takeUnless { it.isNullOrEmpty() }?.let { requestedModel ->
			val baseModel = Providers.resolveModel(providerName, requestedModel) ?: AiModel(requestedModel, requestedModel)
			val selectedModel = provider.resolveReasoning(baseModel, reasoningValue) ?: return null
			AiTarget(provider, selectedModel)
		} ?: playerTargets[provider.name]?.let { existingTarget ->
			if (reasoningValue.isNullOrBlank()) {
				existingTarget
			} else {
				val selectedModel = provider.resolveReasoning(existingTarget.model, reasoningValue) ?: return null
				AiTarget(provider, selectedModel)
			}
		}
			?: defaultTargetFor(provider.name, provider)

		playerTargets[provider.name] = target
		selectedTargets[playerId] = target
		return target
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
		val defaultModel = Providers.getModel(providerName, provider.defaultModel)
		return if (defaultModel != null) {
			AiTarget(provider, provider.resolveReasoning(defaultModel, null) ?: defaultModel)
		} else {
			AiTarget(provider, AiModel(provider.defaultModel, provider.defaultModel))
		}
	}

	data class AiTarget(
		val provider: AiProvider,
		val model: AiModel,
	)
}
