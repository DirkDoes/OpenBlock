package me.wanttobee.mineai.ai

import me.wanttobee.mineai.ai.providers.AiProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AiTargetManager {
	private val selectedTargets = ConcurrentHashMap<UUID, AiTarget>()
	private val lastSelectedTargetsByPlayer = ConcurrentHashMap<UUID, ConcurrentHashMap<String, AiTarget>>()

	fun currentTarget(playerId: UUID): AiTarget? = selectedTargets[playerId]

	fun selectTarget(playerId: UUID, providerName: String, modelId: String?): AiTarget? {
		val provider = Providers.getProviderByName(providerName) ?: return null
		val playerTargets = lastSelectedTargetsFor(playerId)
		val target = modelId?.trim().takeUnless { it.isNullOrEmpty() }?.let { requestedModel ->
			val knownModel = Providers.getModel(providerName, requestedModel)
			if (knownModel != null) {
				AiTarget(provider, knownModel)
			} else {
				AiTarget(provider, AiModel(requestedModel, requestedModel))
			}
		} ?: playerTargets[provider.name]
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
			AiTarget(provider, defaultModel)
		} else {
			AiTarget(provider, AiModel(provider.defaultModel, provider.defaultModel))
		}
	}

	data class AiTarget(
		val provider: AiProvider,
		val model: AiModel,
	)
}
