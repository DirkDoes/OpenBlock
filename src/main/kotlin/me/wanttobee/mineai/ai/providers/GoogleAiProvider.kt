package me.wanttobee.mineai.ai.providers

import com.google.genai.Client
import com.google.genai.types.AutomaticFunctionCallingConfig
import com.google.genai.types.Content
import com.google.genai.types.FunctionResponse
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.sessions.AiModel
import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.ToolManager
import me.wanttobee.mineai.util.EnvironmentVariables
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
	override val modelVariable = "GOOGLE_MODEL"
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
		}
	}

	override fun applyReasoning(model: AiModel, value: String?): AiModel? {
		val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return model
		val support = model.reasoningSupport
		if (!support.supportsReasoning()) {
			return normalized.toIntOrNull()?.let { budget ->
				model.copy(reasoning = AiModel.Reasoning(budgetTokens = budget))
			} ?: model.copy(reasoning = AiModel.Reasoning(value = normalized))
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
			return null
		}
		return model.copy(reasoning = reasoning)
	}

	override fun reasoningSuggestions(model: AiModel): List<AiProvider.ReasoningSuggestion> {
		val support = model.reasoningSupport
		if (!support.supportsReasoning()) {
			return emptyList()
		}
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
		return suggestions
	}

	override fun describeReasoning(model: AiModel): String? {
		val reasoning = model.reasoning ?: return null
		if (!reasoning.isEnabled()) {
			return "thinking off"
		}
		return when {
			reasoning.value != null -> "thinking ${reasoning.value}"
			reasoning.budgetTokens != null -> "thinking ${reasoning.budgetTokens}"
			else -> "thinking on"
		}
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
					val config = GenerateContentConfig.builder().apply {
						session.effectiveSystemPrompt()?.let { prompt ->
							systemInstruction(
								Content.builder()
									.parts(Part.builder().text(prompt).build())
									.build()
							)
						}
						applyThinking(model)
					}.build()
					val response = client.models.generateContent(model.apiName, toContents(session), config)
					val usage = googleUsage(response)
					session.recordProviderCall(name, model.apiName, usage, "assistant")
					GenerationOutcome(
						response.text()?.takeIf { it.isNotBlank() } ?: "Google returned an empty response.",
						usage,
					)
				} else {
					generateWithTools(client, model, session, enabledTools, onActionChange, onMessageAdded)
				}
			}

			session.addAssistantMessage(outcome.text, outcome.usage)
			true
		} catch (exception: Exception) {
			session.addErrorMessage(formatException(exception))
			false
		}
	}

	private fun generateWithTools(
		client: Client,
		model: AiModel,
		session: Session,
		enabledTools: List<me.wanttobee.mineai.ai.toolcalling.AiTool>,
		onActionChange: (String) -> Unit,
		onMessageAdded: (Session.Message) -> Unit,
	): GenerationOutcome {
		val conversation = toContents(session).toMutableList()

		repeat(AiProvider.MAX_TOOL_CALLS) {
			val config = GenerateContentConfig.builder().apply {
				session.effectiveSystemPrompt()?.let { prompt ->
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
			val usage = googleUsage(response)
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
					playerId = session.boundPlayerId,
					name = toolName,
					arguments = arguments,
				)
				invocation?.conversationMessage?.let { content ->
					session.addToolMessage(content)
					onMessageAdded(session.lastMessage()!!)
				}
				val result = invocation?.execution ?: missingToolResult(toolName)
				session.recordToolInvocation(toolName, arguments, result, invocation?.conversationMessage)

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
			.required(tool.parameters.filter(AiTool.Parameter::required).map(AiTool.Parameter::name))
			.propertyOrdering(tool.parameters.map(AiTool.Parameter::name))
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

	private fun googleFunctionResponsePayload(result: AiTool.ExecutionResult): Map<String, Any?> {
		return if (result.isError) {
			mapOf("error" to result.payload)
		} else {
			mapOf("result" to result.payload)
		}
	}

	private fun formatException(exception: Exception): String {
		return exception.message?.takeIf { it.isNotBlank() }
			?: exception.toString()
	}

	private fun googleUsage(response: com.google.genai.types.GenerateContentResponse): Session.TokenUsage? {
		val usage = response.usageMetadata().orElse(null) ?: return null
		return Session.TokenUsage(
			inputTokens = usage.promptTokenCount().orElse(null)?.toLong(),
			outputTokens = usage.candidatesTokenCount().orElse(null)?.toLong(),
			totalTokens = usage.totalTokenCount().orElse(null)?.toLong(),
			cacheReadInputTokens = usage.cachedContentTokenCount().orElse(null)?.toLong(),
			thoughtsTokens = usage.thoughtsTokenCount().orElse(null)?.toLong(),
			toolUsePromptTokens = usage.toolUsePromptTokenCount().orElse(null)?.toLong(),
		)
	}

	private data class GenerationOutcome(
		val text: String,
		val usage: Session.TokenUsage? = null,
	)

	private fun client() = Client.builder()
		.apiKey(requiredApiKey())
		.build()

	private fun requiredApiKey(): String {
		return EnvironmentVariables.get(apiKeyVariable)?.takeIf { it.isNotBlank() }
			?: error("Missing $apiKeyVariable in ${EnvironmentVariables.MINEAI_FILE_NAME} or ${EnvironmentVariables.DOTENV_FILE_NAME}.")
	}

	private fun <T> withClient(block: (Client) -> T): T {
		val client = client()
		try {
			return block(client)
		} finally {
			client.close()
		}
	}

	private fun toContents(session: Session): List<Content> {
		return session.messages().mapNotNull { message ->
			when (message.type) {
				Session.Message.Type.USER -> Content.builder()
					.role("user")
					.parts(Part.builder().text(message.combinedContent()).build())
					.build()
				Session.Message.Type.ASSISTANT -> Content.builder()
					.role("model")
					.parts(Part.builder().text(message.combinedContent()).build())
					.build()
				Session.Message.Type.TOOL,
				Session.Message.Type.ERROR -> null
			}
		}
	}
}
