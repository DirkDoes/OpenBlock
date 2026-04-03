package me.wanttobee.openblock.ai.providers

import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.toolcalling.AiTool
import me.wanttobee.openblock.ai.toolcalling.ToolManager
import net.minecraft.ChatFormatting

interface AiProvider {
	val name: String
	val displayName: String
	val apiKeyVariable: String
	val defaultModel: String
	val models: List<AiModel>
	val chatColor: ChatFormatting
	val progressColorA: Int
	val progressColorB: Int

	fun ping()

	fun startingAction(model: AiModel): String {
		return if (model.usesReasoning()) "thinking" else "generating"
	}

	fun applyReasoning(model: AiModel, value: String?): AiModel? {
		return model
	}

	fun resolveReasoning(model: AiModel, value: String?): AiModel? {
		val requestedValue = value?.trim()?.takeIf { it.isNotEmpty() } ?: defaultReasoningValue(model)
		if (requestedValue == null) {
			return model
		}
		return applyReasoning(model, requestedValue)
	}

	fun defaultReasoningValue(model: AiModel): String? {
		val support = model.reasoningSupport
		if (!support.supportsReasoning()) {
			return null
		}

		return when (support.kind) {
			AiModel.ReasoningSupport.Kind.TEXT -> support.values.middleOrNull()
			AiModel.ReasoningSupport.Kind.NUMBER -> support.numericExamples.middleOrNull()?.toString()
			AiModel.ReasoningSupport.Kind.UNSUPPORTED -> null
		}
	}

	fun reasoningSuggestions(model: AiModel): List<ReasoningSuggestion> {
		return emptyList()
	}

	fun describeReasoning(model: AiModel): String? {
		return null
	}

	fun enabledTools(session: Session): List<AiTool> {
		return ToolManager.enabledTools(session.boundPlayerId)
	}

	fun missingToolResult(toolName: String): AiTool.ExecutionResult {
		return AiTool.ExecutionResult(
			payload = mapOf("message" to "Unknown tool: $toolName"),
			isError = true,
		)
	}

	fun toolCallLimitReachedMessage(): String {
		return "$displayName exceeded the tool call limit."
	}

	fun generate(
		model: AiModel,
		session: Session,
		onActionChange: (String) -> Unit = {},
		onMessageAdded: (Session.Message) -> Unit = {},
	): Boolean

	data class ReasoningSuggestion(
		val value: String,
		val description: String? = null,
	)

	companion object {
		const val MAX_TOOL_CALLS = 50
	}
}

private fun <T> List<T>.middleOrNull(): T? {
	if (isEmpty()) {
		return null
	}
	return this[size / 2]
}
