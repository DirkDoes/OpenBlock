package me.wanttobee.mineai.ai

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AiSessionManager {
	private val sessions = ConcurrentHashMap<UUID, Session>()

	fun currentSession(playerId: UUID): Session? = sessions[playerId]

	fun getOrCreateSession(playerId: UUID): Session {
		return sessions.computeIfAbsent(playerId) { Session() }
	}

	fun clearSession(playerId: UUID): Boolean {
		return sessions.remove(playerId) != null
	}
}
