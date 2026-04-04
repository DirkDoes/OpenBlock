package me.wanttobee.openblock.ai.providers

import com.google.genai.Client
import com.google.genai.types.AutomaticFunctionCallingConfig
import com.google.genai.types.Content
import com.google.genai.types.FunctionResponse
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
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

object GoogleAiProvider : AiProvider {
	private val geminiThreeProSupport = AiModel.ReasoningSupport.text(
		values = listOf("low", "medium", "high"),
		allowsNone = true,
	)
	private val geminiThreeFlashSupport = AiModel.ReasoningSupport.text(
		values = listOf("minimal", "low", "medium", "high"),
		allowsNone = true,
	)
	private val geminiTwoFiveSupport = AiModel.ReasoningSupport.number(
		numericExamples = listOf(1024, 8192, 24576),
		allowsNone = true,
	)

	override val name = "google"
	override val displayName = "Google"
	override val apiKeyVariable = "GOOGLE_API_KEY"
	override val defaultModel = "gemini-2.5-flash"
	override val models = listOf(
		AiModel("gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash Lite", reasoningSupport = geminiThreeFlashSupport),
		AiModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro", reasoningSupport = geminiThreeProSupport),
		AiModel("gemini-3-flash-preview", "Gemini 3 Flash", reasoningSupport = geminiThreeFlashSupport),
		AiModel("gemini-2.5-pro", "Gemini 2.5 Pro", reasoningSupport = geminiTwoFiveSupport),
		AiModel("gemini-2.5-flash", "Gemini 2.5 Flash", reasoningSupport = geminiTwoFiveSupport),
		AiModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", reasoningSupport = geminiTwoFiveSupport)
	)
	override val chatColor = ChatFormatting.LIGHT_PURPLE
	override val progressColorA = 0xFF4DFF
	override val progressColorB = 0x00E5FF

	override fun ping() {
		withClient { client ->
			client.models.get("models/$defaultModel", null)
		}.getOrThrow()
	}

	override fun applyReasoning(model: AiModel, value: String?): Result<AiModel> {
		val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Result.success(model)
		val support = model.reasoningSupport
		if (!support.supportsReasoning()) {
			val reasoning = normalized.toIntOrNull()?.let { budget ->
				model.copy(reasoning = AiModel.Reasoning(budgetTokens = budget))
			} ?: model.copy(reasoning = AiModel.Reasoning(value = normalized))
			return Result.success(reasoning)
		}
		val reasoning = when (support.kind) {
			AiModel.ReasoningSupport.Kind.TEXT -> when (normalized) {
				"on" -> AiModel.Reasoning(value = support.values.firstOrNull())
				"off", "none" -> AiModel.Reasoning(value = "none", budgetTokens = 0)
				else -> AiModel.Reasoning(value = normalized)
			}
			AiModel.ReasoningSupport.Kind.NUMBER -> when (normalized) {
				"on", "medium" -> AiModel.Reasoning(budgetTokens = 8192)
				"low" -> AiModel.Reasoning(budgetTokens = 1024)
				"high" -> AiModel.Reasoning(budgetTokens = 24576)
				"off", "none" -> AiModel.Reasoning(value = "none", budgetTokens = 0)
				else -> normalized.toIntOrNull()?.let { AiModel.Reasoning(budgetTokens = it) }
			}
			AiModel.ReasoningSupport.Kind.UNSUPPORTED -> null
		}
		if (reasoning == null) {
			return Result.failure(IllegalArgumentException("Unsupported reasoning value: $normalized"))
		}
		return Result.success(model.copy(reasoning = reasoning))
	}

	override fun reasoningSuggestions(model: AiModel): Result<List<AiProvider.ReasoningSuggestion>> {
		val support = model.reasoningSupport
		if (!support.supportsReasoning())
			return Result.success(emptyList())

		val suggestions = when (support.kind) {
			AiModel.ReasoningSupport.Kind.TEXT -> support.values.map { value ->
				AiProvider.ReasoningSuggestion(value, "Thinking level: $value")
			}
			AiModel.ReasoningSupport.Kind.NUMBER -> support.numericExamples.map { value ->
				val description = when (value) {
					1024 -> "Thinking budget example: low"
					8192 -> "Thinking budget example: medium"
					24576 -> "Thinking budget example: high"
					else -> "Thinking budget example"
				}
				AiProvider.ReasoningSuggestion(value.toString(), description)
			}
			AiModel.ReasoningSupport.Kind.UNSUPPORTED -> emptyList()
		}.toMutableList()
		if (support.allowsNone) {
			suggestions += AiProvider.ReasoningSuggestion("none", "Disable thinking")
		}
		return Result.success(suggestions)
	}

