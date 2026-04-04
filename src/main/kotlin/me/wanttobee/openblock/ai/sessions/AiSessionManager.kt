package me.wanttobee.openblock.ai.sessions

import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object AiSessionManager {
	private val sessionsByOwner = ConcurrentHashMap<UUID, PlayerSessions>()

	fun getSession(playerId: UUID): Result<Session> {
		val playerSessions = playerSessions(playerId)
		val selectedSessionId = playerSessions.selectedSessionId
			?: return Result.failure(NoSuchElementException("No active session selected."))
		val activeSession = playerSessions.activeSession
		if (activeSession?.id == selectedSessionId) {
			return Result.success(activeSession)
		}

		return loadSession(playerId, selectedSessionId).onSuccess { loadedSession ->
			if (playerSessions.selectedSessionId == selectedSessionId) {
				playerSessions.activeSession = loadedSession
			}
		}
	}

	fun getSelectedSessionId(playerId: UUID): Result<UUID> {
		return playerSessions(playerId).selectedSessionId?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("No active session selected."))
	}

	fun getSelectedSessionSummary(playerId: UUID): SessionSummary? {
		val playerSessions = playerSessions(playerId)
		val selectedSessionId = playerSessions.selectedSessionId ?: return null
		return playerSessions.summaries[selectedSessionId]
	}

	fun allSessions(playerId: UUID): List<SessionSummary> {
		val playerSessions = playerSessions(playerId)
		return playerSessions.orderedSessionIds.mapNotNull(playerSessions.summaries::get)
	}

	fun createSession(
		playerId: UUID,
		systemPrompt: String? = null,
		bindPlayerId: Boolean = true,
	): Session {
		val playerSessions = playerSessions(playerId)
		val session = Session(
			ownerPlayerId = playerId,
			systemPrompt = systemPrompt,
			boundPlayerId = if (bindPlayerId) playerId else null,
		)
		playerSessions.activeSession = session
		playerSessions.selectedSessionId = session.id
		cacheSummary(playerSessions, session.summary(), addToFront = true)
		SessionLogger.logSessionStarted(session)
		return session
	}

	fun clearSession(playerId: UUID): Boolean {
		val playerSessions = playerSessions(playerId)
		val selectedSessionId = playerSessions.selectedSessionId ?: return false
		playerSessions.selectedSessionId = null
		playerSessions.activeSession = null
		return playerSessions.summaries.containsKey(selectedSessionId)
	}

	fun selectSession(playerId: UUID, sessionId: UUID): Result<Session> {
		val playerSessions = playerSessions(playerId)
		return loadSession(playerId, sessionId).onSuccess { loadedSession ->
			playerSessions.activeSession = loadedSession
			playerSessions.selectedSessionId = sessionId
		}
	}

	fun loadSession(playerId: UUID, sessionId: UUID): Result<Session> {
		val playerSessions = playerSessions(playerId)
		if (!playerSessions.summaries.containsKey(sessionId)) {
			return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
		}

		val activeSession = playerSessions.activeSession
		return if (activeSession?.id == sessionId) {
			Result.success(activeSession)
		} else {
			SessionLogger.loadSession(playerId, sessionId)
		}
	}

	fun deleteSession(playerId: UUID, sessionId: UUID): Result<Unit> {
		val playerSessions = playerSessions(playerId)
		if (!playerSessions.summaries.containsKey(sessionId)) {
			return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
		}

		return SessionLogger.deleteSession(playerId, sessionId).onSuccess {
			playerSessions.summaries.remove(sessionId)
			playerSessions.orderedSessionIds.remove(sessionId)
			if (playerSessions.selectedSessionId == sessionId) {
				playerSessions.selectedSessionId = null
			}
			if (playerSessions.activeSession?.id == sessionId) {
				playerSessions.activeSession = null
			}
		}
	}

	internal fun updateSession(session: Session) {
		val playerSessions = playerSessions(session.ownerPlayerId)
		playerSessions.activeSession = session
		playerSessions.selectedSessionId = session.id
		cacheSummary(playerSessions, session.summary(), addToFront = false)
	}

	private fun playerSessions(playerId: UUID): PlayerSessions {
		return sessionsByOwner.computeIfAbsent(playerId) { ownerId ->
			PlayerSessions().also { playerSessions ->
				for (summary in SessionLogger.listSessionSummaries(ownerId).getOrElse { emptyList() }) {
					cacheSummary(playerSessions, summary, addToFront = false)
				}
			}
		}
	}

	private fun cacheSummary(
		playerSessions: PlayerSessions,
		summary: SessionSummary,
		addToFront: Boolean,
	) {
		playerSessions.summaries[summary.id] = summary
		val alreadyPresent = playerSessions.orderedSessionIds.contains(summary.id)
		if (alreadyPresent && !addToFront) {
			return
		}
		playerSessions.orderedSessionIds.remove(summary.id)
		if (addToFront) {
			playerSessions.orderedSessionIds.add(0, summary.id)
		} else {
			playerSessions.orderedSessionIds.add(summary.id)
		}
	}

	private class PlayerSessions {
		val summaries = ConcurrentHashMap<UUID, SessionSummary>()
		val orderedSessionIds = CopyOnWriteArrayList<UUID>()
		@Volatile var selectedSessionId: UUID? = null
		@Volatile var activeSession: Session? = null
	}
}
