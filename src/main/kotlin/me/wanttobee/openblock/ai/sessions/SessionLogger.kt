package me.wanttobee.openblock.ai.sessions

import com.google.gson.GsonBuilder
import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.toolcalling.AiTool
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SessionLogger {
	private const val LOG_DIR = "openblock/sessions"
	private val gson = GsonBuilder()
		.setPrettyPrinting()
		.serializeNulls()
		.create()
	private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
	private val logsBySession = ConcurrentHashMap<UUID, SessionLog>()

	fun logSessionStarted(session: Session) {
		update(session) { log ->
			log.events += LogEvent(
				type = "session_started",
				timestamp = timestamp(),
			)
		}
	}

	fun logSessionClosed(session: Session, reason: String) {
		update(session) { log ->
			log.closedAt = timestamp()
			log.closeReason = reason
			log.events += LogEvent(
				type = "session_closed",
				timestamp = log.closedAt!!,
				reason = reason,
				totalsAfter = log.totals.copy(),
			)
		}
	}

	fun logMessage(session: Session, message: Session.Message) {
		update(session) { log ->
			log.events += LogEvent(
				type = "message",
				timestamp = timestamp(),
				messageType = message.type.name.lowercase(),
				content = message.content,
				hiddenContext = message.hiddenContent,
				usage = message.usage,
				totalsAfter = log.totals.copy(),
			)
		}
	}

	fun logProviderCall(
		session: Session,
		provider: String,
		model: String,
		usage: Session.TokenUsage?,
		finishReason: String?,
	) {
		update(session) { log ->
			log.totals.add(usage)
			log.events += LogEvent(
				type = "provider_call",
				timestamp = timestamp(),
				provider = provider,
				model = model,
				finishReason = finishReason,
				usage = usage,
				totalsAfter = log.totals.copy(),
			)
		}
	}

	fun logToolInvocation(
		session: Session,
		toolName: String,
		arguments: Map<String, String>,
		result: AiTool.ExecutionResult,
		conversationMessage: String?,
	) {
		update(session) { log ->
			log.events += LogEvent(
				type = "tool_invocation",
				timestamp = timestamp(),
				toolName = toolName,
				toolArguments = arguments.toSortedMap(),
				toolResult = result.asResponseMap(),
				conversationMessage = conversationMessage,
				totalsAfter = log.totals.copy(),
			)
		}
	}

	@Synchronized
	private fun update(session: Session, mutate: (SessionLog) -> Unit) {
		val log = logsBySession.computeIfAbsent(session.id) { createLog(session) }
		mutate(log)
		write(session, log)
	}

	private fun createLog(session: Session): SessionLog {
		return SessionLog(
			version = 1,
			sessionId = session.id.toString(),
			boundPlayerId = session.boundPlayerId?.toString(),
			systemPrompt = session.systemPrompt,
			startedAt = timestamp(),
			totals = TokenTotals(),
			events = mutableListOf(),
		)
	}

	private fun write(session: Session, log: SessionLog) {
		val server = PlayerContextCapturer.currentServer() ?: return
		val directory = server.getFile(LOG_DIR)
		Files.createDirectories(directory)
		Files.writeString(
			logFile(directory, session),
			gson.toJson(log) + "\n",
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE,
		)
	}

	private fun logFile(directory: Path, session: Session): Path {
		return directory.resolve("${session.id}.json")
	}

	private fun timestamp(): String = OffsetDateTime.now().format(timestampFormatter)

	private data class SessionLog(
		val version: Int,
		val sessionId: String,
		val boundPlayerId: String?,
		val systemPrompt: String?,
		val startedAt: String,
		var closedAt: String? = null,
		var closeReason: String? = null,
		val totals: TokenTotals,
		val events: MutableList<LogEvent>,
	)

	private data class LogEvent(
		val type: String,
		val timestamp: String,
		val messageType: String? = null,
		val content: String? = null,
		val hiddenContext: String? = null,
		val provider: String? = null,
		val model: String? = null,
		val finishReason: String? = null,
		val toolName: String? = null,
		val toolArguments: Map<String, String>? = null,
		val toolResult: Map<String, Any?>? = null,
		val conversationMessage: String? = null,
		val usage: Session.TokenUsage? = null,
		val totalsAfter: TokenTotals? = null,
		val reason: String? = null,
	)

	private data class TokenTotals(
		var inputTokens: Long = 0,
		var outputTokens: Long = 0,
		var totalTokens: Long = 0,
		var cachedInputTokens: Long = 0,
		var cacheCreationInputTokens: Long = 0,
		var cacheReadInputTokens: Long = 0,
		var reasoningTokens: Long = 0,
		var thoughtsTokens: Long = 0,
		var toolUsePromptTokens: Long = 0,
	) {
		fun add(usage: Session.TokenUsage?) {
			if (usage == null) {
				return
			}
			inputTokens += usage.inputTokens ?: 0
			outputTokens += usage.outputTokens ?: 0
			totalTokens += usage.totalTokens ?: 0
			cachedInputTokens += usage.cachedInputTokens ?: 0
			cacheCreationInputTokens += usage.cacheCreationInputTokens ?: 0
			cacheReadInputTokens += usage.cacheReadInputTokens ?: 0
			reasoningTokens += usage.reasoningTokens ?: 0
			thoughtsTokens += usage.thoughtsTokens ?: 0
			toolUsePromptTokens += usage.toolUsePromptTokens ?: 0
		}
	}
}
