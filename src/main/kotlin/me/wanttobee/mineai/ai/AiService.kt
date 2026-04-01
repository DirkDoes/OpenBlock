package me.wanttobee.mineai.ai

import me.wanttobee.mineai.ai.context.KnowledgeBase
import me.wanttobee.mineai.ai.providers.AiProvider
import me.wanttobee.mineai.ai.sessions.AiSessionManager
import me.wanttobee.mineai.ai.sessions.Sandbox
import me.wanttobee.mineai.ai.sessions.SandboxManager
import me.wanttobee.mineai.ai.sessions.AiTargetManager
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.ToolManager
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

object AiService {
	fun pingProviders(): List<Pair<AiProvider, Exception?>> {
		return Providers.all.map { provider ->
			try {
				provider.ping()
				provider to null
			} catch (exception: Exception) {
				provider to exception
			}
		}
	}

	fun currentTarget(playerId: UUID): AiTargetManager.AiTarget? = AiTargetManager.currentTarget(playerId)

	fun currentSession(playerId: UUID): Session? = AiSessionManager.getSession(playerId)

	fun selectTarget(
		playerId: UUID,
		providerName: String,
		modelId: String?,
		reasoningValue: String? = null,
	): AiTargetManager.AiTarget? = AiTargetManager.selectTarget(playerId, providerName, modelId, reasoningValue)

	fun sendMessage(
		playerId: UUID,
		message: String,
		onActionChange: (String) -> Unit = {},
		onMessageAdded: (Session.Message) -> Unit = {},
	): Pair<AiTargetManager.AiTarget, List<Session.Message>>? {
		val target = currentTarget(playerId) ?: return null
		val session = AiSessionManager.getSession(playerId) ?: AiSessionManager.createSession(
			playerId = playerId,
			systemPrompt = KnowledgeBase.MINEAI_IDENTITY,
			bindPlayerId = true,
		)
		session.addUserMessage(message)
		val messageCountBeforeGenerate = session.messages().size
		val succeeded = try {
			target.provider.generate(target.model, session, onActionChange, onMessageAdded)
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			false
		}
		val newMessages = session.messages()
			.drop(messageCountBeforeGenerate)
			.filter { it.type != Session.Message.Type.USER && it.type != Session.Message.Type.TOOL }
		return if (succeeded && newMessages.isNotEmpty()) {
			target to newMessages
		} else if (newMessages.isNotEmpty()) {
			target to newMessages
		} else {
			session.addErrorMessage("No response message was appended to the session.")
			target to listOfNotNull(session.lastMessage())
		}
	}

	fun clearSession(playerId: UUID): Boolean = AiSessionManager.clearSession(playerId)

	fun currentSandbox(playerId: UUID): Sandbox? = SandboxManager.getSandbox(playerId)

	fun setSandbox(
		playerId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Sandbox = SandboxManager.setSandbox(playerId, dimension, firstCorner, secondCorner)

	fun clearSandbox(playerId: UUID): Sandbox? = SandboxManager.clearSandbox(playerId)

	fun allTools(): List<AiTool> = ToolManager.allTools()

	fun isToolEnabled(playerId: UUID, toolName: String): Boolean = ToolManager.isEnabled(playerId, toolName)

	fun setToolEnabled(playerId: UUID, toolName: String, enabled: Boolean): Boolean {
		return ToolManager.setEnabled(playerId, toolName, enabled)
	}

	fun executeTool(playerId: UUID?, toolName: String, arguments: Map<String, String>): AiTool.ExecutionResult? {
		return ToolManager.execute(playerId, toolName, arguments)
	}
}
