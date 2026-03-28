package me.wanttobee.mineai.ai.providers

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.sessions.AiModel
import me.wanttobee.mineai.util.EnvironmentVariables
import net.minecraft.ChatFormatting
import java.util.stream.Collectors

object OpenAiProvider : AiProvider {
	private val gptFiveReasoningSupport = AiModel.ReasoningSupport.text(
		values = listOf("minimal", "low", "medium", "high", "xhigh"),
		allowsNone = false,
	)
	private val gptFiveOneReasoningSupport = AiModel.ReasoningSupport.text(
		values = listOf("low", "medium", "high"),
		allowsNone = true,
	)
	private val gptFiveTwoReasoningSupport = AiModel.ReasoningSupport.text(
		values = listOf("low", "medium", "high", "xhigh"),
		allowsNone = true,
	)
	private val gptFiveFourReasoningSupport = AiModel.ReasoningSupport.text(
		values = listOf("low", "medium", "high", "xhigh"),
		allowsNone = true,
	)
	private val gptFiveFourProReasoningSupport = AiModel.ReasoningSupport.text(
		values = listOf("medium", "high", "xhigh"),
		allowsNone = false,
	)
	private val oSeriesReasoningSupport = AiModel.ReasoningSupport.text(
		values = listOf("low", "medium", "high"),
		allowsNone = false,
	)
	private val nonReasoningSupport = AiModel.ReasoningSupport.unsupported()

	override val name = "openai"
	override val displayName = "OpenAI"
	override val apiKeyVariable = "OPENAI_API_KEY"
	override val modelVariable = "OPENAI_MODEL"
	override val defaultModel = "gpt-5-nano-2025-08-07"
	override val models = listOf(
		AiModel("gpt-5.4-mini", "GPT-5.4 Mini", reasoningSupport = gptFiveFourReasoningSupport),
		AiModel("gpt-5.4-pro", "GPT-5.4 Pro", reasoningSupport = gptFiveFourProReasoningSupport),
		AiModel("gpt-5.4-nano", "GPT-5.4 Nano", reasoningSupport = gptFiveFourReasoningSupport),
		AiModel("gpt-5-nano-2025-08-07", "GPT-5 Nano", reasoningSupport = gptFiveReasoningSupport),
		AiModel("gpt-5-mini", "GPT-5 Mini", reasoningSupport = gptFiveReasoningSupport),
		AiModel("gpt-4.1", "GPT-4.1", reasoningSupport = nonReasoningSupport),
		AiModel("gpt-5-2025-08-07", "GPT-5", reasoningSupport = gptFiveReasoningSupport),
		AiModel("gpt-5-codex", "GPT-5 Codex", reasoningSupport = gptFiveReasoningSupport),
		AiModel("gpt-5.3-codex", "GPT-5.3 Codex", reasoningSupport = gptFiveTwoReasoningSupport),
		AiModel("gpt-5.2-codex", "GPT-5.2 Codex", reasoningSupport = gptFiveTwoReasoningSupport),
		AiModel("gpt-5.1-codex-mini", "GPT-5.1 Codex Mini", reasoningSupport = gptFiveOneReasoningSupport),
		AiModel("gpt-5.2-2025-12-11", "GPT-5.2", reasoningSupport = gptFiveTwoReasoningSupport),
		AiModel("gpt-5.1-2025-11-13", "GPT-5.1", reasoningSupport = gptFiveOneReasoningSupport),
		AiModel("o3-2025-04-16", "o3", reasoningSupport = oSeriesReasoningSupport),
		AiModel("o4-mini-2025-04-16", "o4-mini", reasoningSupport = oSeriesReasoningSupport),
	)
	override val chatColor = ChatFormatting.AQUA
	override val progressColorA = 0x0084ff
	override val progressColorB = 0x7FFF00

	override fun ping() {
		withClient { client ->
			client.models().retrieve(defaultModel)
		}
	}

	override fun applyReasoning(model: AiModel, value: String?): AiModel? {
		val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return model
		val reasoning = when (normalized) {
			"on" -> AiModel.Reasoning(
				value = model.reasoningSupport.values.find { it == "medium" }
					?: model.reasoningSupport.values.firstOrNull()
					?: "medium"
			)
			"off", "none" -> AiModel.Reasoning(value = "none")
			else -> AiModel.Reasoning(value = normalized)
		}
		return model.copy(reasoning = reasoning)
	}

