package me.wanttobee.mineai.ai

import me.wanttobee.mineai.ai.providers.AiProvider
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

	fun currentTarget(playerId: UUID): AiTarget? = AiTargetManager.currentTarget(playerId)

	fun selectTarget(playerId: UUID, providerName: String, modelId: String?): AiTarget? =
		AiTargetManager.selectTarget(playerId, providerName, modelId)

	fun sendMessage(playerId: UUID, message: String): Pair<AiTarget, Session.Message>? {
		val target = currentTarget(playerId) ?: return null
		val session = AiSessionManager.getOrCreateSession(playerId)
		session.addUserMessage(message)
		val succeeded = try {
			target.provider.generateResponse(target.model, session)
		} catch (exception: Exception) {
			session.addErrorMessage(exception.message ?: "Unknown error")
			false
		}
		val lastMessage = session.lastMessage()
		return if (succeeded && lastMessage != null && lastMessage.type != Session.Message.Type.USER) {
			target to lastMessage
		} else if (lastMessage != null && lastMessage.type != Session.Message.Type.USER) {
			target to lastMessage
		} else {
			session.addErrorMessage("No response message was appended to the session.")
			target to session.lastMessage()!!
		}
	}

	fun clearSession(playerId: UUID): Boolean = AiSessionManager.clearSession(playerId)
}
