package me.wanttobee.mineai.ai

import me.wanttobee.mineai.ai.providers.AiProvider

data class AiTarget(
	val provider: AiProvider,
	val model: AiModel,
)
