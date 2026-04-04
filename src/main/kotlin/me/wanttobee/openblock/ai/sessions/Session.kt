package me.wanttobee.openblock.ai.sessions

import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.sandbox.SandboxManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class Session(
	val id: UUID = UUID.randomUUID(),
	val ownerPlayerId: UUID,
	val systemPrompt: String? = null,
	val boundPlayerId: UUID? = null,
) {
	private val messages = CopyOnWriteArrayList<SessionMessage>()
	private var lastSandboxUpdateVersion: Long = 0L

	fun messages(): List<SessionMessage> = messages.toList()
	fun lastMessage(): Result<SessionMessage> {
		return messages.lastOrNull()?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Session has no messages."))
	}
	fun userMessageCount(): Int = messages.count { it.type == SessionMessage.Type.USER }
	fun firstUserMessage(): Result<String> {
		return messages.firstOrNull { it.type == SessionMessage.Type.USER }?.content?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Session has no user messages."))
	}

	fun lastResponseProviderName(): String? {
		return messages.lastOrNull { message ->
			message.type == SessionMessage.Type.ASSISTANT && !message.providerName.isNullOrBlank()
		}?.providerName
	}

	fun summary(): SessionSummary {
		return SessionSummary(
			id = id,
			ownerPlayerId = ownerPlayerId,
			boundPlayerId = boundPlayerId,
			systemPrompt = systemPrompt,
			userMessageCount = userMessageCount(),
			lastResponseProviderName = lastResponseProviderName(),
		)
	}
	fun effectiveSystemPrompt(): Result<String> {
		val basePrompt = systemPrompt?.trim().orEmpty()
		if (boundPlayerId == null) {
			return basePrompt.takeIf { it.isNotBlank() }?.let(Result.Companion::success)
				?: Result.failure(NoSuchElementException("Session has no effective system prompt."))
		}

		val username = PlayerContextCapturer.capture(boundPlayerId).getOrNull()?.username
		val bindingPrompt =
			"Session binding: this conversation is with player UUID $boundPlayerId" +
				(username?.let { " (username: $it)" } ?: "") +
				".\n" +
				"User messages may begin with live player context in the form " +
				"[game mode - pos(x, y, z)/fac(yaw, pitch) - dimension].\n" +
				"If the player is in survival or adventure mode, the prefix may also include " +
				"hp(...), xp(...), and hunger(...).\n" +
				"Treat that prefix as authoritative context about the player speaking to you."

		val prompt = listOf(basePrompt, bindingPrompt)
			.filter { it.isNotBlank() }
			.joinToString("\n\n")
		return prompt.takeIf { it.isNotBlank() }?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Session has no effective system prompt."))
	}

	fun addUserMessage(content: String) {
		val hiddenParts = mutableListOf<String>()
		boundPlayerId
			?.let { playerId -> PlayerContextCapturer.capture(playerId).getOrNull() }
			?.let { context -> hiddenParts += context.promptPrefix() }
		boundPlayerId
			?.let { playerId -> SandboxManager.latestUpdate(playerId).getOrNull() }
			?.takeIf { update -> update.version > lastSandboxUpdateVersion }
			?.let { update ->
				hiddenParts += update.description
				lastSandboxUpdateVersion = update.version
			}
		val hiddenContent = hiddenParts
			.filter { part -> part.isNotBlank() }
			.joinToString("\n")
			.ifBlank { null }
		val message = SessionMessage(
			type = SessionMessage.Type.USER,
			content = content,
			hiddenContent = hiddenContent,
		)
		messages += message
		SessionLogger.logMessage(this, message)
		AiSessionManager.updateSession(this)
	}

	fun addAssistantMessage(
		content: String,
		usage: SessionTokenUsage? = null,
		providerName: String? = null,
		modelName: String? = null,
	) {
		val message = SessionMessage(
			type = SessionMessage.Type.ASSISTANT,
			content = content,
			usage = usage,
			providerName = providerName,
			modelName = modelName,
		)
		messages += message
		SessionLogger.logMessage(this, message)
		AiSessionManager.updateSession(this)
	}

	fun addToolMessage(content: String) {
		val message = SessionMessage(SessionMessage.Type.TOOL, content)
		messages += message
		SessionLogger.logMessage(this, message)
		AiSessionManager.updateSession(this)
	}

	fun addErrorMessage(
		content: String,
		usage: SessionTokenUsage? = null,
		providerName: String? = null,
		modelName: String? = null,
	) {
		val message = SessionMessage(
			type = SessionMessage.Type.ERROR,
			content = content,
			usage = usage,
			providerName = providerName,
			modelName = modelName,
		)
		messages += message
		SessionLogger.logMessage(this, message)
		AiSessionManager.updateSession(this)
	}

	fun appendPersistedMessage(message: SessionMessage) {
		messages += message
	}

	fun recordProviderCall(
		provider: String,
		model: String,
		usage: SessionTokenUsage? = null,
		finishReason: String? = null,
	) {
		SessionLogger.logProviderCall(this, provider, model, usage, finishReason)
	}

	fun recordToolInvocation(
		toolName: String,
		arguments: Map<String, String>,
		result: AiToolExecution,
		conversationMessage: String? = null,
	) {
		SessionLogger.logToolInvocation(this, toolName, arguments, result, conversationMessage)
	}

}