	override fun reasoningSuggestions(model: AiModel): List<AiProvider.ReasoningSuggestion> {
		val support = model.reasoningSupport
		if (!support.supportsReasoning()) {
			return emptyList()
		}
		val suggestions = support.values.map { value ->
			AiProvider.ReasoningSuggestion(value, "Reasoning effort: $value")
		}.toMutableList()
		if (support.allowsNone) {
			suggestions += AiProvider.ReasoningSuggestion("none", "Disable reasoning")
		}
		return suggestions
	}

	override fun describeReasoning(model: AiModel): String? {
		val reasoning = model.reasoning ?: return null
		if (!reasoning.isEnabled()) {
			return "reasoning off"
		}
		return reasoning.value?.let { "reasoning $it" } ?: "reasoning on"
	}

	override fun generate(model: AiModel, session: Session, onActionChange: (String) -> Unit): Boolean {
		return try {
			val responseText = withClient { client ->
				val params = ResponseCreateParams.builder()
					.model(model.apiName)
					.inputOfResponse(toInputItems(session))

				session.effectiveSystemPrompt()?.let(params::instructions)
				applyReasoning(model, params)
				streamResponse(client, params.build(), model, onActionChange)
			}

			session.addAssistantMessage(responseText)
			true
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			false
		}
	}

	private fun streamResponse(
		client: com.openai.client.OpenAIClient,
		params: ResponseCreateParams,
		model: AiModel,
		onActionChange: (String) -> Unit,
	): String {
		val response = client.responses().createStreaming(params)
		val text = StringBuilder()
		var generating = !model.usesReasoning()

		try {
			response.stream().forEach { event ->
				when {
					event.isOutputTextDelta() -> {
						if (!generating) {
							onActionChange("generating")
							generating = true
						}
						text.append(event.asOutputTextDelta().delta())
					}
					event.isReasoningTextDelta() -> Unit
					event.isReasoningSummaryTextDelta() -> Unit
				}
			}
		} finally {
			response.close()
		}

		return text.toString().ifBlank { "OpenAI returned an empty response." }
	}

	private fun applyReasoning(model: AiModel, params: ResponseCreateParams.Builder) {
		val config = model.reasoning ?: return
		if (!config.isEnabled()) {
			params.reasoning(
				Reasoning.builder()
					.effort(ReasoningEffort.NONE)
					.build()
			)
			return
		}

		val effort = config.value ?: return
		params.reasoning(
			Reasoning.builder()
				.effort(ReasoningEffort.of(effort.lowercase()))
				.build()
		)
	}

	private fun client() = OpenAIOkHttpClient.builder()
		.apiKey(requiredApiKey())
		.build()

	private fun requiredApiKey(): String {
		return EnvironmentVariables.get(apiKeyVariable)?.takeIf { it.isNotBlank() }
			?: error("Missing $apiKeyVariable in ${EnvironmentVariables.MINEAI_FILE_NAME} or ${EnvironmentVariables.DOTENV_FILE_NAME}.")
	}

	private fun <T> withClient(block: (com.openai.client.OpenAIClient) -> T): T {
		val client = client()
		try {
			return block(client)
		} finally {
			client.close()
		}
	}

	private fun toInputItems(session: Session): List<ResponseInputItem> {
		return session.messages().mapNotNull { message ->
			when (message.type) {
				Session.Message.Type.USER -> ResponseInputItem.ofEasyInputMessage(
					EasyInputMessage.builder()
						.role(EasyInputMessage.Role.USER)
						.content(message.combinedContent())
						.build()
				)
				Session.Message.Type.ASSISTANT -> ResponseInputItem.ofEasyInputMessage(
					EasyInputMessage.builder()
						.role(EasyInputMessage.Role.ASSISTANT)
						.content(message.combinedContent())
						.phase(EasyInputMessage.Phase.FINAL_ANSWER)
						.build()
				)
				Session.Message.Type.ERROR -> null
			}
		}
	}
}
