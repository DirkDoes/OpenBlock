package me.wanttobee.openblock.ai.sessions.base

data class SessionTokenUsage(
	val inputTokens: Long? = null,
	val outputTokens: Long? = null,
	val totalTokens: Long? = null,
	val cachedInputTokens: Long? = null,
	val cacheCreationInputTokens: Long? = null,
	val cacheReadInputTokens: Long? = null,
	val reasoningTokens: Long? = null,
	val thoughtsTokens: Long? = null,
	val toolUsePromptTokens: Long? = null,
)
