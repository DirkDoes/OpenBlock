package me.wanttobee.mineai.ai.providers

import me.wanttobee.mineai.ai.sessions.AiModel
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.tools.AiTool
import me.wanttobee.mineai.ai.tools.ToolManager
import net.minecraft.ChatFormatting

interface AiProvider {
	val name: String
	val displayName: String
	val apiKeyVariable: String
	val modelVariable: String
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

	fun generate(model: AiModel, session: Session, onActionChange: (String) -> Unit = {}): Boolean

	data class ReasoningSuggestion(
		val value: String,
		val description: String? = null,
	)

	companion object {
		const val MAX_TOOL_CALLS = 50
	}
}
