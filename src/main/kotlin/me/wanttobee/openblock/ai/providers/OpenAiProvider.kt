package me.wanttobee.openblock.ai.providers

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue as OpenAiJsonValue
import com.openai.models.responses.FunctionTool as OpenAiFunctionTool
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
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
		}.getOrThrow()
	}

	override fun applyReasoning(model: AiModel, value: String?): Result<AiModel> {
		val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Result.success(model)
		val reasoning = when (normalized) {
			"on" -> AiModel.Reasoning(
				value = model.reasoningSupport.values.find { it == "medium" }
					?: model.reasoningSupport.values.firstOrNull()
					?: "medium"
			)
			"off", "none" -> AiModel.Reasoning(value = "none")
			else -> AiModel.Reasoning(value = normalized)
		}
		return Result.success(model.copy(reasoning = reasoning))
	}

	override fun reasoningSuggestions(model: AiModel): Result<List<AiProvider.ReasoningSuggestion>> {
		val support = model.reasoningSupport
		if (!support.supportsReasoning()) {
			return Result.success(emptyList())
		}
		val suggestions = support.values.map { value ->
			AiProvider.ReasoningSuggestion(value, "Reasoning effort: $value")
		}.toMutableList()
		if (support.allowsNone) {
			suggestions += AiProvider.ReasoningSuggestion("none", "Disable reasoning")
		}
		return Result.success(suggestions)
	}

	override fun describeReasoning(model: AiModel): Result<String> {
		val reasoning = model.reasoning ?: return Result.failure(
			IllegalStateException("Reasoning is not configured for ${model.displayName}.")
		)
		if (!reasoning.isEnabled()) {
			return Result.success("reasoning off")
		}
		return Result.success(reasoning.value?.let { "reasoning $it" } ?: "reasoning on")
	}

	override fun generate(
		model: AiModel,
		session: Session,
		onActionChange: (String) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): Result<Boolean> {
		val result = withClient { client ->
			val enabledTools = enabledTools(session)
			if (enabledTools.isEmpty()) {
				val params = ResponseCreateParams.builder()
					.model(model.apiName)
					.inputOfResponse(toInputItems(session))

				session.effectiveSystemPrompt().getOrNull()?.let(params::instructions)
				applyReasoning(model, params)
				val response = client.responses().create(params.build())
				val usage = openAiUsage(response).getOrNull()
				session.recordProviderCall(name, model.apiName, usage, "assistant")
				GenerationOutcome(extractText(response), usage)
			} else {
				generateWithTools(client, model, session, enabledTools, onActionChange, onMessageAdded)
			}
		}.mapCatching { outcome ->
			session.addAssistantMessage(outcome.text, outcome.usage, name, model.apiName)
			true
		}

		result.onFailure { exception ->
			session.addErrorMessage(exception.message ?: "Unknown error", providerName = name, modelName = model.apiName)
		}
		return result
	}

	private fun generateWithTools(
		client: com.openai.client.OpenAIClient,
		model: AiModel,
		session: Session,
		enabledTools: List<AiTool>,
		onActionChange: (String) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): GenerationOutcome {
		var previousResponseId: String? = null
		var toolInputs: List<ResponseInputItem> = toInputItems(session)

		repeat(AiProvider.MAX_TOOL_CALLS) {
			val params = ResponseCreateParams.builder()
				.model(model.apiName)
				.parallelToolCalls(false)

			session.effectiveSystemPrompt().getOrNull()?.let(params::instructions)
			applyReasoning(model, params)
			enabledTools.map(::openAiTool).forEach(params::addTool)

			if (previousResponseId == null) {
				params.inputOfResponse(toolInputs)
			} else {
				params.previousResponseId(previousResponseId)
				params.inputOfResponse(toolInputs)
			}

			val response = client.responses().create(params.build())
			val usage = openAiUsage(response).getOrNull()
			previousResponseId = response.id()
			val toolCalls = response.output()
				.filter(ResponseOutputItem::isFunctionCall)
				.map(ResponseOutputItem::asFunctionCall)

			if (toolCalls.isEmpty()) {
				onActionChange("generating")
				session.recordProviderCall(name, model.apiName, usage, "assistant")
				return GenerationOutcome(extractText(response), usage)
			}
			session.recordProviderCall(name, model.apiName, usage, "tool_calls")

			toolInputs = toolCalls.map { toolCall ->
				onActionChange("using ${toolCall.name()}")
				val playerId = session.boundPlayerId
				val arguments = parseJsonArguments(toolCall.arguments())
				val invocation = ToolManager.invoke(
					boundedPlayerId = playerId,
					name = toolCall.name(),
					arguments = arguments,
				)
				val result = invocation.fold(
					onSuccess = { toolInvocation ->
						toolInvocation.conversationMessage?.let { content ->
							session.addToolMessage(content)
							session.lastMessage().getOrNull()?.let(onMessageAdded)
						}
						session.recordToolInvocation(toolCall.name(), arguments, toolInvocation.execution, toolInvocation.conversationMessage)
						toolInvocation.execution
					},
					onFailure = { error ->
						val failedResult = AiToolExecution(
							payload = mapOf("message" to (error.message ?: "Tool invocation failed: ${toolCall.name()}")),
							isError = true,
						)
						session.recordToolInvocation(toolCall.name(), arguments, failedResult, null)
						failedResult
					},
				)

				ResponseInputItem.ofFunctionCallOutput(
					ResponseInputItem.FunctionCallOutput.builder()
						.callId(toolCall.callId())
						.outputAsJson(result.asResponseMap())
						.build()
				)
			}
		}

		return GenerationOutcome(toolCallLimitReachedMessage())
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

	private fun extractText(response: Response): String {
		val text = response.output()
			.filter(ResponseOutputItem::isMessage)
			.flatMap { outputItem -> outputItem.asMessage().content() }
			.filter { content -> content.isOutputText() }
			.joinToString(separator = "") { content -> content.asOutputText().text() }

		return text.ifBlank { "OpenAI returned an empty response." }
	}

	private fun openAiTool(tool: AiTool): OpenAiFunctionTool {
		return OpenAiFunctionTool.builder()
			.name(tool.name)
			.description(tool.description)
			.strict(true)
			.parameters(
				OpenAiFunctionTool.Parameters.builder()
					.putAdditionalProperty("type", OpenAiJsonValue.from("object"))
					.putAdditionalProperty("properties", OpenAiJsonValue.from(openAiProperties(tool)))
					.putAdditionalProperty("required", OpenAiJsonValue.from(requiredParameters(tool)))
					.putAdditionalProperty("additionalProperties", OpenAiJsonValue.from(false))
					.build()
			)
			.build()
	}

	private fun openAiProperties(tool: AiTool): Map<String, Any> {
		return tool.parameters.associate { parameter ->
			parameter.name to schemaProperty(parameter)
		}
	}

	private fun requiredParameters(tool: AiTool): List<String> {
		return tool.parameters.map(AiToolParameter::name)
	}

	private fun schemaProperty(parameter: AiToolParameter): Map<String, Any> {
		return linkedMapOf(
			"type" to when {
				parameter.required -> when (parameter.type) {
					AiToolParameter.ParameterType.STRING -> "string"
					AiToolParameter.ParameterType.UUID -> "string"
				}
				else -> listOf(
					when (parameter.type) {
						AiToolParameter.ParameterType.STRING -> "string"
						AiToolParameter.ParameterType.UUID -> "string"
					},
					"null",
				)
			},
			"description" to parameter.description,
		)
	}

	private fun parseJsonArguments(argumentsJson: String): Map<String, String> {
		val jsonObject = com.google.gson.JsonParser.parseString(argumentsJson).asJsonObject
		return jsonObject.entrySet().associate { (key, value) ->
			key to when {
				value.isJsonNull -> ""
				value.isJsonPrimitive -> value.asJsonPrimitive.asString
				else -> value.toString()
			}
		}
	}

	private fun openAiUsage(response: Response): Result<SessionTokenUsage> {
		val usage = response.usage().orElse(null)
			?: return Result.failure(NoSuchElementException("OpenAI response has no usage metadata."))
		return Result.success(SessionTokenUsage(
			inputTokens = usage._inputTokens().asKnown().orElse(null),
			outputTokens = usage._outputTokens().asKnown().orElse(null),
			totalTokens = usage._totalTokens().asKnown().orElse(null),
			cachedInputTokens = usage._inputTokensDetails().asKnown().flatMap { details ->
				details._cachedTokens().asKnown()
			}.orElse(null),
			reasoningTokens = usage._outputTokensDetails().asKnown().flatMap { details ->
				details._reasoningTokens().asKnown()
			}.orElse(null),
		))
	}

	private data class GenerationOutcome(
		val text: String,
		val usage: SessionTokenUsage? = null,
	)

	private fun client(apiKey: String) = OpenAIOkHttpClient.builder()
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

	private fun <T> withClient(block: (com.openai.client.OpenAIClient) -> T): Result<T> {
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

	private fun toInputItems(session: Session): List<ResponseInputItem> {
		return session.messages().mapNotNull { message ->
			when (message.type) {
				SessionMessage.Type.USER -> ResponseInputItem.ofEasyInputMessage(
					EasyInputMessage.builder()
						.role(EasyInputMessage.Role.USER)
						.content(message.combinedContent())
						.build()
				)
				SessionMessage.Type.ASSISTANT -> ResponseInputItem.ofEasyInputMessage(
					EasyInputMessage.builder()
						.role(EasyInputMessage.Role.ASSISTANT)
						.content(message.combinedContent())
						.phase(EasyInputMessage.Phase.FINAL_ANSWER)
						.build()
				)
				SessionMessage.Type.TOOL,
				SessionMessage.Type.ERROR -> null
			}
		}
	}
}
