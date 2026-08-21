package me.wanttobee.openblock.ai.providers

import me.wanttobee.openblock.ai.AiActionBarManager
import me.wanttobee.openblock.ai.providers.codex.CodexSubscriptionService
import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import net.minecraft.ChatFormatting

object CodexSubscriptionProvider : AiProvider {
	private val reasoningSupport = AiModel.ReasoningSupport.text(
		values = listOf("low", "medium", "high", "xhigh", "max"),
		allowsNone = true,
	)

	override val name = "codex"
	override val displayName = "Codex Subscription"
	override val apiKeyVariable: String? = null
	override val defaultModel = "gpt-5.6-sol"
	override val models = listOf(
		AiModel("gpt-5.6-luna", "GPT-5.6 Luna", reasoningSupport),
		AiModel("gpt-5.6-sol", "GPT-5.6 Sol", reasoningSupport),
		AiModel("gpt-5.6-terra", "GPT-5.6 Terra", reasoningSupport),
	)
	override val chatColor = ChatFormatting.GREEN
	override val progressColorA = 0x10A37F
	override val progressColorB = 0x74AA9C

	override fun ping() {
		CodexSubscriptionService.requireChatGptAccount().getOrThrow()
	}

	override fun applyReasoning(model: AiModel, value: String?): Result<AiModel> {
		val normalized = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return Result.success(model)
		return if (normalized in reasoningSupport.values || normalized == "none") {
			Result.success(model.copy(reasoning = AiModel.Reasoning(value = normalized)))
		} else {
			Result.failure(IllegalArgumentException("Unsupported Codex reasoning effort: $value"))
		}
	}

	override fun reasoningSuggestions(model: AiModel): Result<List<AiProvider.ReasoningSuggestion>> {
		return Result.success((listOf("none") + reasoningSupport.values).map { value ->
			AiProvider.ReasoningSuggestion(value, "Codex reasoning effort: $value")
		})
	}

	override fun describeReasoning(model: AiModel): Result<String> {
		return model.reasoning?.value?.let { value -> Result.success("reasoning $value") }
			?: Result.failure(IllegalStateException("Reasoning is not configured for ${model.displayName}."))
	}

	override fun generate(
		model: AiModel,
		session: Session,
		generationId: Long,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): Result<Boolean> {
		if (session.isGenerationInterrupted(generationId)) return Result.success(false)
		val result = CodexSubscriptionService.generate(
			model = model,
			session = session,
			generationId = generationId,
			enabledTools = enabledTools(session),
			onActionChange = onActionChange,
			onMessageAdded = onMessageAdded,
		).map { outcome ->
			if (outcome.interrupted) {
				session.recordProviderCall(name, model.apiName, outcome.usage, "interrupted")
				false
			} else {
				session.recordProviderCall(name, model.apiName, outcome.usage, "assistant")
				session.addAssistantMessage(outcome.text, outcome.usage, name, model.apiName, generationId)
				true
			}
		}
		result.onFailure { error ->
			session.addErrorMessage(
				content = error.message ?: "Unknown Codex error",
				providerName = name,
				modelName = model.apiName,
				generationId = generationId,
			)
		}
		return result
	}
}
