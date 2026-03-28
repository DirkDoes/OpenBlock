package me.wanttobee.mineai.ai.providers

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.sessions.AiModel
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

	override fun generate(model: AiModel, session: Session, onActionChange: (String) -> Unit): Boolean {
		return try {
			val responseText = withClient { client ->
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
				streamResponse(client, model.apiName, toContents(session), config, model, onActionChange)
			}

			session.addAssistantMessage(responseText)
			true
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			false
		}
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
				Session.Message.Type.ERROR -> null
			}
		}
	}
}
