package me.wanttobee.openblock.benchmarking

import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.ai.context.KnowledgeBase
import me.wanttobee.openblock.ai.sessions.AiSessionManager
import me.wanttobee.openblock.ai.sessions.AiTargetManager
import me.wanttobee.openblock.ai.sessions.SessionLogger
import me.wanttobee.openblock.ai.toolcalling.tools.SandboxTargetTool
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.floor

object BenchmarkExecutionManager {
	private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
		Thread(runnable, "openblock-benchmark").apply {
			isDaemon = true
		}
	}

	fun startPresetRun(
		playerId: UUID,
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<Unit> {
		return startEntryRuns(playerId, providerName, modelName, pathSegments, entry, plannedRuns = 1).map { Unit }
	}

	fun startEntryRuns(
		playerId: UUID,
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		plannedRuns: Int,
	): Result<Int> {
		if (plannedRuns <= 0) {
			return Result.failure(IllegalArgumentException("Planned runs must be at least 1."))
		}

		val server = OpenBlock.currentServer().getOrElse { return Result.failure(it) }
		val provider = Providers.getProviderByName(providerName).getOrElse { return Result.failure(it) }
		val baseModel = Providers.resolveModel(providerName, modelName).getOrElse { return Result.failure(it) }
		val benchmarkModel = provider.resolveReasoning(baseModel, null).getOrElse { return Result.failure(it) }
		val player = server.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Player is not online: $playerId"))
		val launchPoint = launchPoint(player)
		val queuedRuns = plannedQueue(pathSegments, entry, plannedRuns).getOrElse { return Result.failure(it) }
		if (queuedRuns.isEmpty()) {
			return Result.failure(IllegalArgumentException("No benchmark presets were found to queue."))
		}

		executor.submit {
			executeQueue(
				server = server,
				playerId = playerId,
				target = AiTargetManager.AiTarget(provider, benchmarkModel),
				queuedRuns = queuedRuns,
				launchPoint = launchPoint,
			)
		}

		return Result.success(queuedRuns.size)
	}

	fun startMissingRuns(
		playerId: UUID,
		providerName: String,
		modelName: String,
		pathSegments: List<String> = emptyList(),
		entry: BenchmarkCatalogManager.CatalogEntry? = null,
	): Result<Int> {
		val server = OpenBlock.currentServer().getOrElse { return Result.failure(it) }
		val provider = Providers.getProviderByName(providerName).getOrElse { return Result.failure(it) }
		val baseModel = Providers.resolveModel(providerName, modelName).getOrElse { return Result.failure(it) }
		val benchmarkModel = provider.resolveReasoning(baseModel, null).getOrElse { return Result.failure(it) }
		val player = server.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Player is not online: $playerId"))
		val launchPoint = launchPoint(player)

		val queuedRuns = missingQueue(provider.name, benchmarkModel.apiName, pathSegments, entry).getOrElse { return Result.failure(it) }
		if (queuedRuns.isEmpty()) {
			return Result.failure(IllegalArgumentException("There are no missing benchmark runs to queue."))
		}

		executor.submit {
			executeQueue(
				server = server,
				playerId = playerId,
				target = AiTargetManager.AiTarget(provider, benchmarkModel),
				queuedRuns = queuedRuns,
				launchPoint = launchPoint,
			)
		}

		return Result.success(queuedRuns.size)
	}

	private fun executeQueue(
		server: MinecraftServer,
		playerId: UUID,
		target: AiTargetManager.AiTarget,
		queuedRuns: List<QueuedPresetRun>,
		launchPoint: LaunchPoint,
	) {
		for (queuedRun in queuedRuns) {
			val preparedRun = prepareQueuedRun(server, playerId, target, queuedRun, launchPoint).getOrElse { error ->
				notifyFailure(server, playerId, error.message ?: "Unable to prepare benchmark run.")
				if (server.playerList.getPlayer(playerId) == null) {
					return
				}
				continue
			}

			val finishedRun = executePreparedRun(server, playerId, target, preparedRun)
			if (finishedRun?.deleteSessionOnFinish == true || finishedRun?.safeStopRequested == true) {
				return
			}
		}
	}

	private fun benchmarkStoragePath(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): String {
		return (listOf("benchmarks", providerName, modelName) + pathSegments + entry.storedName)
			.joinToString("/")
	}

	private fun prepareQueuedRun(
		server: MinecraftServer,
		playerId: UUID,
		target: AiTargetManager.AiTarget,
		queuedRun: QueuedPresetRun,
		launchPoint: LaunchPoint,
	): Result<PreparedRun> {
		val definition = BenchmarkPresetManager.runDefinition(queuedRun.pathSegments, queuedRun.entry).getOrElse { return Result.failure(it) }
		if (definition.task.isBlank()) {
			return Result.failure(IllegalArgumentException("Benchmark preset task is blank."))
		}

		val session = AiSessionManager.createStandaloneSession(
			storagePath = benchmarkStoragePath(target.provider.name, target.model.apiName, queuedRun.pathSegments, queuedRun.entry),
			systemPrompt = null,
			builtInPromptSections = listOf(
				KnowledgeBase.BENCHMARK_SYSTEM_PROMPT_PROPOSAL,
				KnowledgeBase.REDSTONE_DIRECTION_DETAILS,
			),
		)
		val placementResult = onServerThread(server) {
			val level = server.getLevel(launchPoint.dimension)
				?: return@onServerThread Result.failure(IllegalStateException("Benchmark launch dimension is not currently loaded."))
			val anchor = benchmarkAnchor(launchPoint, definition)
			BenchmarkPresetManager.placePresetAtScope(
				scopeId = session.toolScopeId,
				level = level,
				anchor = anchor,
				pathSegments = queuedRun.pathSegments,
				entry = queuedRun.entry,
			)
		}
		if (placementResult.isFailure) {
			onServerThread(server) { BenchmarkPresetManager.cleanupSessionSandbox(session) }
			AiSessionManager.releaseSession(session)
			return Result.failure(placementResult.exceptionOrNull() ?: IllegalStateException("Unable to place benchmark preset."))
		}

		session.restoreToolState((definition.acceptedToolNames + SandboxTargetTool.name).toSet())
		AiSessionManager.updateSession(session)
		val activeRunId = BenchmarkRunsManager.registerActiveRun(
			sessionScopeId = session.toolScopeId,
			sessionStoragePath = session.storagePath,
			sessionId = session.id,
			providerName = target.provider.name,
			modelName = target.model.apiName,
			benchmarkName = queuedRun.entry.displayName,
			currentIteration = queuedRun.queuePosition,
			totalIterations = queuedRun.queueTotal,
			warpDimension = launchPoint.dimension,
			warpX = launchPoint.centerX,
			warpY = launchPoint.bottomY.toDouble(),
			warpZ = launchPoint.centerZ,
		)

		return Result.success(
			PreparedRun(
				session = session,
				benchmarkPath = queuedRun.pathSegments + queuedRun.entry.storedName,
				validationMode = definition.postValidation,
				activeRunId = activeRunId,
				prompt = benchmarkUserPrompt(definition),
			)
		)
	}

	private fun executePreparedRun(
		server: MinecraftServer,
		playerId: UUID,
		target: AiTargetManager.AiTarget,
		preparedRun: PreparedRun,
	): BenchmarkRunsManager.ActiveRun? {
		BenchmarkRunsManager.updateActiveRunAction(preparedRun.activeRunId, target.provider.startingAction(target.model))
		val generationResult = AiService.runSession(
			session = preparedRun.session,
			target = target,
			message = preparedRun.prompt,
			onActionChange = { action, _ ->
				BenchmarkRunsManager.updateActiveRunAction(preparedRun.activeRunId, action)
			},
		)
		BenchmarkRunsManager.updateActiveRunAction(preparedRun.activeRunId, "finished")
		val finishedRun = BenchmarkRunsManager.consumeFinishedRun(preparedRun.activeRunId)
		val shouldRecord = finishedRun?.deleteSessionOnFinish != true
		val tokenTotals = SessionLogger.tokenTotals(preparedRun.session.storagePath, preparedRun.session.id)
			.getOrDefault(me.wanttobee.openblock.ai.sessions.SessionLogger.TokenTotals())
		val estimatedCost = SessionLogger.estimatedCost(preparedRun.session.storagePath, preparedRun.session.id)
			.getOrDefault(0.0)
		val captureResult = if (shouldRecord) {
			onServerThread(server) {
				BenchmarkPresetManager.captureSessionResult(preparedRun.session)
			}.map { capture -> capture as BenchmarkPresetManager.SessionRunCapture? }
		} else {
			Result.success(null)
		}
		val recordResult = captureResult.mapCatching { capture ->
			if (capture != null) {
				BenchmarkRunsManager.recordRun(
					providerName = target.provider.name,
					modelName = target.model.apiName,
					run = BenchmarkRunsManager.RecordedRun(
						benchmarkPath = preparedRun.benchmarkPath,
						sessionId = preparedRun.session.id,
						validationMode = preparedRun.validationMode,
						validationStatus = "unvalidated",
						success = false,
						sandboxDescription = capture.sandboxDescription,
						targets = capture.targets,
						build = capture.build,
						inputTokens = tokenTotals.inputTokens,
						outputTokens = tokenTotals.outputTokens,
						cachedInputTokens = tokenTotals.cachedInputTokens,
						reasoningTokens = tokenTotals.reasoningTokens,
						generationDurationMillis = tokenTotals.generationDurationMillis,
						estimatedCost = estimatedCost,
					),
				).getOrThrow()
			} else {
				Unit
			}
		}
		val cleanupResult = onServerThread(server) {
			BenchmarkPresetManager.cleanupSessionSandbox(preparedRun.session)
		}

		val failureMessage = generationResult.exceptionOrNull()?.message
			?: captureResult.exceptionOrNull()?.message
			?: recordResult.exceptionOrNull()?.message
			?: cleanupResult.exceptionOrNull()?.message
		if (failureMessage != null) {
			notifyFailure(server, playerId, failureMessage)
		}

		SessionLogger.logSessionClosed(preparedRun.session, closeReason(generationResult, recordResult, cleanupResult))
		if (finishedRun?.deleteSessionOnFinish == true) {
			SessionLogger.deleteSession(preparedRun.session.storagePath, preparedRun.session.id)
		}
		AiSessionManager.releaseSession(preparedRun.session)
		return finishedRun
	}

	private fun benchmarkAnchor(
		launchPoint: LaunchPoint,
		definition: BenchmarkPresetManager.PresetRunDefinition,
	): BlockPos {
		return BlockPos(
			floor(launchPoint.centerX - sandboxCenterOffset(definition.relativeBounds.minX, definition.relativeBounds.maxX)).toInt(),
			launchPoint.bottomY - definition.relativeBounds.minY,
			floor(launchPoint.centerZ - sandboxCenterOffset(definition.relativeBounds.minZ, definition.relativeBounds.maxZ)).toInt(),
		)
	}

	private fun launchPoint(player: net.minecraft.server.level.ServerPlayer): LaunchPoint {
		val position = player.blockPosition()
		return LaunchPoint(
			dimension = player.level().dimension(),
			centerX = position.x + 0.5,
			bottomY = position.y,
			centerZ = position.z + 0.5,
		)
	}

	private fun sandboxCenterOffset(min: Int, max: Int): Double {
		return (min + max + 1) / 2.0
	}

	private fun benchmarkUserPrompt(definition: BenchmarkPresetManager.PresetRunDefinition): String {
		val sections = mutableListOf(definition.task.trim())
		if (definition.targets.isNotEmpty()) {
			sections += ""
			sections += "You must provide sandbox targets under the exact keys listed below."
			sections += definition.targets.map { target ->
				"- ${target.key}: ${target.description.ifBlank { "No description provided." }}"
			}
		}
		return sections.joinToString("\n")
	}

	private fun plannedQueue(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		plannedRuns: Int,
	): Result<List<QueuedPresetRun>> {
		return when (entry.kind) {
			BenchmarkCatalogManager.EntryKind.PRESET ->
				Result.success(normalizeQueueProgress(
					(1..plannedRuns).map { iteration ->
						QueuedPresetRun(
							pathSegments = pathSegments,
							entry = entry,
							iterationNumber = iteration,
							iterationTotal = plannedRuns,
						)
					}
				))

			BenchmarkCatalogManager.EntryKind.FOLDER -> {
				val presets = collectPresetEntries(pathSegments + entry.storedName).getOrElse { return Result.failure(it) }
				Result.success(normalizeQueueProgress(
					(1..plannedRuns).flatMap { iteration ->
						presets.map { preset ->
							QueuedPresetRun(
								pathSegments = preset.pathSegments,
								entry = preset.entry,
								iterationNumber = iteration,
								iterationTotal = plannedRuns,
							)
						}
					}
				))
			}
		}
	}

	private fun missingQueue(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry?,
	): Result<List<QueuedPresetRun>> {
		val presets = when {
			entry == null -> collectPresetEntries(pathSegments)
			entry.kind == BenchmarkCatalogManager.EntryKind.PRESET -> Result.success(listOf(PresetReference(pathSegments, entry)))
			else -> collectPresetEntries(pathSegments + entry.storedName)
		}.getOrElse { return Result.failure(it) }
		val totalIterations = BenchmarkRunsManager.settings().getOrElse { return Result.failure(it) }.maxRuns
		val queue = mutableListOf<QueuedPresetRun>()
		for (preset in presets) {
			val existingRuns = BenchmarkRunsManager.presetRunSessions(providerName, modelName, preset.pathSegments, preset.entry)
				.map(List<BenchmarkRunsManager.PresetRunSession>::size)
				.getOrElse { return Result.failure(it) }
			val missingRuns = BenchmarkRunsManager.missingRunsForPreset(providerName, modelName, preset.pathSegments, preset.entry)
				.getOrElse { return Result.failure(it) }
			for (offset in 1..missingRuns) {
				queue += QueuedPresetRun(
					pathSegments = preset.pathSegments,
					entry = preset.entry,
					iterationNumber = existingRuns + offset,
					iterationTotal = totalIterations,
				)
			}
		}
		return Result.success(normalizeQueueProgress(queue))
	}

	private fun normalizeQueueProgress(queuedRuns: List<QueuedPresetRun>): List<QueuedPresetRun> {
		return queuedRuns.mapIndexed { index, queuedRun ->
			queuedRun.copy(
				queuePosition = index + 1,
				queueTotal = queuedRuns.size,
			)
		}
	}

	private fun collectPresetEntries(pathSegments: List<String>): Result<List<PresetReference>> {
		val entries = BenchmarkCatalogManager.listEntries(pathSegments).getOrElse { return Result.failure(it) }
		val presets = mutableListOf<PresetReference>()
		for (entry in entries) {
			when (entry.kind) {
				BenchmarkCatalogManager.EntryKind.PRESET ->
					presets += PresetReference(pathSegments, entry)

				BenchmarkCatalogManager.EntryKind.FOLDER ->
					presets += collectPresetEntries(pathSegments + entry.storedName).getOrElse { return Result.failure(it) }
			}
		}
		return Result.success(presets)
	}

	private fun closeReason(generationResult: Result<Boolean>, recordResult: Result<Unit>, cleanupResult: Result<Unit>): String {
		generationResult.exceptionOrNull()?.message?.let { message ->
			return "Benchmark run ended with an AI error: $message"
		}
		recordResult.exceptionOrNull()?.message?.let { message ->
			return "Benchmark run finished but result persistence failed: $message"
		}
		cleanupResult.exceptionOrNull()?.message?.let { message ->
			return "Benchmark run finished but cleanup failed: $message"
		}
		if (generationResult.getOrNull() == false) {
			return "Benchmark run was interrupted."
		}
		return "Benchmark run completed."
	}

	private fun <T> onServerThread(
		server: MinecraftServer,
		block: () -> Result<T>,
	): Result<T> {
		val future = CompletableFuture<Result<T>>()
		server.execute {
			future.complete(block())
		}
		return future.join()
	}

	private fun notifyFailure(server: MinecraftServer, playerId: UUID, message: String) {
		server.execute {
			server.playerList.getPlayer(playerId)?.sendSystemMessage(
				Component.literal("Benchmark run failed: $message").withStyle(ChatFormatting.RED),
			)
		}
	}

	private data class QueuedPresetRun(
		val pathSegments: List<String>,
		val entry: BenchmarkCatalogManager.CatalogEntry,
		val iterationNumber: Int,
		val iterationTotal: Int,
		val queuePosition: Int = 1,
		val queueTotal: Int = 1,
	)

	private data class PreparedRun(
		val session: me.wanttobee.openblock.ai.sessions.Session,
		val benchmarkPath: List<String>,
		val validationMode: String,
		val activeRunId: UUID,
		val prompt: String,
	)

	private data class PresetReference(
		val pathSegments: List<String>,
		val entry: BenchmarkCatalogManager.CatalogEntry,
	)

	private data class LaunchPoint(
		val dimension: net.minecraft.resources.ResourceKey<Level>,
		val centerX: Double,
		val bottomY: Int,
		val centerZ: Double,
	)
}
