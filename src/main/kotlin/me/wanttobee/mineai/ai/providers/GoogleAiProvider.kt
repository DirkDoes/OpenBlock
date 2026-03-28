package me.wanttobee.mineai.ai.providers

import com.google.genai.Client
import me.wanttobee.mineai.ai.AiModel
import me.wanttobee.mineai.EnvironmentVariables
import net.minecraft.ChatFormatting

object GoogleAiProvider : AiProvider {
	override val name = "google"
	override val displayName = "Google"
	override val apiKeyVariable = "GOOGLE_API_KEY"
	override val modelVariable = "GOOGLE_MODEL"
	override val defaultModel = "gemini-2.5-flash"
	override val models = listOf(
		AiModel("gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash Lite"),
		AiModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro"),
		AiModel("gemini-3-flash-preview", "Gemini 3 Flash"),
		AiModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
		AiModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
		AiModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite"),
		AiModel("gemini-2.0-flash", "Gemini 2.0 Flash"),
		AiModel("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite")
	)
	override val chatColor = ChatFormatting.LIGHT_PURPLE

	override fun ping() {
		withClient { client ->
			client.models.get("models/$defaultModel", null)
		}
	}

	override fun generateResponse(model: String, prompt: String): String {
		return withClient { client ->
			val response = client.models.generateContent(model, prompt, null)
			response.text()?.ifBlank { "Google returned an empty response." }
				?: "Google returned an empty response."
		}
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
}
