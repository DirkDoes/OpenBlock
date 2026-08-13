package me.wanttobee.openblock.ai.toolcalling.base

data class AiToolInvocation(
	val execution: AiToolExecution,
	val conversationMessage: String? = null,
)
