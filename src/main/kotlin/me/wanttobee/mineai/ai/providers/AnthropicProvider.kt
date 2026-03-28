package me.wanttobee.mineai.ai.providers

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.ThinkingConfigEnabled
import com.anthropic.models.messages.ThinkingConfigParam
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.sessions.AiModel
import me.wanttobee.mineai.util.EnvironmentVariables
import net.minecraft.ChatFormatting
object AnthropicProvider : AiProvider {
	private const val DEFAULT_MAX_TOKENS = 2048L
	private const val MIN_RESPONSE_BUFFER_TOKENS = 512L
	private val thinkingSupport = AiModel.ReasoningSupport.number(
		numericExamples = listOf(1024, 4096, 8192),
		allowsNone = true,
	)

	override val name = "claude"
	override val displayName = "Claude"
	override val apiKeyVariable = "ANTHROPIC_API_KEY"
	override val modelVariable = "ANTHROPIC_MODEL"
	override val defaultModel = "claude-haiku-4-5"
	override val models = listOf(
		AiModel("claude-haiku-4-5", "Haiku 4.5"),
		AiModel("claude-sonnet-4-6", "Sonnet 4.6", reasoningSupport = thinkingSupport),
		AiModel("claude-opus-4-6", "Opus 4.6", reasoningSupport = thinkingSupport),
		AiModel("claude-opus-4-5-20251101", "Opus 4.5", reasoningSupport = thinkingSupport),
		AiModel("claude-sonnet-4-5-20250929", "Sonnet 4.5", reasoningSupport = thinkingSupport),
		AiModel("claude-sonnet-4-20250514", "Sonnet 4", reasoningSupport = thinkingSupport),
	)
	override val chatColor = ChatFormatting.GOLD
	override val progressColorA = 0xFF3B30
	override val progressColorB = 0xFFD400

	override fun ping() {
		withClient { client ->
			client.models().retrieve(defaultModel)
		}
	}

	override fun applyReasoning(model: AiModel, value: String?): AiModel? {
		val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return model
		val budgetTokens = when (normalized) {
			"on", "medium" -> 4096
			"low" -> 1024
			"high" -> 8192
			"off", "none" -> 0
			else -> normalized.toIntOrNull()
		} ?: return null

		val reasoning = if (budgetTokens == 0) {
			AiModel.Reasoning(value = "none", budgetTokens = 0)
		} else {
			AiModel.Reasoning(budgetTokens = budgetTokens)
		}
		return model.copy(reasoning = reasoning)
	}

	override fun reasoningSuggestions(model: AiModel): List<AiProvider.ReasoningSuggestion> {
		if (!model.reasoningSupport.supportsReasoning()) {
			return emptyList()
		}
		return listOf(
			AiProvider.ReasoningSuggestion("1024", "Thinking budget example: low"),
			AiProvider.ReasoningSuggestion("4096", "Thinking budget example: medium"),
			AiProvider.ReasoningSuggestion("8192", "Thinking budget example: high"),
			AiProvider.ReasoningSuggestion("none", "Disable thinking"),
		)
	}

	override fun describeReasoning(model: AiModel): String? {
		val reasoning = model.reasoning ?: return null
		if (!reasoning.isEnabled()) {
			return "thinking off"
		}
		return reasoning.budgetTokens?.let { "thinking $it" } ?: "thinking on"
	}

	override fun generate(model: AiModel, session: Session, onActionChange: (String) -> Unit): Boolean {
		return try {
			val responseText = withClient { client ->
				val maxTokens = maxTokensFor(model)
				val builder = MessageCreateParams.builder()
					.model(model.apiName)
					.maxTokens(maxTokens)

				session.effectiveSystemPrompt()?.let(builder::system)
				applyThinking(model, builder)

				for (message in session.messages()) {
					when (message.type) {
						Session.Message.Type.USER -> builder.addMessage(
							MessageParam.builder()
								.role(MessageParam.Role.USER)
								.content(message.combinedContent())
								.build()
						)
						Session.Message.Type.ASSISTANT -> builder.addMessage(
							MessageParam.builder()
								.role(MessageParam.Role.ASSISTANT)
								.content(message.combinedContent())
								.build()
						)
						Session.Message.Type.ERROR -> Unit
					}
				}

				streamResponse(client, builder.build(), model, onActionChange)
			}

			session.addAssistantMessage(responseText)
			true
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			false
		}
	}

	private fun maxTokensFor(model: AiModel): Long {
		val budgetTokens = model.reasoning?.budgetTokens?.takeIf { it > 0 }?.toLong()
		if (budgetTokens == null) {
			return DEFAULT_MAX_TOKENS
		}

		return maxOf(DEFAULT_MAX_TOKENS, budgetTokens + MIN_RESPONSE_BUFFER_TOKENS)
	}

	private fun streamResponse(
		client: com.anthropic.client.AnthropicClient,
		params: MessageCreateParams,
		model: AiModel,
		onActionChange: (String) -> Unit,
	): String {
		val response = client.messages().createStreaming(params)
		val text = StringBuilder()
		var generating = !model.usesReasoning()

		try {
			response.stream().forEach { event ->
				if (!event.isContentBlockDelta()) {
					return@forEach
				}

				val delta = event.asContentBlockDelta().delta()
				when {
					delta.isThinking() -> Unit
					delta.isText() -> {
						if (!generating) {
							onActionChange("generating")
							generating = true
						}
						text.append(delta.asText().text())
					}
				}
			}
		} finally {
			response.close()
		}

		return text.toString().ifBlank { "Claude returned an empty response." }
	}

	private fun applyThinking(model: AiModel, builder: MessageCreateParams.Builder) {
		val config = model.reasoning ?: return
		if (!config.isEnabled()) {
			builder.thinking(
				ThinkingConfigParam.ofDisabled(
					ThinkingConfigDisabled.builder().build()
				)
			)
			return
		}

		val budgetTokens = config.budgetTokens
		if (budgetTokens != null) {
			builder.thinking(
				ThinkingConfigParam.ofEnabled(
					ThinkingConfigEnabled.builder()
						.budgetTokens(budgetTokens.toLong())
						.build()
				)
			)
			return
		}

		builder.thinking(
			ThinkingConfigParam.ofAdaptive(
				ThinkingConfigAdaptive.builder().build()
			)
		)
	}

	private fun client() = AnthropicOkHttpClient.builder()
		.apiKey(requiredApiKey())
		.build()

	private fun requiredApiKey(): String {
		return EnvironmentVariables.get(apiKeyVariable)?.takeIf { it.isNotBlank() }
			?: error("Missing $apiKeyVariable in ${EnvironmentVariables.MINEAI_FILE_NAME} or ${EnvironmentVariables.DOTENV_FILE_NAME}.")
	}

	private fun <T> withClient(block: (com.anthropic.client.AnthropicClient) -> T): T {
		val client = client()
		try {
			return block(client)
		} finally {
			client.close()
		}
	}
}
