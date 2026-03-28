package me.wanttobee.mineai.ai.sessions

data class AiModel(
	val apiName: String,
	val displayName: String,
) {
	val displaySlug: String
		get() = displayName.replace(' ', '-')
}
