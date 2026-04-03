package me.wanttobee.openblock.ai.sessions

import com.google.gson.GsonBuilder
import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object SessionLogger {
	private const val LOG_DIR = "openblock/sessions"
	private val gson = GsonBuilder()
		.setPrettyPrinting()
		.serializeNulls()
		.create()
	private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

	fun logSessionStarted(session: Session) {
		update(session) { snapshot ->
			snapshot.messages = persistedMessages(session)
			snapshot.summary = persistedSummary(session.summary())
		}
	}

	fun logSessionClosed(session: Session, reason: String) {
		update(session) { snapshot ->
			snapshot.closedAt = timestamp()
			snapshot.closeReason = reason
		}
	}

	fun logMessage(session: Session, message: SessionMessage) {
		update(session) { snapshot ->
			snapshot.messages = persistedMessages(session)
			snapshot.summary = persistedSummary(session.summary())
		}
	}

	fun logProviderCall(
		session: Session,
		provider: String,
		model: String,
		usage: SessionTokenUsage?,
		finishReason: String?,
	) {
		update(session) { snapshot ->
			snapshot.providerCalls += ProviderCallEntry(
				timestamp = timestamp(),
				provider = provider,
				model = model,
				finishReason = finishReason,
				usage = usage,
			)
		}
	}

	fun logToolInvocation(
		session: Session,
		toolName: String,
		arguments: Map<String, String>,
		result: AiToolExecution,
		conversationMessage: String?,
	) {
		update(session) { snapshot ->
			snapshot.toolInvocations += ToolInvocationEntry(
				timestamp = timestamp(),
				toolName = toolName,
				toolArguments = arguments.toSortedMap(),
				toolResult = result.asResponseMap(),
				conversationMessage = conversationMessage,
			)
		}
	}

	fun listSessionSummaries(ownerPlayerId: UUID): Result<List<SessionSummary>> {
		val directory = ownerDirectory(ownerPlayerId)
			?: return Result.failure(IllegalStateException("Minecraft server is not available."))
		if (!Files.isDirectory(directory)) {
			return Result.success(emptyList())
		}

		return Result.success(Files.list(directory).use { files ->
			files
				.toList()
				.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".json") }
				.mapNotNull(::readSnapshot)
				.sortedByDescending(SessionSnapshot::updatedAt)
				.map { snapshot ->
					SessionSummary(
						id = UUID.fromString(snapshot.sessionId),
						ownerPlayerId = UUID.fromString(snapshot.ownerPlayerId),
						boundPlayerId = snapshot.boundPlayerId?.let(UUID::fromString),
						systemPrompt = snapshot.systemPrompt,
						userMessageCount = snapshot.summary.userMessageCount,
						firstUserMessage = snapshot.summary.firstUserMessage,
					)
				}
		})
	}

	fun loadSession(ownerPlayerId: UUID, sessionId: UUID): Result<Session> {
		val snapshot = readSnapshot(logFile(ownerPlayerId, sessionId))
			?: return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
		val session = Session(
			id = UUID.fromString(snapshot.sessionId),
			ownerPlayerId = UUID.fromString(snapshot.ownerPlayerId),
			systemPrompt = snapshot.systemPrompt,
			boundPlayerId = snapshot.boundPlayerId?.let(UUID::fromString),
		)
		for (message in snapshot.messages) {
			session.appendPersistedMessage(
				SessionMessage(
					type = SessionMessage.Type.valueOf(message.type),
					content = message.content,
					hiddenContent = message.hiddenContent,
					usage = message.usage,
				)
			)
		}
		return Result.success(session)
	}

	@Synchronized
	private fun update(session: Session, mutate: (SessionSnapshot) -> Unit) {
		val snapshot = readSnapshot(logFile(session.ownerPlayerId, session.id)) ?: createSnapshot(session)
		snapshot.ownerPlayerId = session.ownerPlayerId.toString()
		snapshot.boundPlayerId = session.boundPlayerId?.toString()
		snapshot.systemPrompt = session.systemPrompt
		snapshot.updatedAt = timestamp()
		mutate(snapshot)
		write(session.ownerPlayerId, session.id, snapshot)
	}

	private fun createSnapshot(session: Session): SessionSnapshot {
		val now = timestamp()
		return SessionSnapshot(
			version = 2,
			sessionId = session.id.toString(),
			ownerPlayerId = session.ownerPlayerId.toString(),
			boundPlayerId = session.boundPlayerId?.toString(),
			systemPrompt = session.systemPrompt,
			startedAt = now,
			updatedAt = now,
			summary = persistedSummary(session.summary()),
			messages = persistedMessages(session),
			providerCalls = mutableListOf(),
			toolInvocations = mutableListOf(),
		)
	}

	private fun persistedSummary(summary: SessionSummary): PersistedSummary {
		return PersistedSummary(
			userMessageCount = summary.userMessageCount,
			firstUserMessage = summary.firstUserMessage,
		)
	}

	private fun persistedMessages(session: Session): MutableList<PersistedMessage> {
		return session.messages().map { message ->
			PersistedMessage(
				type = message.type.name,
				content = message.content,
				hiddenContent = message.hiddenContent,
				usage = message.usage,
			)
		}.toMutableList()
	}

	private fun write(ownerPlayerId: UUID, sessionId: UUID, snapshot: SessionSnapshot) {
		val directory = ownerDirectory(ownerPlayerId) ?: return
		Files.createDirectories(directory)
		Files.writeString(
			logFile(directory, sessionId),
			gson.toJson(snapshot) + "\n",
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE,
		)
	}

	private fun readSnapshot(path: Path): SessionSnapshot? {
		return runCatching {
			if (!Files.exists(path)) {
				null
			} else {
				Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
					gson.fromJson(reader, SessionSnapshot::class.java)
				}
			}
		}.getOrNull()
	}

	private fun ownerDirectory(ownerPlayerId: UUID): Path? {
		val server = PlayerContextCapturer.currentServer().getOrNull() ?: return null
		return server.getFile(LOG_DIR).resolve(ownerPlayerId.toString())
	}

	private fun logFile(ownerPlayerId: UUID, sessionId: UUID): Path {
		return ownerDirectory(ownerPlayerId)?.let { directory -> logFile(directory, sessionId) }
			?: Path.of(LOG_DIR, ownerPlayerId.toString(), "$sessionId.json")
	}

	private fun logFile(directory: Path, sessionId: UUID): Path {
		return directory.resolve("$sessionId.json")
	}

	private fun timestamp(): String = OffsetDateTime.now().format(timestampFormatter)

	private data class SessionSnapshot(
		val version: Int,
		val sessionId: String,
		var ownerPlayerId: String,
		var boundPlayerId: String?,
		var systemPrompt: String?,
		val startedAt: String,
		var updatedAt: String,
		var closedAt: String? = null,
		var closeReason: String? = null,
		var summary: PersistedSummary,
		var messages: MutableList<PersistedMessage>,
		val providerCalls: MutableList<ProviderCallEntry>,
		val toolInvocations: MutableList<ToolInvocationEntry>,
	)

	private data class PersistedSummary(
		val userMessageCount: Int,
		val firstUserMessage: String?,
	)

	private data class PersistedMessage(
		val type: String,
		val content: String,
		val hiddenContent: String? = null,
		val usage: SessionTokenUsage? = null,
	)

	private data class ProviderCallEntry(
		val timestamp: String,
		val provider: String,
		val model: String,
		val finishReason: String? = null,
		val usage: SessionTokenUsage? = null,
	)

	private data class ToolInvocationEntry(
		val timestamp: String,
		val toolName: String,
		val toolArguments: Map<String, String>,
		val toolResult: Map<String, Any?>,
		val conversationMessage: String? = null,
	)
}
