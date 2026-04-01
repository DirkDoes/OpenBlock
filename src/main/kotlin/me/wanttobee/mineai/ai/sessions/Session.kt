package me.wanttobee.mineai.ai.sessions

import me.wanttobee.mineai.ai.context.PlayerContextCapturer
import me.wanttobee.mineai.ai.toolcalling.AiTool
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class Session(
	val id: UUID = UUID.randomUUID(),
	val systemPrompt: String? = null,
	val boundPlayerId: UUID? = null,
) {
	private val messages = CopyOnWriteArrayList<Message>()
	private var lastSandboxUpdateVersion: Long = 0L

	fun messages(): List<Message> = messages.toList()
	fun lastMessage(): Message? = messages.lastOrNull()
	fun effectiveSystemPrompt(): String? {
		val basePrompt = systemPrompt?.trim().orEmpty()
		if (boundPlayerId == null) {
			return basePrompt.ifBlank { null }
		}

		val username = PlayerContextCapturer.capture(boundPlayerId)?.username
		val bindingPrompt =
			"Session binding: this conversation is with player UUID $boundPlayerId" +
				(username?.let { " (username: $it)" } ?: "") +
				".\n" +
				"User messages may begin with live player context in the form " +
				"[game mode - pos(x, y, z)/fac(yaw, pitch) - dimension].\n" +
				"If the player is in survival or adventure mode, the prefix may also include " +
				"hp(...), xp(...), and hunger(...).\n" +
				"Treat that prefix as authoritative context about the player speaking to you."

		return listOf(basePrompt, bindingPrompt)
			.filter { it.isNotBlank() }
			.joinToString("\n\n")
	}

	fun addUserMessage(content: String) {
		val hiddenParts = mutableListOf<String>()
		boundPlayerId
			?.let(PlayerContextCapturer::capture)
			?.let { context -> hiddenParts += context.promptPrefix() }
		boundPlayerId
			?.let(SandboxManager::latestUpdate)
			?.takeIf { update -> update.version > lastSandboxUpdateVersion }
			?.let { update ->
				hiddenParts += update.description
				lastSandboxUpdateVersion = update.version
			}
		val hiddenContent = hiddenParts
			.filter { part -> part.isNotBlank() }
			.joinToString("\n")
			.ifBlank { null }
		val message = Message(
			type = Message.Type.USER,
			content = content,
			hiddenContent = hiddenContent,
		)
		messages += message
		SessionLogger.logMessage(this, message)
	}

	fun addAssistantMessage(content: String, usage: TokenUsage? = null) {
		val message = Message(Message.Type.ASSISTANT, content, usage = usage)
		messages += message
		SessionLogger.logMessage(this, message)
	}

	fun addToolMessage(content: String) {
		val message = Message(Message.Type.TOOL, content)
		messages += message
		SessionLogger.logMessage(this, message)
	}

	fun addErrorMessage(content: String, usage: TokenUsage? = null) {
		val message = Message(Message.Type.ERROR, content, usage = usage)
		messages += message
		SessionLogger.logMessage(this, message)
	}

	fun recordProviderCall(
		provider: String,
		model: String,
		usage: TokenUsage? = null,
		finishReason: String? = null,
	) {
		SessionLogger.logProviderCall(this, provider, model, usage, finishReason)
	}

	fun recordToolInvocation(
		toolName: String,
		arguments: Map<String, String>,
		result: AiTool.ExecutionResult,
		conversationMessage: String? = null,
	) {
		SessionLogger.logToolInvocation(this, toolName, arguments, result, conversationMessage)
	}

	data class Message(
		val type: Type,
		val content: String,
		val hiddenContent: String? = null,
		val usage: TokenUsage? = null,
	) {
		fun combinedContent(): String {
			return hiddenContent?.takeIf { it.isNotBlank() }?.let { "$it\n$content" } ?: content
		}

		enum class Type {
			USER,
			TOOL,
			ASSISTANT,
			ERROR,
		}
	}

	data class TokenUsage(
		val inputTokens: Long? = null,
		val outputTokens: Long? = null,
		val totalTokens: Long? = null,
		val cachedInputTokens: Long? = null,
		val cacheCreationInputTokens: Long? = null,
		val cacheReadInputTokens: Long? = null,
		val reasoningTokens: Long? = null,
		val thoughtsTokens: Long? = null,
		val toolUsePromptTokens: Long? = null,
	)
}
