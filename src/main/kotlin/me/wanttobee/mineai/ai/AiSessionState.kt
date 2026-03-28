package me.wanttobee.mineai.ai

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AiSessionState {
	private val selectedTargets = ConcurrentHashMap<UUID, AiTarget>()
	private val lastSelectedTargets = ConcurrentHashMap<String, AiTarget>().apply {
		for (provider in Providers.all) {
			this[provider.name] = defaultTargetFor(provider.name, provider)
		}
	}

	fun currentTarget(playerId: UUID): AiTarget? = selectedTargets[playerId]

	fun selectTarget(playerId: UUID, providerName: String, modelId: String?): AiTarget? {
		val provider = Providers.getProviderByName(providerName) ?: return null
		val target = modelId?.trim().takeUnless { it.isNullOrEmpty() }?.let { requestedModel ->
			val knownModel = Providers.getModel(providerName, requestedModel)
			if (knownModel != null) {
				AiTarget(provider, knownModel.apiName, knownModel.displayName)
			} else {
				AiTarget(provider, requestedModel, requestedModel)
			}
		} ?: lastSelectedTargets[provider.name]
			?: defaultTargetFor(provider.name, provider)

		lastSelectedTargets[provider.name] = target
		selectedTargets[playerId] = target
		return target
	}

	private fun defaultTargetFor(providerName: String, provider: me.wanttobee.mineai.ai.providers.AiProvider): AiTarget {
		val defaultModel = Providers.getModel(providerName, provider.defaultModel)
		return if (defaultModel != null) {
			AiTarget(provider, defaultModel.apiName, defaultModel.displayName)
		} else {
			AiTarget(provider, provider.defaultModel, provider.defaultModel)
		}
	}
}
