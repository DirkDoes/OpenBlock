package me.wanttobee.mineai.ai.providers

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import me.wanttobee.mineai.ai.AiModel
import me.wanttobee.mineai.EnvironmentVariables
import net.minecraft.ChatFormatting
import java.util.stream.Collectors

object AnthropicProvider : AiProvider {
	override val name = "claude"
	override val displayName = "Claude"
	override val apiKeyVariable = "ANTHROPIC_API_KEY"
	override val modelVariable = "ANTHROPIC_MODEL"
	override val defaultModel = "claude-haiku-4-5"
	override val models = listOf(
		AiModel("claude-haiku-4-5", "Haiku 4.5"),
		AiModel("claude-sonnet-4-6", "Sonnet 4.6"),
		AiModel("claude-opus-4-6", "Opus 4.6"),
		AiModel("claude-opus-4-5-20251101", "Opus 4.5"),
		AiModel("claude-sonnet-4-5-20250929", "Sonnet 4.5"),
		AiModel("claude-sonnet-4-20250514", "Sonnet 4"),
	)
	override val chatColor = ChatFormatting.GOLD

	override fun ping() {
		withClient { client ->
			client.models().retrieve(defaultModel)
		}
	}

	override fun generateResponse(model: String, prompt: String): String {
		return withClient { client ->
			val message = client.messages().create(
				MessageCreateParams.builder()
					.model(model)
					.maxTokens(512)
					.addUserMessage(prompt)
					.build()
			)

			val text = message.content().stream()
				.flatMap { contentBlock -> contentBlock.text().stream() }
				.map { textBlock -> textBlock.text() }
				.filter { value -> !value.isNullOrBlank() }
				.collect(Collectors.joining("\n"))

			text.ifBlank { "Claude returned an empty response." }
		}
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
