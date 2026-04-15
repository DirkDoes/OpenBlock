package me.wanttobee.openblock.ai.providers

import me.wanttobee.openblock.ai.AiActionBarManager
import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.ToolManager
import me.wanttobee.openblock.util.middleOrNull
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

	fun pricing(modelName: String): Result<ModelTokenPricing> {
		return Result.failure(NoSuchElementException("$displayName has no pricing entry for $modelName."))
	}

	fun estimateCost(modelName: String, usage: SessionTokenUsage): Result<Double> {
		return estimateCost(modelName, listOf(usage))
	}

	fun estimateCost(modelName: String, usages: List<SessionTokenUsage>): Result<Double> {
		return pricing(modelName).map { pricing ->
			pricing.estimateCost(usages)
		}
	}

	fun startingAction(model: AiModel): String {
		return if (model.usesReasoning()) "thinking" else "generating"
	}

	fun applyReasoning(model: AiModel, value: String?): Result<AiModel> {
		return Result.success(model)
	}

	fun resolveReasoning(model: AiModel, value: String?): Result<AiModel> {
		val requestedValue = value?.trim()?.takeIf { it.isNotEmpty() } ?: defaultReasoningValue(model)
		if (requestedValue == null) {
			return Result.success(model)
		}
		return applyReasoning(model, requestedValue)
	}

	// returning null means reasoning is un-supported
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

	fun reasoningSuggestions(model: AiModel): Result<List<ReasoningSuggestion>> {
		return Result.success(emptyList())
	}

	fun describeReasoning(model: AiModel): Result<String> {
		return Result.failure(
			UnsupportedOperationException("$displayName does not support reasoning for ${model.displayName}.")
		)
	}

	fun enabledTools(session: Session): List<AiTool> {
		return ToolManager.enabledTools(session)
	}

	fun toolBatchActionLabel(toolNames: List<String>): String {
		return when (toolNames.size) {
			0 -> "using tools"
			1 -> "using ${toolNames.first()}"
			2 -> "using ${toolNames[0]} + ${toolNames[1]}"
			else -> "using ${toolNames.first()} + ${toolNames.size - 1} more"
		}
	}

	fun hasToolError(outcome: ToolManager.ToolCallOutcome): Boolean {
		return outcome.invocation.fold(
			onSuccess = { invocation -> invocation.execution.isError },
			onFailure = { true },
		)
	}

	fun missingToolResult(toolName: String): AiToolExecution {
		return AiToolExecution(
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
		generationId: Long,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit = { _, _ -> },
		onMessageAdded: (SessionMessage) -> Unit = {},
	): Result<Boolean>

	data class ReasoningSuggestion(
		val value: String,
		val description: String? = null,
	)

	companion object {
		const val MAX_TOOL_CALLS = 50
	}
}
