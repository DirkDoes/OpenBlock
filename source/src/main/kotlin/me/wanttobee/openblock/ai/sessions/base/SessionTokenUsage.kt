package me.wanttobee.openblock.ai.sessions.base

data class SessionTokenUsage(
	val inputTokens: Long? = null,
	val outputTokens: Long? = null,
	val totalTokens: Long? = null,
	val cachedInputTokens: Long? = null,
	val reasoningTokens: Long? = null,
)
