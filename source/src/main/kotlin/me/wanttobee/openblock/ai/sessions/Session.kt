package me.wanttobee.openblock.ai.sessions

import me.wanttobee.openblock.ai.context.KnowledgeBase
import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.sandbox.Sandbox
import me.wanttobee.openblock.sandbox.SandboxManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class Session(
	val id: UUID = UUID.randomUUID(),
	val ownerPlayerId: UUID?,
	val storagePath: String,
	val systemPrompt: String? = null,
	val boundPlayerId: UUID? = null,
	val toolScopeId: UUID = id,
	val builtInPromptSections: List<String> = listOf(
		KnowledgeBase.OPENBLOCK_IDENTITY,
		KnowledgeBase.REDSTONE_DIRECTION_DETAILS,
	),
	private var persisted: Boolean = false,
	initialEnabledToolNames: Set<String> = emptySet(),
	initialAllowedCommandNames: Set<String> = emptySet(),
) {
	private val messages = CopyOnWriteArrayList<SessionMessage>()
	private var sandbox: Sandbox? = null
	private var lastSandboxUpdateVersion: Long = 0L
	private val enabledToolNames = initialEnabledToolNames.toMutableSet()
	private val allowedCommandNames = initialAllowedCommandNames.toMutableSet()
	private var nextGenerationId: Long = 1L
	private val activeGenerationIds = linkedSetOf<Long>()
	private val interruptedGenerationIds = linkedSetOf<Long>()
	private val generationStartedAtMillis = linkedMapOf<Long, Long>()

	fun messages(): List<SessionMessage> = messages.toList()
	fun sandbox(): Sandbox? = sandbox
	fun isPersisted(): Boolean = persisted
	fun enabledToolNames(): Set<String> = enabledToolNames.toSet()
	fun allowedCommandNames(): Set<String> = allowedCommandNames.toSet()
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
			inputTokens = 0,
			outputTokens = 0,
			cachedInputTokens = 0,
			reasoningTokens = 0,
			estimatedCost = 0.0,
		)
	}

	fun estimatedCost(): Result<Double> {
		return SessionLogger.estimatedCost(storagePath, id)
	}
	fun effectiveSystemPrompt(): Result<String> {
		val basePrompt = systemPrompt?.trim().orEmpty()
		val builtInPrompt = builtInPromptSections
			.filter(String::isNotBlank)
			.joinToString("\n\n")
		if (boundPlayerId == null) {
			val prompt = listOf(builtInPrompt, basePrompt)
				.filter { it.isNotBlank() }
				.joinToString("\n\n")
			return prompt.takeIf { it.isNotBlank() }?.let(Result.Companion::success)
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

		val prompt = listOf(builtInPrompt, basePrompt, bindingPrompt)
			.filter { it.isNotBlank() }
			.joinToString("\n\n")
		return prompt.takeIf { it.isNotBlank() }?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Session has no effective system prompt."))
	}

	fun addUserMessage(content: String, supplementalHiddenContent: String? = null) {
		if (!persisted) {
			persisted = true
			SessionLogger.logSessionStarted(this)
			persistState()
		}
		val hiddenParts = mutableListOf<String>()
		boundPlayerId
			?.let { playerId -> PlayerContextCapturer.capture(playerId).getOrNull() }
			?.let { context -> hiddenParts += context.promptPrefix() }
		SandboxManager.latestUpdate(id).getOrNull()
			?.takeIf { update -> update.version > lastSandboxUpdateVersion }
			?.let { update ->
				hiddenParts += update.description
				lastSandboxUpdateVersion = update.version
			}
		supplementalHiddenContent
			?.takeIf(String::isNotBlank)
			?.let(hiddenParts::add)
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
		persistState()
	}

	@Synchronized
	fun beginGeneration(): Long {
		val generationId = nextGenerationId++
		activeGenerationIds += generationId
		generationStartedAtMillis[generationId] = System.currentTimeMillis()
		return generationId
	}

	@Synchronized
	fun interruptActiveGenerations(): Int {
		interruptedGenerationIds += activeGenerationIds
		return activeGenerationIds.size
	}

	@Synchronized
	fun isGenerationInterrupted(generationId: Long): Boolean {
		return generationId in interruptedGenerationIds
	}

	@Synchronized
	fun finishGeneration(generationId: Long) {
		activeGenerationIds -= generationId
		interruptedGenerationIds -= generationId
		generationStartedAtMillis -= generationId
	}

	fun addAssistantMessage(
		content: String,
		usage: SessionTokenUsage? = null,
		providerName: String? = null,
		modelName: String? = null,
		generationId: Long? = null,
	) {
		val message = SessionMessage(
			type = SessionMessage.Type.ASSISTANT,
			content = content,
			usage = usage,
			providerName = providerName,
			modelName = modelName,
			generationDurationMillis = generationDurationMillis(generationId),
		)
		messages += message
		SessionLogger.logMessage(this, message)
		persistState()
	}

	fun addToolMessage(content: String) {
		val message = SessionMessage(SessionMessage.Type.TOOL, content)
		messages += message
		SessionLogger.logMessage(this, message)
		persistState()
	}

	fun addErrorMessage(
		content: String,
		usage: SessionTokenUsage? = null,
		providerName: String? = null,
		modelName: String? = null,
		generationId: Long? = null,
	) {
		val message = SessionMessage(
			type = SessionMessage.Type.ERROR,
			content = content,
			usage = usage,
			providerName = providerName,
			modelName = modelName,
			generationDurationMillis = generationDurationMillis(generationId),
		)
		messages += message
		SessionLogger.logMessage(this, message)
		persistState()
	}

	fun appendPersistedMessage(message: SessionMessage) {
		messages += message
	}

	internal fun restoreSandboxState(sandbox: Sandbox?) {
		this.sandbox = sandbox
	}

	internal fun updateSandboxState(sandbox: Sandbox?) {
		this.sandbox = sandbox
		persistState()
	}

	internal fun restoreToolState(enabledToolNames: Set<String>) {
		this.enabledToolNames.clear()
		this.enabledToolNames += enabledToolNames
	}

	internal fun updateToolState(toolName: String, enabled: Boolean) {
		if (enabled) {
			enabledToolNames += toolName
		} else {
			enabledToolNames -= toolName
		}
		persistState()
	}

	internal fun restoreCommandState(allowedCommandNames: Set<String>) {
		this.allowedCommandNames.clear()
		this.allowedCommandNames += allowedCommandNames
	}

	internal fun updateCommandState(commandName: String, allowed: Boolean) {
		if (allowed) {
			allowedCommandNames += commandName
		} else {
			allowedCommandNames -= commandName
		}
		persistState()
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

	private fun persistState() {
		AiSessionManager.updateSession(this)
	}

	@Synchronized
	private fun generationDurationMillis(generationId: Long?): Long? {
		val startedAt = generationId?.let(generationStartedAtMillis::get) ?: return null
		return (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
	}

}
