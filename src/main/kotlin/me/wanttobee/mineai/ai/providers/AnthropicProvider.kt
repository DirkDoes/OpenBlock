package me.wanttobee.mineai.ai.providers

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import me.wanttobee.mineai.ai.AiModel
import me.wanttobee.mineai.EnvironmentVariables
import me.wanttobee.mineai.ai.Session
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

	override fun generateResponse(model: AiModel, session: Session): Boolean {
		return try {
			val responseText = withClient { client ->
				val builder = MessageCreateParams.builder()
					.model(model.apiName)
					.maxTokens(512)

				session.effectiveSystemPrompt()?.let(builder::system)

				for (message in session.messages()) {
					when (message.type) {
						Session.Message.Type.USER -> builder.addMessage(
							MessageParam.builder()
								.role(MessageParam.Role.USER)
								.content(message.content)
								.build()
						)
						Session.Message.Type.ASSISTANT -> builder.addMessage(
							MessageParam.builder()
								.role(MessageParam.Role.ASSISTANT)
								.content(message.content)
								.build()
						)
						Session.Message.Type.ERROR -> Unit
					}
				}

				val response = client.messages().create(builder.build())
				val text = response.content().stream()
					.flatMap { contentBlock -> contentBlock.text().stream() }
					.map { textBlock -> textBlock.text() }
					.filter { value -> !value.isNullOrBlank() }
					.collect(Collectors.joining("\n"))

				text.ifBlank { "Claude returned an empty response." }
			}

			session.addAssistantMessage(responseText)
			true
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			false
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
