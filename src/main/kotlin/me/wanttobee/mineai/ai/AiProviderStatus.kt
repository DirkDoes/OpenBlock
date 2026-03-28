package me.wanttobee.mineai.ai

import me.wanttobee.mineai.ai.providers.AiProvider

data class AiProviderStatus(
	val provider: AiProvider,
	val isReady: Boolean,
	val message: String,
)