	override fun describeReasoning(model: AiModel): Result<String> {
		val reasoning = model.reasoning ?: return Result.failure(
			IllegalStateException("Reasoning is not configured for ${model.displayName}.")
		)
		if (!reasoning.isEnabled()) {
			return Result.success("thinking off")
		}
		return Result.success(when {
			reasoning.value != null -> "thinking ${reasoning.value}"
			reasoning.budgetTokens != null -> "thinking ${reasoning.budgetTokens}"
			else -> "thinking on"
		})
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
				val config = GenerateContentConfig.builder().apply {
					session.effectiveSystemPrompt().getOrNull()?.let { prompt ->
						systemInstruction(
							Content.builder()
								.parts(Part.builder().text(prompt).build())
								.build()
						)
					}
					applyThinking(model)
				}.build()
				val response = client.models.generateContent(model.apiName, toContents(session), config)
				val usage = googleUsage(response).getOrNull()
				session.recordProviderCall(name, model.apiName, usage, "assistant")
				GenerationOutcome(
					response.text()?.takeIf { it.isNotBlank() } ?: "Google returned an empty response.",
					usage,
				)
			} else {
				generateWithTools(client, model, session, enabledTools, onActionChange, onMessageAdded)
			}
		}.mapCatching { outcome ->
			session.addAssistantMessage(outcome.text, outcome.usage, name, model.apiName)
			true
		}

		result.onFailure { exception ->
			session.addErrorMessage(formatException(exception), providerName = name, modelName = model.apiName)
		}
		return result
	}

	private fun generateWithTools(
		client: Client,
		model: AiModel,
		session: Session,
		enabledTools: List<AiTool>,
		onActionChange: (String) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): GenerationOutcome {
		val conversation = toContents(session).toMutableList()

		repeat(AiProvider.MAX_TOOL_CALLS) {
			val config = GenerateContentConfig.builder().apply {
				session.effectiveSystemPrompt().getOrNull()?.let { prompt ->
					systemInstruction(
						Content.builder()
							.parts(Part.builder().text(prompt).build())
							.build()
					)
				}
				applyThinking(model)
				tools(enabledTools.map(::googleTool))
				automaticFunctionCalling(
					AutomaticFunctionCallingConfig.builder()
						.disable(true)
						.build()
				)
			}.build()

			val response = client.models.generateContent(model.apiName, conversation, config)
			val usage = googleUsage(response).getOrNull()
			val functionCalls = response.functionCalls() ?: emptyList()
			if (functionCalls.isEmpty()) {
				onActionChange("generating")
				session.recordProviderCall(name, model.apiName, usage, "assistant")
				return GenerationOutcome(
					response.text()?.takeIf { it.isNotBlank() } ?: "Google returned an empty response.",
					usage,
				)
			}
			session.recordProviderCall(name, model.apiName, usage, "tool_calls")

			response.parts()?.takeIf { it.isNotEmpty() }?.let { parts ->
				conversation += Content.builder()
					.role("model")
					.parts(parts)
					.build()
			}

			val functionResponses = functionCalls.map { functionCall ->
				val toolName = functionCall.name().orElse("")
				onActionChange("using ${functionCall.name().orElse("tool")}")
				val arguments = functionCall.args()
					.orElse(emptyMap())
					.mapValues { (_, value) -> value?.toString().orEmpty() }
				val invocation = ToolManager.invoke(
					boundedPlayerId = session.boundPlayerId,
					name = toolName,
					arguments = arguments,
				)
				val result = invocation.fold(
					onSuccess = { toolInvocation ->
						toolInvocation.conversationMessage?.let { content ->
							session.addToolMessage(content)
							session.lastMessage().getOrNull()?.let(onMessageAdded)
						}
						session.recordToolInvocation(toolName, arguments, toolInvocation.execution, toolInvocation.conversationMessage)
						toolInvocation.execution
					},
					onFailure = { error ->
						val failedResult = AiToolExecution(
							payload = mapOf("message" to (error.message ?: "Tool invocation failed: $toolName")),
							isError = true,
						)
						session.recordToolInvocation(toolName, arguments, failedResult, null)
						failedResult
					},
				)

				Part.builder()
					.functionResponse(
						FunctionResponse.builder()
							.name(toolName)
							.id(functionCall.id().orElse(null))
							.response(googleFunctionResponsePayload(result))
							.build()
					)
					.build()
			}

			conversation += Content.builder()
				.role("user")
				.parts(functionResponses)
				.build()
		}

		return GenerationOutcome(toolCallLimitReachedMessage())
	}

	private fun streamResponse(
		client: Client,
		modelName: String,
		contents: List<Content>,
		config: GenerateContentConfig,
		model: AiModel,
		onActionChange: (String) -> Unit,
	): String {
		val response = client.models.generateContentStream(modelName, contents, config)
		val text = StringBuilder()
		var generating = !model.usesReasoning()

		try {
			for (chunk in response) {
				if (chunk == null) {
					continue
				}
				val parts = chunk.candidates()
					.orElse(emptyList())
					.firstOrNull()
					?.content()
					?.orElse(null)
					?.parts()
					?.orElse(emptyList())
					?: emptyList()

				for (part in parts) {
					val isThought = part.thought().orElse(false)
					if (isThought) {
						continue
					}

					val partText = part.text().orElse("")
					if (partText.isBlank()) {
						continue
					}

					if (!generating) {
						onActionChange("generating")
						generating = true
					}
					text.append(partText)
				}
			}
		} finally {
			response.close()
		}

		return text.toString().ifBlank { "Google returned an empty response." }
	}

	private fun GenerateContentConfig.Builder.applyThinking(model: AiModel) {
		val config = model.reasoning ?: return
		val thinkingConfig = ThinkingConfig.builder().apply {
			if (!config.isEnabled()) {
				includeThoughts(false)
				thinkingBudget(0)
				return@apply
			}

			includeThoughts(config.includeThoughts ?: true)
			config.budgetTokens?.let(::thinkingBudget)
			config.value?.let(::thinkingLevel)

			if (config.budgetTokens == null && config.value == null) {
				thinkingBudget(-1)
			}
		}.build()

		thinkingConfig(thinkingConfig)
	}

	private fun googleTool(tool: AiTool): com.google.genai.types.Tool {
		val properties = tool.parameters.associate { parameter ->
			parameter.name to Schema.builder()
				.type("STRING")
				.description(parameter.description)
				.build()
		}

		val parameters = Schema.builder()
			.type("OBJECT")
			.properties(properties)
			.required(tool.parameters.filter(AiToolParameter::required).map(AiToolParameter::name))
			.propertyOrdering(tool.parameters.map(AiToolParameter::name))
			.build()

		return com.google.genai.types.Tool.builder()
			.functionDeclarations(
				FunctionDeclaration.builder()
					.name(tool.name)
					.description(tool.description)
					.parameters(parameters)
					.build()
			)
			.build()
	}

	private fun googleFunctionResponsePayload(result: AiToolExecution): Map<String, Any?> {
		return if (result.isError) {
			mapOf("error" to result.payload)
		} else {
			mapOf("result" to result.payload)
		}
	}

	private fun formatException(exception: Throwable): String {
		return exception.message?.takeIf { it.isNotBlank() }
			?: exception.toString()
	}

	private fun googleUsage(response: com.google.genai.types.GenerateContentResponse): Result<SessionTokenUsage> {
		val usage = response.usageMetadata().orElse(null)
			?: return Result.failure(NoSuchElementException("Google response has no usage metadata."))
		return Result.success(SessionTokenUsage(
			inputTokens = usage.promptTokenCount().orElse(null)?.toLong(),
			outputTokens = usage.candidatesTokenCount().orElse(null)?.toLong(),
			totalTokens = usage.totalTokenCount().orElse(null)?.toLong(),
			cacheReadInputTokens = usage.cachedContentTokenCount().orElse(null)?.toLong(),
			thoughtsTokens = usage.thoughtsTokenCount().orElse(null)?.toLong(),
			toolUsePromptTokens = usage.toolUsePromptTokenCount().orElse(null)?.toLong(),
		))
	}

	private data class GenerationOutcome(
		val text: String,
		val usage: SessionTokenUsage? = null,
	)

	private fun client(apiKey: String) = Client.builder()
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

	private fun <T> withClient(block: (Client) -> T): Result<T> {
		val apiKey = requiredApiKey().getOrElse { return Result.failure(it) }
		return runCatching {
			val client = client(apiKey)
            client.use { client ->
                block(client)
            }
		}
	}

	private fun toContents(session: Session): List<Content> {
		return session.messages().mapNotNull { message ->
			when (message.type) {
				SessionMessage.Type.USER -> Content.builder()
					.role("user")
					.parts(Part.builder().text(message.combinedContent()).build())
					.build()
				SessionMessage.Type.ASSISTANT -> Content.builder()
					.role("model")
					.parts(Part.builder().text(message.combinedContent()).build())
					.build()
				SessionMessage.Type.TOOL,
				SessionMessage.Type.ERROR -> null
			}
		}
	}
}
