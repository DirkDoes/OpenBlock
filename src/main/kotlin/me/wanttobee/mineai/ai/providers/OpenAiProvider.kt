package me.wanttobee.mineai.ai.providers

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.responses.ResponseCreateParams
import me.wanttobee.mineai.ai.AiModel
import me.wanttobee.mineai.EnvironmentVariables
import net.minecraft.ChatFormatting
import java.util.stream.Collectors

object OpenAiProvider : AiProvider {
	override val name = "openai"
	override val displayName = "OpenAI"
	override val apiKeyVariable = "OPENAI_API_KEY"
	override val modelVariable = "OPENAI_MODEL"
	override val defaultModel = "gpt-5-nano-2025-08-07"
	override val models = listOf(
		AiModel("gpt-5.4-mini", "GPT-5.4 Mini"),
		AiModel("gpt-5.4-pro", "GPT-5.4 Pro"),
		AiModel("gpt-5.4-nano", "GPT-5.4 Nano"),
		AiModel("gpt-5-nano-2025-08-07", "GPT-5 Nano"),
		AiModel("gpt-5-mini", "GPT-5 Mini"),
		AiModel("gpt-4.1", "GPT-4.1"),
		AiModel("gpt-5-2025-08-07", "GPT-5"),
		AiModel("gpt-5-codex", "GPT-5 Codex"),
		AiModel("gpt-5.3-codex", "GPT-5.3 Codex"),
		AiModel("gpt-5.2-codex", "GPT-5.2 Codex"),
		AiModel("gpt-5.1-codex-mini", "GPT-5.1 Codex Mini"),
		AiModel("gpt-5.2-2025-12-11", "GPT-5.2"),
		AiModel("gpt-5.1-2025-11-13", "GPT-5.1"),
		AiModel("o3-2025-04-16", "o3"),
		AiModel("o4-mini-2025-04-16", "o4-mini"),
	)
	override val chatColor = ChatFormatting.AQUA

	override fun ping() {
		withClient { client ->
			client.models().retrieve(defaultModel)
		}
	}

	override fun generateResponse(model: String, prompt: String): String {
		return withClient { client ->
			val response = client.responses().create(
				ResponseCreateParams.builder()
					.model(model)
					.input(prompt)
					.build()
			)

			val text = response.output().stream()
				.flatMap { item -> item.message().stream() }
				.flatMap { message -> message.content().stream() }
				.flatMap { content -> content.outputText().stream() }
				.map { outputText -> outputText.text() }
				.filter { value -> !value.isNullOrBlank() }
				.collect(Collectors.joining("\n"))

			text.ifBlank { "OpenAI returned an empty response." }
		}
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
}
