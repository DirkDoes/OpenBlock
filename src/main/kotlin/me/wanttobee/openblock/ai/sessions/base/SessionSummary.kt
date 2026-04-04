package me.wanttobee.openblock.ai.sessions.base

import java.util.UUID

data class SessionSummary(
	val id: UUID,
	val ownerPlayerId: UUID,
	val boundPlayerId: UUID?,
	val systemPrompt: String?,
	val userMessageCount: Int,
	val lastResponseProviderName: String?,
)
