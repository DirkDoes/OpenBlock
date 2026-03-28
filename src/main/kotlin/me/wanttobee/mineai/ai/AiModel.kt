package me.wanttobee.mineai.ai

data class AiModel(
	val apiName: String,
	val displayName: String,
) {
	val displaySlug: String
		get() = displayName.replace(' ', '-')
}
