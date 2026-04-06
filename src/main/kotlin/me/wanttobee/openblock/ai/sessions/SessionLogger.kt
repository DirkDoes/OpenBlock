package me.wanttobee.openblock.ai.sessions

import com.google.gson.GsonBuilder
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.sandbox.Sandbox
import me.wanttobee.openblock.sandbox.SandboxRegion
import com.google.gson.annotations.SerializedName
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object SessionLogger {
	private const val LOG_DIR = "openblock-data/sessions"
	private const val PLAYER_SCOPE_PREFIX = "player"
	private val namespacedIdPattern = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
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

	fun logSessionState(session: Session) {
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
		migrateLegacyPlayerSessions(ownerPlayerId).getOrElse { return Result.failure(it) }
		val directory = storageDirectory(playerStoragePath(ownerPlayerId))
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
						ownerPlayerId = snapshot.ownerPlayerId?.let(UUID::fromString),
						boundPlayerId = snapshot.boundPlayerId?.let(UUID::fromString),
						systemPrompt = snapshot.systemPrompt,
						userMessageCount = snapshot.summary.userMessageCount,
						lastResponseProviderName = snapshot.messages.lastOrNull { message ->
							message.type == SessionMessage.Type.ASSISTANT.name && !message.providerName.isNullOrBlank()
						}?.providerName,
					)
				}
		})
	}

	fun loadSession(ownerPlayerId: UUID, sessionId: UUID): Result<Session> {
		migrateLegacyPlayerSessions(ownerPlayerId).getOrElse { return Result.failure(it) }
		val snapshot = readSnapshot(logFile(playerStoragePath(ownerPlayerId), sessionId))
			?: return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
		return restoredSession(snapshot)
	}

	fun loadSession(storagePath: String, sessionId: UUID): Result<Session> {
		val snapshot = readSnapshot(logFile(storagePath, sessionId))
			?: return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
		return restoredSession(snapshot)
	}

	fun deleteSession(ownerPlayerId: UUID, sessionId: UUID): Result<Unit> {
		migrateLegacyPlayerSessions(ownerPlayerId).getOrElse { return Result.failure(it) }
		return deleteSession(playerStoragePath(ownerPlayerId), sessionId)
	}

	fun deleteSession(storagePath: String, sessionId: UUID): Result<Unit> {
		return runCatching {
			Files.deleteIfExists(logFile(storagePath, sessionId))
			Unit
		}
	}

	fun tokenTotals(storagePath: String, sessionId: UUID): Result<TokenTotals> {
		val snapshot = readSnapshot(logFile(storagePath, sessionId))
			?: return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
		return Result.success(
			TokenTotals(
				inputTokens = snapshot.providerCalls.sumOf { entry -> entry.usage.inputTokensOrZero() },
				outputTokens = snapshot.providerCalls.sumOf { entry -> entry.usage.expandedOutputTokensOrZero() },
				cachedTokens = snapshot.providerCalls.sumOf { entry -> entry.usage.cachedTokensOrZero() },
			)
		)
	}

	private fun restoredSession(snapshot: SessionSnapshot): Result<Session> {
		val sandbox = restoredSandbox(snapshot.sandbox).getOrElse { return Result.failure(it) }
		val ownerPlayerId = snapshot.ownerPlayerId?.let(UUID::fromString)
		val session = Session(
			id = UUID.fromString(snapshot.sessionId),
			ownerPlayerId = ownerPlayerId,
			storagePath = ownerPlayerId?.let(::playerStoragePath) ?: snapshot.storagePath,
			systemPrompt = snapshot.systemPrompt,
			boundPlayerId = snapshot.boundPlayerId?.let(UUID::fromString),
			toolScopeId = snapshot.toolScopeId?.let(UUID::fromString) ?: UUID.fromString(snapshot.sessionId),
			builtInPromptSections = snapshot.builtInPromptSections.ifEmpty {
				listOf(
					me.wanttobee.openblock.ai.context.KnowledgeBase.OPENBLOCK_IDENTITY,
					me.wanttobee.openblock.ai.context.KnowledgeBase.REDSTONE_DIRECTION_DETAILS,
				)
			},
			persisted = true,
			initialEnabledToolNames = snapshot.enabledTools.toSet(),
			initialAllowedCommandNames = snapshot.allowedCommands.toSet(),
		)
		for (message in snapshot.messages) {
			session.appendPersistedMessage(
				SessionMessage(
					type = SessionMessage.Type.valueOf(message.type),
					content = message.content,
					hiddenContent = message.hiddenContent,
					usage = message.usage,
					providerName = message.providerName,
					modelName = message.modelName,
				)
			)
		}
		session.restoreSandboxState(sandbox)
		session.restoreToolState(snapshot.enabledTools.toSet())
		session.restoreCommandState(snapshot.allowedCommands.toSet())
		return Result.success(session)
	}

	@Synchronized
	private fun update(session: Session, mutate: (SessionSnapshot) -> Unit) {
		val snapshot = readSnapshot(logFile(session.storagePath, session.id)) ?: createSnapshot(session)
		snapshot.ownerPlayerId = session.ownerPlayerId?.toString()
		snapshot.storagePath = session.storagePath
		snapshot.boundPlayerId = session.boundPlayerId?.toString()
		snapshot.toolScopeId = session.toolScopeId.toString()
		snapshot.builtInPromptSections = session.builtInPromptSections.toMutableList()
		snapshot.systemPrompt = session.systemPrompt
		snapshot.updatedAt = timestamp()
		snapshot.sandbox = persistedSandbox(session.sandbox())
		snapshot.enabledTools = session.enabledToolNames().sorted().toMutableList()
		snapshot.allowedCommands = session.allowedCommandNames().sorted().toMutableList()
		mutate(snapshot)
		write(session.storagePath, session.id, snapshot)
	}

	private fun createSnapshot(session: Session): SessionSnapshot {
		val now = timestamp()
		return SessionSnapshot(
			version = 5,
			sessionId = session.id.toString(),
			ownerPlayerId = session.ownerPlayerId?.toString(),
			storagePath = session.storagePath,
			boundPlayerId = session.boundPlayerId?.toString(),
			toolScopeId = session.toolScopeId.toString(),
			builtInPromptSections = session.builtInPromptSections.toMutableList(),
			systemPrompt = session.systemPrompt,
			startedAt = now,
			updatedAt = now,
			summary = persistedSummary(session.summary()),
			messages = persistedMessages(session),
			sandbox = persistedSandbox(session.sandbox()),
			enabledTools = session.enabledToolNames().sorted().toMutableList(),
			allowedCommands = session.allowedCommandNames().sorted().toMutableList(),
			providerCalls = mutableListOf(),
			toolInvocations = mutableListOf(),
		)
	}

	private fun persistedSummary(summary: SessionSummary): PersistedSummary {
		return PersistedSummary(
			userMessageCount = summary.userMessageCount,
		)
	}

	private fun persistedMessages(session: Session): MutableList<PersistedMessage> {
		return session.messages().map { message ->
			PersistedMessage(
				type = message.type.name,
				content = message.content,
				hiddenContent = message.hiddenContent,
				usage = message.usage,
				providerName = message.providerName,
				modelName = message.modelName,
			)
		}.toMutableList()
	}

	private fun persistedSandbox(sandbox: Sandbox?): PersistedSandbox? {
		return sandbox?.let {
			PersistedSandbox(
				dimension = persistedDimension(it.dimension),
				boundary = PersistedRegion(
					firstCorner = persistedBlockPos(it.boundary.firstCorner),
					secondCorner = persistedBlockPos(it.boundary.secondCorner),
				),
				exclusions = it.exclusions.map { (name, position) ->
					PersistedNamedPoint(
						name = name,
						position = persistedBlockPos(position),
					)
				},
				targets = it.targets.map { (name, position) ->
					PersistedNamedPoint(
						name = name,
						position = persistedBlockPos(position),
					)
				},
			)
		}
	}

	private fun persistedDimension(dimension: ResourceKey<Level>): String {
		return namespacedIdPattern.findAll(dimension.toString()).lastOrNull()?.value
			?: dimension.toString()
	}

	private fun persistedBlockPos(position: BlockPos): PersistedBlockPos {
		return PersistedBlockPos(
			x = position.x,
			y = position.y,
			z = position.z,
		)
	}

	private fun restoredSandbox(persisted: PersistedSandbox?): Result<Sandbox?> {
		if (persisted == null) {
			return Result.success(null)
		}

		val separatorIndex = persisted.dimension.indexOf(':')
		if (separatorIndex <= 0 || separatorIndex == persisted.dimension.lastIndex) {
			return Result.failure(IllegalArgumentException("Invalid persisted sandbox dimension: ${persisted.dimension}"))
		}
		val dimension: ResourceKey<Level> = runCatching {
			ResourceKey.create(
				Registries.DIMENSION,
				Identifier.fromNamespaceAndPath(
					persisted.dimension.substring(0, separatorIndex),
					persisted.dimension.substring(separatorIndex + 1),
				),
			)
		}.getOrElse {
			return Result.failure(IllegalArgumentException("Invalid persisted sandbox dimension: ${persisted.dimension}", it))
		}
		return Result.success(
			Sandbox(
				dimension = dimension,
				boundary = SandboxRegion(
					firstCorner = restoredBlockPos(persisted.boundary.firstCorner),
					secondCorner = restoredBlockPos(persisted.boundary.secondCorner),
				),
				exclusions = persisted.exclusions.associate { entry ->
					entry.name to restoredBlockPos(entry.position)
				},
				targets = persisted.targets.associate { entry ->
					entry.name to restoredBlockPos(entry.position)
				},
			)
		)
	}

	private fun restoredBlockPos(position: PersistedBlockPos): BlockPos {
		return BlockPos(position.x, position.y, position.z)
	}

	private fun write(storagePath: String, sessionId: UUID, snapshot: SessionSnapshot) {
		val directory = storageDirectory(storagePath) ?: return
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

	private fun storageDirectory(storagePath: String): Path? {
		val server = OpenBlock.currentServer().getOrNull() ?: return null
		return normalizedStoragePath(storagePath)
			.fold(server.getFile(LOG_DIR)) { current, segment -> current.resolve(segment) }
	}

	private fun logFile(storagePath: String, sessionId: UUID): Path {
		return storageDirectory(storagePath)?.let { directory -> logFile(directory, sessionId) }
			?: normalizedStoragePath(storagePath)
				.fold(Path.of(LOG_DIR)) { current, segment -> current.resolve(segment) }
				.resolve("$sessionId.json")
	}

	private fun logFile(directory: Path, sessionId: UUID): Path {
		return directory.resolve("$sessionId.json")
	}

	private fun timestamp(): String = OffsetDateTime.now().format(timestampFormatter)

	internal fun playerStoragePath(ownerPlayerId: UUID): String {
		return "$PLAYER_SCOPE_PREFIX/$ownerPlayerId"
	}

	private fun normalizedStoragePath(storagePath: String): List<String> {
		return storagePath.split('/')
			.map(String::trim)
			.filter(String::isNotBlank)
			.map { segment ->
				segment.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
			}
			.ifEmpty { listOf("unscoped") }
	}

	private fun migrateLegacyPlayerSessions(ownerPlayerId: UUID): Result<Unit> {
		val legacyDirectory = storageDirectory(ownerPlayerId.toString())
			?: return Result.failure(IllegalStateException("Minecraft server is not available."))
		if (!Files.isDirectory(legacyDirectory)) {
			return Result.success(Unit)
		}

		val targetDirectory = storageDirectory(playerStoragePath(ownerPlayerId))
			?: return Result.failure(IllegalStateException("Minecraft server is not available."))
		return runCatching {
			Files.createDirectories(targetDirectory)
			Files.list(legacyDirectory).use { files ->
				files
					.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".json") }
					.forEach { legacyFile ->
						val targetFile = targetDirectory.resolve(legacyFile.fileName.toString())
						if (!Files.exists(targetFile)) {
							Files.move(legacyFile, targetFile)
						}
					}
			}
			Files.deleteIfExists(legacyDirectory)
			Unit
		}
	}

	private data class SessionSnapshot(
		val version: Int,
		val sessionId: String,
		var ownerPlayerId: String?,
		var storagePath: String,
		var boundPlayerId: String?,
		var toolScopeId: String? = null,
		var builtInPromptSections: MutableList<String> = mutableListOf(),
		var systemPrompt: String?,
		val startedAt: String,
		var updatedAt: String,
		var closedAt: String? = null,
		var closeReason: String? = null,
		var summary: PersistedSummary,
		var messages: MutableList<PersistedMessage>,
		var sandbox: PersistedSandbox? = null,
		var enabledTools: MutableList<String> = mutableListOf(),
		var allowedCommands: MutableList<String> = mutableListOf(),
		val providerCalls: MutableList<ProviderCallEntry>,
		val toolInvocations: MutableList<ToolInvocationEntry>,
	)

	private data class PersistedSummary(
		val userMessageCount: Int,
	)

	private data class PersistedMessage(
		val type: String,
		val content: String,
		val hiddenContent: String? = null,
		val usage: SessionTokenUsage? = null,
		val providerName: String? = null,
		val modelName: String? = null,
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

	private data class PersistedSandbox(
		val dimension: String,
		val boundary: PersistedRegion,
		val exclusions: List<PersistedNamedPoint>,
		@SerializedName(value = "targets", alternate = ["interactions"])
		val targets: List<PersistedNamedPoint>,
	)

	private data class PersistedRegion(
		val firstCorner: PersistedBlockPos,
		val secondCorner: PersistedBlockPos,
	)

	private data class PersistedNamedPoint(
		val name: String,
		val position: PersistedBlockPos,
	)

	private data class PersistedBlockPos(
		val x: Int,
		val y: Int,
		val z: Int,
	)

	data class TokenTotals(
		val inputTokens: Long = 0,
		val outputTokens: Long = 0,
		val cachedTokens: Long = 0,
	)

	private fun SessionTokenUsage?.inputTokensOrZero(): Long {
		return this?.inputTokens ?: 0
	}

	private fun SessionTokenUsage?.expandedOutputTokensOrZero(): Long {
		return (this?.outputTokens ?: 0) +
			(this?.reasoningTokens ?: 0) +
			(this?.thoughtsTokens ?: 0) +
			(this?.toolUsePromptTokens ?: 0)
	}

	private fun SessionTokenUsage?.cachedTokensOrZero(): Long {
		return (this?.cachedInputTokens ?: 0) +
			(this?.cacheCreationInputTokens ?: 0) +
			(this?.cacheReadInputTokens ?: 0)
	}
}
