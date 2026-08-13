package me.wanttobee.openblock.ai.sessions.base

data class SessionMessage(
	val type: Type,
	val content: String,
	val hiddenContent: String? = null,
	val usage: SessionTokenUsage? = null,
	val providerName: String? = null,
	val modelName: String? = null,
	val generationDurationMillis: Long? = null,
) {
	fun combinedContent(): String {
		return hiddenContent?.takeIf { it.isNotBlank() }?.let { "$it\n$content" } ?: content
	}

	enum class Type {
		USER,
		TOOL,
		ASSISTANT,
		ERROR,
	}
}
