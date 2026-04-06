package me.wanttobee.openblock.ai.sessions

import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.toolcalling.CommandToolsSupport
import me.wanttobee.openblock.ai.toolcalling.ToolManager
import me.wanttobee.openblock.sandbox.SandboxManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object AiSessionManager {
	private val sessionsByOwner = ConcurrentHashMap<UUID, PlayerSessions>()
	private val sessionsByScopeId = ConcurrentHashMap<UUID, Session>()
	private val currentSessionListeners = CopyOnWriteArrayList<(UUID, Session) -> Unit>()

	fun getSession(playerId: UUID): Result<Session> {
		val playerSessions = playerSessions(playerId)
		val selectedSessionId = playerSessions.selectedSessionId ?: ensureDraftSession(playerId, playerSessions).id
		val activeSession = playerSessions.activeSession
		if (activeSession?.id == selectedSessionId) {
			return Result.success(activeSession)
		}

		return loadSession(playerId, selectedSessionId).onSuccess { loadedSession ->
			if (playerSessions.selectedSessionId == selectedSessionId) {
				playerSessions.activeSession = loadedSession
				notifyCurrentSessionChanged(playerId, loadedSession)
			}
		}
	}

	fun getSelectedSessionId(playerId: UUID): Result<UUID> {
		val playerSessions = playerSessions(playerId)
		return Result.success(playerSessions.selectedSessionId ?: ensureDraftSession(playerId, playerSessions).id)
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
			storagePath = SessionLogger.playerStoragePath(playerId),
			systemPrompt = systemPrompt,
			boundPlayerId = if (bindPlayerId) playerId else null,
			persisted = false,
			initialEnabledToolNames = ToolManager.defaultEnabledToolNames(),
			initialAllowedCommandNames = CommandToolsSupport.defaultAllowedCommandNames(),
		)
		playerSessions.activeSession = session
		playerSessions.selectedSessionId = session.id
		registerSession(session)
		SandboxManager.bindSession(session.id, session.sandbox())
		return session
	}

	fun createStandaloneSession(
		storagePath: String,
		systemPrompt: String? = null,
		builtInPromptSections: List<String>,
	): Session {
		val session = Session(
			ownerPlayerId = null,
			storagePath = storagePath,
			systemPrompt = systemPrompt,
			boundPlayerId = null,
			toolScopeId = UUID.randomUUID(),
			builtInPromptSections = builtInPromptSections,
			persisted = false,
			initialEnabledToolNames = ToolManager.defaultEnabledToolNames(),
			initialAllowedCommandNames = CommandToolsSupport.defaultAllowedCommandNames(),
		)
		registerSession(session)
		SandboxManager.bindSession(session.id, session.sandbox())
		return session
	}

	fun clearSession(playerId: UUID): Boolean {
		val playerSessions = playerSessions(playerId)
		val previousSessionId = playerSessions.selectedSessionId
		val draftSession = createDraftSession(playerId)
		playerSessions.selectedSessionId = draftSession.id
		playerSessions.activeSession = draftSession
		if (previousSessionId != null) {
			SandboxManager.unbindSession(previousSessionId)
		}
		previousSessionId?.let(sessionsByScopeId::remove)
		SandboxManager.bindSession(draftSession.id, draftSession.sandbox())
		registerSession(draftSession)
		notifyCurrentSessionChanged(playerId, draftSession)
		return true
	}

	fun selectSession(playerId: UUID, sessionId: UUID): Result<Session> {
		val playerSessions = playerSessions(playerId)
		return loadSession(playerId, sessionId).onSuccess { loadedSession ->
			playerSessions.activeSession = loadedSession
			playerSessions.selectedSessionId = sessionId
			notifyCurrentSessionChanged(playerId, loadedSession)
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
			SessionLogger.loadSession(playerId, sessionId).onSuccess { loadedSession ->
				registerSession(loadedSession)
				SandboxManager.bindSession(loadedSession.id, loadedSession.sandbox())
			}
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
			sessionsByScopeId.remove(sessionId)
			if (playerSessions.selectedSessionId == sessionId) {
				val draftSession = createDraftSession(playerId)
				playerSessions.selectedSessionId = draftSession.id
				playerSessions.activeSession = draftSession
				SandboxManager.bindSession(draftSession.id, draftSession.sandbox())
				registerSession(draftSession)
				notifyCurrentSessionChanged(playerId, draftSession)
			}
			SandboxManager.unbindSession(sessionId)
		}
	}

	internal fun updateSession(session: Session) {
		session.ownerPlayerId?.let { ownerPlayerId ->
			val playerSessions = playerSessions(ownerPlayerId)
			playerSessions.activeSession = session
			playerSessions.selectedSessionId = session.id
			if (session.isPersisted()) {
				cacheSummary(playerSessions, session.summary(), addToFront = false)
			}
		}
		registerSession(session)
		SandboxManager.bindSession(session.id, session.sandbox())
	}

	fun findSessionForScope(scopeId: UUID): Session? {
		return sessionsByScopeId[scopeId]
			?: sessionsByOwner[scopeId]?.activeSession
	}

	fun sessionForScope(scopeId: UUID): Result<Session> {
		return findSessionForScope(scopeId)?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Unknown session scope: $scopeId"))
	}

	fun releaseSession(session: Session) {
		sessionsByScopeId.remove(session.toolScopeId, session)
		SandboxManager.unbindSession(session.id)
	}

	private fun playerSessions(playerId: UUID): PlayerSessions {
		return sessionsByOwner.computeIfAbsent(playerId) { ownerId ->
			PlayerSessions().also { playerSessions ->
				for (summary in SessionLogger.listSessionSummaries(ownerId).getOrElse { emptyList() }) {
					cacheSummary(playerSessions, summary, addToFront = false)
				}
				val draftSession = createDraftSession(ownerId)
				playerSessions.selectedSessionId = draftSession.id
				playerSessions.activeSession = draftSession
				registerSession(draftSession)
			}
		}
	}

	private fun ensureDraftSession(playerId: UUID, playerSessions: PlayerSessions): Session {
		val draftSession = playerSessions.activeSession ?: createDraftSession(playerId)
		playerSessions.activeSession = draftSession
		playerSessions.selectedSessionId = draftSession.id
		SandboxManager.bindSession(draftSession.id, draftSession.sandbox())
		registerSession(draftSession)
		notifyCurrentSessionChanged(playerId, draftSession)
		return draftSession
	}

	private fun createDraftSession(playerId: UUID): Session {
		return Session(
			ownerPlayerId = playerId,
			storagePath = SessionLogger.playerStoragePath(playerId),
			systemPrompt = null,
			boundPlayerId = playerId,
			persisted = false,
			initialEnabledToolNames = ToolManager.defaultEnabledToolNames(),
			initialAllowedCommandNames = CommandToolsSupport.defaultAllowedCommandNames(),
		)
	}

	private fun registerSession(session: Session) {
		sessionsByScopeId[session.toolScopeId] = session
	}

	fun ownersWithSelectedSession(sessionId: UUID): List<UUID> {
		return sessionsByOwner.entries
			.filter { (_, sessions) -> sessions.selectedSessionId == sessionId }
			.map { entry -> entry.key }
	}

	fun subscribeCurrentSessionChanges(listener: (UUID, Session) -> Unit) {
		currentSessionListeners += listener
	}

	private fun notifyCurrentSessionChanged(playerId: UUID, session: Session) {
		for (listener in currentSessionListeners) {
			listener(playerId, session)
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
