package me.wanttobee.mineai.ai

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AiSessionManager {
	private val sessions = ConcurrentHashMap<UUID, Session>()

	fun getSession(playerId: UUID): Session? = sessions[playerId]

	fun createSession(
		playerId: UUID,
		systemPrompt: String? = null,
		bindPlayerId: Boolean = true,
	): Session {
		val session = Session(
			systemPrompt = systemPrompt,
			boundPlayerId = if (bindPlayerId) playerId else null,
		)
		sessions[playerId] = session
		return session
	}

	fun clearSession(playerId: UUID): Boolean {
		return sessions.remove(playerId) != null
	}
}
