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
import me.wanttobee.openblock.sandbox.SandboxManager
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

object AiService {
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

	fun sendMessage(
		playerId: UUID,
		message: String,
		onActionChange: (String) -> Unit = {},
		onMessageAdded: (SessionMessage) -> Unit = {},
	): Pair<AiTargetManager.AiTarget, List<SessionMessage>>? {
		val target = currentTarget(playerId).getOrElse { return null }
		val session = currentSession(playerId).getOrNull() ?: AiSessionManager.createSession(
			playerId = playerId,
			systemPrompt = KnowledgeBase.OPENBLOCK_IDENTITY + KnowledgeBase.REDSTONE_DIRECTION_DETAILS,
			bindPlayerId = true,
		)
		session.addUserMessage(message)
		val messageCountBeforeGenerate = session.messages().size
		val generationResult = try {
			target.provider.generate(target.model, session, onActionChange, onMessageAdded)
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			Result.failure(exception)
		}
		val succeeded = generationResult.isSuccess
		val newMessages = session.messages()
			.drop(messageCountBeforeGenerate)
			.filter { it.type != SessionMessage.Type.USER && it.type != SessionMessage.Type.TOOL }
		return if (succeeded && newMessages.isNotEmpty()) {
			target to newMessages
		} else if (newMessages.isNotEmpty()) {
			target to newMessages
		} else {
			session.addErrorMessage("No response message was appended to the session.")
			target to listOfNotNull(session.lastMessage().getOrNull())
		}
	}

	fun clearSession(playerId: UUID): Boolean = AiSessionManager.clearSession(playerId)

	fun currentSandbox(playerId: UUID): Result<Sandbox> = SandboxManager.getSandbox(playerId)

	fun setSandbox(
		playerId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Sandbox = SandboxManager.setSandbox(playerId, dimension, firstCorner, secondCorner)

	fun clearSandbox(playerId: UUID): Result<Sandbox> = SandboxManager.clearSandbox(playerId)

	fun allTools(): List<AiTool> = ToolManager.allTools()

	fun isToolEnabled(playerId: UUID, toolName: String): Boolean = ToolManager.isEnabled(playerId, toolName)

	fun setToolEnabled(playerId: UUID, toolName: String, enabled: Boolean): Boolean {
		return ToolManager.setEnabled(playerId, toolName, enabled)
	}

	fun executeTool(playerId: UUID?, toolName: String, arguments: Map<String, String>): Result<AiToolExecution> {
		return ToolManager.execute(playerId, toolName, arguments)
	}
}
