package me.wanttobee.openblock.ai

import me.wanttobee.openblock.ai.context.KnowledgeBase
import me.wanttobee.openblock.ai.providers.AiProvider
import me.wanttobee.openblock.ai.sessions.AiSessionManager
import me.wanttobee.openblock.ai.sessions.AiTargetManager
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.ToolManager
import me.wanttobee.openblock.sandbox.Sandbox
import me.wanttobee.openblock.sandbox.SandboxFloorBuilder
import me.wanttobee.openblock.sandbox.SandboxManager
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.UUID

object AiService {
	private const val INTERRUPT_PROMPT = "stop"
	private const val INTERRUPT_NOTE =
		"Conversation note: the user interrupted the previous AI response. Any tool calls that had already started may still finish, but the interrupted response must not continue from that point."

	fun pingProviders(): List<Result<AiProvider>> {
		return Providers.all.map { provider ->
			runCatching {
				provider.ping()
				provider
			}
		}
	}

	fun currentTarget(playerId: UUID): Result<AiTargetManager.AiTarget> = AiTargetManager.currentTarget(playerId)

	fun currentSession(playerId: UUID): Result<Session> = AiSessionManager.getSession(playerId)
	fun currentSessionSummary(playerId: UUID): SessionSummary? = AiSessionManager.getSelectedSessionSummary(playerId)
	fun currentSessionId(playerId: UUID): Result<UUID> = AiSessionManager.getSelectedSessionId(playerId)
	fun allSessions(playerId: UUID): List<SessionSummary> = AiSessionManager.allSessions(playerId)
	fun selectSession(playerId: UUID, sessionId: UUID): Result<Session> = AiSessionManager.selectSession(playerId, sessionId)
	fun loadSession(playerId: UUID, sessionId: UUID): Result<Session> = AiSessionManager.loadSession(playerId, sessionId)
	fun deleteSession(playerId: UUID, sessionId: UUID): Result<Unit> = AiSessionManager.deleteSession(playerId, sessionId)

	fun selectTarget(
		playerId: UUID,
		providerName: String,
		modelId: String?,
		reasoningValue: String? = null,
	): Result<AiTargetManager.AiTarget> = AiTargetManager.selectTarget(playerId, providerName, modelId, reasoningValue)

	fun isInterruptPrompt(message: String): Boolean {
		return message.trim().equals(INTERRUPT_PROMPT, ignoreCase = true)
	}

	fun interruptCurrentGeneration(playerId: UUID, message: String): Result<Boolean> {
		val session = currentSession(playerId).getOrElse { return Result.failure(it) }
		val interruptedCount = session.interruptActiveGenerations()
		if (interruptedCount <= 0) {
			return Result.success(false)
		}

		session.addUserMessage(message, supplementalHiddenContent = INTERRUPT_NOTE)
		return Result.success(true)
	}

	fun sendMessage(
		playerId: UUID,
		message: String,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit = { _, _ -> },
		onMessageAdded: (SessionMessage) -> Unit = {},
	): Pair<AiTargetManager.AiTarget, List<SessionMessage>>? {
		val target = currentTarget(playerId).getOrElse { return null }
		val session = currentSession(playerId).getOrElse { return null }
		val generationId = session.beginGeneration()
		session.addUserMessage(message)
		val messageCountBeforeGenerate = session.messages().size
		val generationResult = runCatching {
			target.provider.generate(target.model, session, generationId, onActionChange, onMessageAdded)
		}.fold(
			onSuccess = { it },
			onFailure = { exception ->
				session.addErrorMessage(exception.message ?: "Unknown error")
				Result.failure(exception)
			},
		).also {
			session.finishGeneration(generationId)
		}
		val completedNormally = generationResult.getOrNull() == true
		val interrupted = generationResult.getOrNull() == false
		val newMessages = session.messages()
			.drop(messageCountBeforeGenerate)
			.filter { it.type != SessionMessage.Type.USER && it.type != SessionMessage.Type.TOOL }
		return if (completedNormally && newMessages.isNotEmpty()) {
			target to newMessages
		} else if (interrupted) {
			target to emptyList()
		} else if (newMessages.isNotEmpty()) {
			target to newMessages
		} else {
			session.addErrorMessage("No response message was appended to the session.")
			target to listOfNotNull(session.lastMessage().getOrNull())
		}
	}

	fun clearSession(playerId: UUID): Boolean = AiSessionManager.clearSession(playerId)

	fun currentSandbox(playerId: UUID): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return session.sandbox()?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("No active sandbox."))
	}

	fun setSandbox(
		playerId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.setSandbox(session.id, dimension, firstCorner, secondCorner)
			.onSuccess(session::updateSandboxState)
	}

	fun addSandboxExclusion(
		playerId: UUID,
		dimension: ResourceKey<Level>,
		name: String,
		position: BlockPos,
	): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.addExclusion(session.id, dimension, name, position)
			.onSuccess(session::updateSandboxState)
	}

	fun removeSandboxExclusion(playerId: UUID, name: String): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.removeExclusion(session.id, name)
			.onSuccess(session::updateSandboxState)
	}

	fun clearSandboxExclusions(playerId: UUID): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.clearExclusions(session.id)
			.onSuccess(session::updateSandboxState)
	}

	fun addSandboxTarget(
		playerId: UUID,
		dimension: ResourceKey<Level>,
		name: String,
		position: BlockPos,
	): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.addTarget(session.id, dimension, name, position)
			.onSuccess(session::updateSandboxState)
	}

	fun removeSandboxTarget(playerId: UUID, name: String): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.removeTarget(session.id, name)
			.onSuccess(session::updateSandboxState)
	}

	fun clearSandboxTargets(playerId: UUID): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.clearTargets(session.id)
			.onSuccess(session::updateSandboxState)
	}

	fun clearSandbox(playerId: UUID): Result<Sandbox> {
		val session = AiSessionManager.getSession(playerId).getOrElse { return Result.failure(it) }
		return SandboxManager.clearSandbox(session.id)
			.onSuccess { session.updateSandboxState(null) }
	}

	fun placeSandboxFloor(playerId: UUID, level: ServerLevel): Result<SandboxFloorBuilder.PlacementSummary> {
		return currentSandbox(playerId).mapCatching { sandbox ->
			SandboxFloorBuilder.placeFloor(level, sandbox).getOrThrow()
		}
	}

	fun sandboxExclusionNames(playerId: UUID): Result<List<String>> {
		return currentSandbox(playerId).map { sandbox ->
			sandbox.exclusions.keys.sorted()
		}
	}

	fun sandboxTargetNames(playerId: UUID): Result<List<String>> {
		return currentSandbox(playerId).map { sandbox ->
			sandbox.targets.keys.sorted()
		}
	}

	fun allTools(): List<AiTool> = ToolManager.allTools()

	fun isToolEnabled(playerId: UUID, toolName: String): Boolean = ToolManager.isEnabled(playerId, toolName)

	fun setToolEnabled(playerId: UUID, toolName: String, enabled: Boolean): Boolean {
		return ToolManager.setEnabled(playerId, toolName, enabled)
	}

	fun executeTool(playerId: UUID?, toolName: String, arguments: Map<String, String>): Result<AiToolExecution> {
		return ToolManager.execute(playerId, toolName, arguments)
	}
}
