package me.wanttobee.mineai.ai.providers

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue as AnthropicJsonValue
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.ThinkingConfigEnabled
import com.anthropic.models.messages.ThinkingConfigParam
import com.anthropic.models.messages.Tool as AnthropicTool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.sessions.AiModel
import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.ToolManager
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

	override fun generate(
		model: AiModel,
		session: Session,
		onActionChange: (String) -> Unit,
		onMessageAdded: (Session.Message) -> Unit,
	): Boolean {
		return try {
			val outcome = withClient { client ->
				val enabledTools = enabledTools(session)
				if (enabledTools.isEmpty()) {
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
							Session.Message.Type.TOOL,
							Session.Message.Type.ERROR -> Unit
						}
					}

					val response = client.messages().create(builder.build())
					val usage = anthropicUsage(response)
					session.recordProviderCall(name, model.apiName, usage, "assistant")
					GenerationOutcome(extractText(response), usage)
				} else {
					generateWithTools(client, model, session, enabledTools, onActionChange, onMessageAdded)
				}
			}

			session.addAssistantMessage(outcome.text, outcome.usage)
			true
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			false
		}
	}

	private fun generateWithTools(
		client: com.anthropic.client.AnthropicClient,
		model: AiModel,
		session: Session,
		enabledTools: List<me.wanttobee.mineai.ai.toolcalling.AiTool>,
		onActionChange: (String) -> Unit,
		onMessageAdded: (Session.Message) -> Unit,
	): GenerationOutcome {
		val conversation = session.messages().mapNotNull { message ->
			when (message.type) {
				Session.Message.Type.USER -> MessageParam.builder()
					.role(MessageParam.Role.USER)
					.content(message.combinedContent())
					.build()
				Session.Message.Type.ASSISTANT -> MessageParam.builder()
					.role(MessageParam.Role.ASSISTANT)
					.content(message.combinedContent())
					.build()
				Session.Message.Type.TOOL,
				Session.Message.Type.ERROR -> null
			}
		}.toMutableList()

		repeat(AiProvider.MAX_TOOL_CALLS) {
			val builder = MessageCreateParams.builder()
				.model(model.apiName)
				.maxTokens(maxTokensFor(model))
				.toolChoice(ToolChoiceAuto.builder().build())

			session.effectiveSystemPrompt()?.let(builder::system)
			applyThinking(model, builder)
			enabledTools.map(::anthropicTool).forEach(builder::addTool)
			conversation.forEach(builder::addMessage)

			val response = client.messages().create(builder.build())
			val usage = anthropicUsage(response)
			val toolUses = response.content()
				.filter(ContentBlock::isToolUse)
				.map(ContentBlock::asToolUse)

			if (toolUses.isEmpty()) {
				onActionChange("generating")
				session.recordProviderCall(name, model.apiName, usage, "assistant")
				return GenerationOutcome(extractText(response), usage)
			}
			session.recordProviderCall(name, model.apiName, usage, "tool_calls")

			conversation += response.toParam()

			val toolResults = toolUses.map { toolUse ->
				onActionChange("using ${toolUse.name()}")
				val arguments = parseJsonArguments(toolUse._input())
				val invocation = ToolManager.invoke(
					playerId = session.boundPlayerId,
					name = toolUse.name(),
					arguments = arguments,
				)
				invocation?.conversationMessage?.let { content ->
					session.addToolMessage(content)
					onMessageAdded(session.lastMessage()!!)
				}
				val result = invocation?.execution ?: missingToolResult(toolUse.name())
				session.recordToolInvocation(toolUse.name(), arguments, result, invocation?.conversationMessage)

				ContentBlockParam.ofToolResult(
					ToolResultBlockParam.builder()
						.toolUseId(toolUse.id())
						.content(result.asResponseMapAsString())
						.isError(result.isError)
						.build()
				)
			}

			conversation += MessageParam.builder()
				.role(MessageParam.Role.USER)
				.contentOfBlockParams(toolResults)
				.build()
		}

		return GenerationOutcome(toolCallLimitReachedMessage())
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

	private fun extractText(message: Message): String {
		val text = message.content()
			.filter(ContentBlock::isText)
			.joinToString(separator = "") { block -> block.asText().text() }
		return text.ifBlank { "Claude returned an empty response." }
	}

	private fun anthropicTool(tool: AiTool): AnthropicTool {
		val properties = AnthropicTool.InputSchema.Properties.builder().apply {
			for (parameter in tool.parameters) {
				putAdditionalProperty(parameter.name, AnthropicJsonValue.from(schemaProperty(parameter)))
			}
		}.build()

		return AnthropicTool.builder()
			.name(tool.name)
			.description(tool.description)
			.strict(true)
				.inputSchema(
					AnthropicTool.InputSchema.builder()
						.properties(properties)
						.required(tool.parameters.filter(AiTool.Parameter::required).map(AiTool.Parameter::name))
						.additionalProperties(mapOf("additionalProperties" to AnthropicJsonValue.from(false)))
						.build()
				)
			.build()
	}

	private fun schemaProperty(parameter: AiTool.Parameter): Map<String, Any> {
		return linkedMapOf(
			"type" to when (parameter.type) {
				AiTool.Type.STRING -> "string"
				AiTool.Type.UUID -> "string"
			},
			"description" to parameter.description,
		)
	}

	private fun parseJsonArguments(argumentsJson: AnthropicJsonValue): Map<String, String> {
		val jsonObject = argumentsJson.asObject().orElse(emptyMap())
		return jsonObject.mapValues { (_, value) -> jsonValueToString(value) }
	}

	private fun jsonValueToString(value: AnthropicJsonValue): String {
		return value.asString().orElseGet {
			value.asNumber().map(Number::toString).orElseGet {
				value.asBoolean().map(Boolean::toString).orElseGet {
					when {
						value.isNull() -> ""
						else -> value.toString()
					}
				}
			}
		}
	}

	private fun anthropicUsage(message: Message): Session.TokenUsage? {
		val usage = message._usage().asKnown().orElse(null) ?: return null
		val inputTokens = usage._inputTokens().asKnown().orElse(null)
		val outputTokens = usage._outputTokens().asKnown().orElse(null)
		val cacheCreationInputTokens = usage._cacheCreationInputTokens().asKnown().orElse(null)
		val cacheReadInputTokens = usage._cacheReadInputTokens().asKnown().orElse(null)
		val totalTokens = listOfNotNull(
			inputTokens,
			outputTokens,
			cacheCreationInputTokens,
			cacheReadInputTokens,
		).sum().takeIf { it > 0 }
		return Session.TokenUsage(
			inputTokens = inputTokens,
			outputTokens = outputTokens,
			totalTokens = totalTokens,
			cacheCreationInputTokens = cacheCreationInputTokens,
			cacheReadInputTokens = cacheReadInputTokens,
		)
	}

	private data class GenerationOutcome(
		val text: String,
		val usage: Session.TokenUsage? = null,
	)

	private fun AiTool.ExecutionResult.asResponseMapAsString(): String {
		return com.google.gson.Gson().toJson(asResponseMap())
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
