package me.wanttobee.openblock.ai.providers

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
import me.wanttobee.openblock.ai.AiActionBarManager
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage
import me.wanttobee.openblock.ai.toolcalling.ToolManager
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.util.EnvironmentVariables
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
		}.getOrThrow()
	}

	override fun applyReasoning(model: AiModel, value: String?): Result<AiModel> {
		val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Result.success(model)
		val budgetTokens = when (normalized) {
			"on", "medium" -> 4096
			"low" -> 1024
			"high" -> 8192
			"off", "none" -> 0
			else -> normalized.toIntOrNull()
		} ?: return Result.failure(IllegalArgumentException("Unsupported reasoning value: $normalized"))

		val reasoning = if (budgetTokens == 0) {
			AiModel.Reasoning(value = "none", budgetTokens = 0)
		} else {
			AiModel.Reasoning(budgetTokens = budgetTokens)
		}
		return Result.success(model.copy(reasoning = reasoning))
	}

	override fun reasoningSuggestions(model: AiModel): Result<List<AiProvider.ReasoningSuggestion>> {
		if (!model.reasoningSupport.supportsReasoning()) {
			return Result.success(emptyList())
		}
		return Result.success(listOf(
			AiProvider.ReasoningSuggestion("1024", "Thinking budget example: low"),
			AiProvider.ReasoningSuggestion("4096", "Thinking budget example: medium"),
			AiProvider.ReasoningSuggestion("8192", "Thinking budget example: high"),
			AiProvider.ReasoningSuggestion("none", "Disable thinking"),
		))
	}

	override fun describeReasoning(model: AiModel): Result<String> {
		val reasoning = model.reasoning ?: return Result.failure(
			IllegalStateException("Reasoning is not configured for ${model.displayName}.")
		)
		if (!reasoning.isEnabled()) {
			return Result.success("thinking off")
		}
		return Result.success(reasoning.budgetTokens?.let { "thinking $it" } ?: "thinking on")
	}

	override fun generate(
		model: AiModel,
		session: Session,
		generationId: Long,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): Result<Boolean> {
		if (session.isGenerationInterrupted(generationId)) {
			return Result.success(false)
		}

		val result = withClient { client ->
			val enabledTools = enabledTools(session)
			if (enabledTools.isEmpty()) {
				val maxTokens = maxTokensFor(model)
				val builder = MessageCreateParams.builder()
					.model(model.apiName)
					.maxTokens(maxTokens)

				session.effectiveSystemPrompt().getOrNull()?.let(builder::system)
				applyThinking(model, builder)

				for (message in session.messages()) {
					when (message.type) {
						SessionMessage.Type.USER -> builder.addMessage(
							MessageParam.builder()
								.role(MessageParam.Role.USER)
								.content(message.combinedContent())
								.build()
						)
						SessionMessage.Type.ASSISTANT -> builder.addMessage(
							MessageParam.builder()
								.role(MessageParam.Role.ASSISTANT)
								.content(message.combinedContent())
								.build()
						)
						SessionMessage.Type.TOOL,
						SessionMessage.Type.ERROR -> Unit
					}
				}

				val response = client.messages().create(builder.build())
				val usage = anthropicUsage(response).getOrNull()
				if (session.isGenerationInterrupted(generationId)) {
					session.recordProviderCall(name, model.apiName, usage, "interrupted")
					GenerationOutcome(interrupted = true, usage = usage)
				} else {
					session.recordProviderCall(name, model.apiName, usage, "assistant")
					GenerationOutcome(extractText(response), usage = usage)
				}
			} else {
				generateWithTools(client, model, session, generationId, enabledTools, onActionChange, onMessageAdded)
			}
		}.mapCatching { outcome ->
			if (outcome.interrupted) {
				false
			} else {
				session.addAssistantMessage(outcome.text, outcome.usage, name, model.apiName)
				true
			}
		}

		result.onFailure { exception ->
			session.addErrorMessage(exception.message ?: "Unknown error", providerName = name, modelName = model.apiName)
		}
		return result
	}

	private fun generateWithTools(
		client: com.anthropic.client.AnthropicClient,
		model: AiModel,
		session: Session,
		generationId: Long,
		enabledTools: List<AiTool>,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): GenerationOutcome {
		val conversation = session.messages().mapNotNull { message ->
			when (message.type) {
				SessionMessage.Type.USER -> MessageParam.builder()
					.role(MessageParam.Role.USER)
					.content(message.combinedContent())
					.build()
				SessionMessage.Type.ASSISTANT -> MessageParam.builder()
					.role(MessageParam.Role.ASSISTANT)
					.content(message.combinedContent())
					.build()
				SessionMessage.Type.TOOL,
				SessionMessage.Type.ERROR -> null
			}
		}.toMutableList()

		repeat(AiProvider.MAX_TOOL_CALLS) {
			if (session.isGenerationInterrupted(generationId)) {
				return GenerationOutcome(interrupted = true)
			}

			val builder = MessageCreateParams.builder()
				.model(model.apiName)
				.maxTokens(maxTokensFor(model))
				.toolChoice(ToolChoiceAuto.builder().build())

			session.effectiveSystemPrompt().getOrNull()?.let(builder::system)
			applyThinking(model, builder)
			enabledTools.map(::anthropicTool).forEach(builder::addTool)
			conversation.forEach(builder::addMessage)

			val response = client.messages().create(builder.build())
			val usage = anthropicUsage(response).getOrNull()
			val toolUses = response.content()
				.filter(ContentBlock::isToolUse)
				.map(ContentBlock::asToolUse)

			if (toolUses.isEmpty()) {
				if (session.isGenerationInterrupted(generationId)) {
					session.recordProviderCall(name, model.apiName, usage, "interrupted")
					return GenerationOutcome(interrupted = true, usage = usage)
				}
				onActionChange("generating", AiActionBarManager.IndicatorState.PROVIDER_PROGRESS)
				session.recordProviderCall(name, model.apiName, usage, "assistant")
				return GenerationOutcome(extractText(response), usage = usage)
			}
			session.recordProviderCall(name, model.apiName, usage, "tool_calls")

			conversation += response.toParam()
			if (session.isGenerationInterrupted(generationId)) {
				return GenerationOutcome(interrupted = true, usage = usage)
			}

			onActionChange(toolBatchActionLabel(toolUses.map { it.name() }), AiActionBarManager.IndicatorState.TOOL_PROCESSING)
			val toolRequests = toolUses.map { toolUse ->
				ToolManager.ToolCallRequest(
					name = toolUse.name(),
					arguments = parseJsonArguments(toolUse._input()),
				)
			}
			val toolOutcomes = ToolManager.invokeAllParallel(session.boundPlayerId, toolRequests) { started ->
				started.conversationMessage?.let { content ->
					session.addToolMessage(content)
					session.lastMessage().getOrNull()?.let(onMessageAdded)
				}
			}
			if (session.isGenerationInterrupted(generationId)) {
				return GenerationOutcome(interrupted = true)
			}
			val batchIndicator = if (toolOutcomes.any(::hasToolError)) {
				AiActionBarManager.IndicatorState.TOOL_ERROR
			} else {
				AiActionBarManager.IndicatorState.TOOL_SUCCESS
			}
			onActionChange(toolBatchActionLabel(toolUses.map { it.name() }), batchIndicator)

			val toolResults = toolUses.zip(toolOutcomes).map { (toolUse, outcome) ->
				val result = outcome.invocation.fold(
					onSuccess = { toolInvocation ->
						session.recordToolInvocation(toolUse.name(), outcome.arguments, toolInvocation.execution, toolInvocation.conversationMessage)
						toolInvocation.execution
					},
					onFailure = { error ->
						val failedResult = AiToolExecution(
							payload = mapOf("message" to (error.message ?: "Tool invocation failed: ${toolUse.name()}")),
							isError = true,
						)
						session.recordToolInvocation(toolUse.name(), outcome.arguments, failedResult, null)
						failedResult
					},
				)

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
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit,
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
							onActionChange("generating", AiActionBarManager.IndicatorState.PROVIDER_PROGRESS)
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
						.required(tool.parameters.filter(AiToolParameter::required).map(AiToolParameter::name))
						.additionalProperties(mapOf("additionalProperties" to AnthropicJsonValue.from(false)))
						.build()
				)
			.build()
	}

	private fun schemaProperty(parameter: AiToolParameter): Map<String, Any> {
		return linkedMapOf(
			"type" to when (parameter.type) {
				AiToolParameter.ParameterType.STRING -> "string"
				AiToolParameter.ParameterType.UUID -> "string"
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

	private fun anthropicUsage(message: Message): Result<SessionTokenUsage> {
		val usage = message._usage().asKnown().orElse(null)
			?: return Result.failure(NoSuchElementException("Anthropic message has no usage metadata."))
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
		return Result.success(SessionTokenUsage(
			inputTokens = inputTokens,
			outputTokens = outputTokens,
			totalTokens = totalTokens,
			cacheCreationInputTokens = cacheCreationInputTokens,
			cacheReadInputTokens = cacheReadInputTokens,
		))
	}

	private data class GenerationOutcome(
		val text: String = "",
		val interrupted: Boolean = false,
		val usage: SessionTokenUsage? = null,
	)

	private fun AiToolExecution.asResponseMapAsString(): String {
		return com.google.gson.Gson().toJson(asResponseMap())
	}

	private fun client(apiKey: String) = AnthropicOkHttpClient.builder()
		.apiKey(apiKey)
		.build()

	private fun requiredApiKey(): Result<String> {
		return EnvironmentVariables.get(apiKeyVariable).mapCatching { apiKey ->
			apiKey.takeIf { it.isNotBlank() }
				?: throw IllegalStateException(
					"Missing $apiKeyVariable in ${EnvironmentVariables.OPENBLOCK_FILE_NAME} or ${EnvironmentVariables.DOTENV_FILE_NAME}."
				)
		}
	}

	private fun <T> withClient(block: (com.anthropic.client.AnthropicClient) -> T): Result<T> {
		val apiKey = requiredApiKey().getOrElse { return Result.failure(it) }
		return runCatching {
			val client = client(apiKey)
			try {
				block(client)
			} finally {
				client.close()
			}
		}
	}
}
