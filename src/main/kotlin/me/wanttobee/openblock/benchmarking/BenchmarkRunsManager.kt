package me.wanttobee.openblock.benchmarking

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.Level
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BenchmarkRunsManager {
	private const val ROOT_DIR = "openblock-data/benchmarks/results"
	private const val SETTINGS_FILE = "openblock-data/benchmarks/settings.json"
	private const val RESULT_FILE_NAME = "result.json"
	private const val MODEL_CACHE_SUFFIX = ".json"
	private val gson = GsonBuilder()
		.setPrettyPrinting()
		.serializeNulls()
		.create()
	private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
	private val activeRuns = ConcurrentHashMap<UUID, ActiveRun>()

	fun settings(): Result<Settings> {
		return loadSettings().map { persisted ->
			Settings(maxRuns = persisted.maxRuns.coerceIn(1, 32))
		}
	}

	fun adjustMaxRuns(delta: Int): Result<Settings> {
		val currentSettings = settings().getOrElse { return Result.failure(it) }
		val nextSettings = Settings(
			maxRuns = (currentSettings.maxRuns + delta).coerceIn(1, 32),
		)
		saveSettings(nextSettings).getOrElse { return Result.failure(it) }
		rebuildAllCaches().getOrElse { return Result.failure(it) }
		return Result.success(nextSettings)
	}

	fun availableModels(): Result<List<ModelReference>> {
		val trackedKeys = listTrackedModels()
			.map { trackedModels -> trackedModels.map(ModelReference::key).toSet() }
			.getOrElse { return Result.failure(it) }

		return Result.success(
			Providers.all
				.flatMap { provider ->
					provider.models.map { model ->
						ModelReference(
							providerName = provider.name,
							providerDisplayName = provider.displayName,
							modelName = model.apiName,
							modelDisplayName = model.displayName,
						)
					}
				}
				.filterNot { model -> model.key() in trackedKeys }
				.sortedWith(compareBy<ModelReference>({ it.providerDisplayName.lowercase() }, { it.modelDisplayName.lowercase() }))
		)
	}

	fun listTrackedModels(): Result<List<ModelReference>> {
		val root = resultsRoot().getOrElse { return Result.failure(it) }
		if (!Files.exists(root)) {
			return Result.success(emptyList())
		}

		return runCatching {
			Files.list(root).use { providerDirectories ->
				providerDirectories.iterator().asSequence()
					.filter(Files::isDirectory)
					.flatMap { providerDirectory ->
						val providerName = providerDirectory.fileName.toString()
						Files.list(providerDirectory).use { files ->
							files.iterator().asSequence()
								.filter(Files::isRegularFile)
								.filter { path -> path.fileName.toString().endsWith(MODEL_CACHE_SUFFIX, ignoreCase = true) }
								.map { file ->
									val modelName = file.fileName.toString().removeSuffix(MODEL_CACHE_SUFFIX)
									migrateLegacyModelFileIfNeeded(providerName, modelName).getOrThrow()
									modelReference(providerName, modelName)
								}
								.toList()
						}.asSequence()
					}
					.sortedWith(compareBy<ModelReference>({ it.providerDisplayName.lowercase() }, { it.modelDisplayName.lowercase() }))
					.toList()
			}
		}
	}

	fun addTrackedModel(providerName: String, modelName: String): Result<ModelReference> {
		val reference = resolvedModelReference(providerName, modelName).getOrElse { return Result.failure(it) }
		writeModelCache(
			providerName = reference.providerName,
			modelName = reference.modelName,
			cache = rebuildModelCache(reference.providerName, reference.modelName).getOrElse { return Result.failure(it) },
		).getOrElse { return Result.failure(it) }
		return Result.success(reference)
	}

	fun removeTrackedModel(providerName: String, modelName: String): Result<Unit> {
		val cacheFile = modelCacheFile(providerName, modelName).getOrElse { return Result.failure(it) }
		val resultsDirectory = modelResultsDirectory(providerName, modelName).getOrElse { return Result.failure(it) }
		return runCatching {
			Files.deleteIfExists(cacheFile)
			if (Files.exists(resultsDirectory)) {
				Files.walk(resultsDirectory).use { paths ->
					paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
				}
			}
			Unit
		}
	}

	fun runSelectionLabel(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): String {
		return BenchmarkCatalogManager.pathLabel(pathSegments + entry.storedName)
	}

	fun recordRun(
		providerName: String,
		modelName: String,
		run: RecordedRun,
	): Result<Unit> {
		migrateLegacyModelFileIfNeeded(providerName, modelName).getOrElse { return Result.failure(it) }
		val persistedRun = if (run.recordedAt.isBlank()) run.copy(recordedAt = timestamp()) else run
		val results = loadPresetResults(providerName, modelName, persistedRun.benchmarkPath).getOrElse { return Result.failure(it) }
		val updatedResults = results.copy(
			benchmarkPath = persistedRun.benchmarkPath,
			runs = results.runs + persistedRun,
		)
		writePresetResults(providerName, modelName, updatedResults).getOrElse { return Result.failure(it) }
		rebuildAndWriteModelCache(providerName, modelName).getOrElse { return Result.failure(it) }
		return Result.success(Unit)
	}

	fun presetRunSessions(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<List<PresetRunSession>> {
		val maxRuns = settings().getOrElse { return Result.failure(it) }.maxRuns
		val benchmarkPath = pathSegments + entry.storedName
		val sortedRuns = sortedRuns(loadPresetResults(providerName, modelName, benchmarkPath).getOrElse { return Result.failure(it) }.runs)
		return Result.success(
			sortedRuns.mapIndexed { index, run ->
				PresetRunSession(
					benchmarkPath = benchmarkPath,
					sessionId = run.sessionId,
					recordedAt = run.recordedAt.takeIf(String::isNotBlank),
					considered = index < maxRuns,
					status = validationStatus(run.validationStatus, run.success),
					tokenUsage = TokenUsageSummary(
						inputTokens = run.inputTokens,
						outputTokens = run.outputTokens,
						cachedInputTokens = run.cachedInputTokens,
						reasoningTokens = run.reasoningTokens,
					),
					generationDurationMillis = run.generationDurationMillis,
				)
			}
		)
	}

	fun missingRunsForPreset(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<Int> {
		val maxRuns = settings().getOrElse { return Result.failure(it) }.maxRuns
		val existingRuns = presetRunSessions(providerName, modelName, pathSegments, entry)
			.map(List<PresetRunSession>::size)
			.getOrElse { return Result.failure(it) }
		return Result.success((maxRuns - existingRuns).coerceAtLeast(0))
	}

	fun setRunValidation(
		providerName: String,
		modelName: String,
		benchmarkPath: List<String>,
		sessionId: UUID,
		status: RunValidationStatus,
	): Result<Unit> {
		migrateLegacyModelFileIfNeeded(providerName, modelName).getOrElse { return Result.failure(it) }
		val results = loadPresetResults(providerName, modelName, benchmarkPath).getOrElse { return Result.failure(it) }
		if (results.runs.none { run -> run.sessionId == sessionId }) {
			return Result.failure(NoSuchElementException("Unknown benchmark session: $sessionId"))
		}

		val updatedResults = results.copy(
			runs = results.runs.map { run ->
				if (run.sessionId != sessionId) {
					run
				} else {
					run.copy(
						validationStatus = persistedValidationStatus(status),
						success = status == RunValidationStatus.SUCCESS,
					)
				}
			}
		)
		writePresetResults(providerName, modelName, updatedResults).getOrElse { return Result.failure(it) }
		rebuildAndWriteModelCache(providerName, modelName).getOrElse { return Result.failure(it) }
		return Result.success(Unit)
	}

	fun placeRunSession(
		playerId: UUID,
		providerName: String,
		modelName: String,
		benchmarkPath: List<String>,
		sessionId: UUID,
	): Result<Unit> {
		val run = loadPresetResults(providerName, modelName, benchmarkPath)
			.map { results ->
				results.runs.firstOrNull { candidate -> candidate.sessionId == sessionId }
					?: throw NoSuchElementException("Unknown benchmark session: $sessionId")
			}
			.getOrElse { return Result.failure(it) }
		val entry = BenchmarkCatalogManager.CatalogEntry.preset(benchmarkPath.last())
		return BenchmarkPresetManager.placeCapturedRunHere(
			playerId = playerId,
			pathSegments = benchmarkPath.dropLast(1),
			entry = entry,
			build = run.build,
			targets = run.targets,
		).map { Unit }
	}

	fun modelSummary(providerName: String, modelName: String): Result<ModelSummary> {
		migrateLegacyModelFileIfNeeded(providerName, modelName).getOrElse { return Result.failure(it) }
		val cache = rebuildAndWriteModelCache(providerName, modelName).getOrElse { return Result.failure(it) }
		val model = modelReference(providerName, modelName)
		return Result.success(
			ModelSummary(
				model = model,
				total = scoreSummary(
					successCount = cache.total.successCount,
					totalCount = cache.total.totalCount,
					complete = cache.total.complete,
				),
				tokenUsage = TokenUsageSummary(
					inputTokens = cache.total.inputTokens,
					outputTokens = cache.total.outputTokens,
					cachedInputTokens = cache.total.cachedInputTokens,
					reasoningTokens = cache.total.reasoningTokens,
				),
				generationDuration = GenerationDurationSummary(
					totalGenerationDurationMillis = cache.total.totalGenerationDurationMillis,
					measuredRunCount = cache.total.measuredRunCount,
				),
				tagScores = cache.tags.map { tag ->
					TagScore(
						tagId = tag.tagId,
						tagName = tag.tagName,
						successCount = tag.successCount,
						totalCount = tag.totalCount,
					)
				},
			)
		)
	}

	fun entrySummaries(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
	): Result<List<EntrySummary>> {
		migrateLegacyModelFileIfNeeded(providerName, modelName).getOrElse { return Result.failure(it) }
		val maxRuns = settings().getOrElse { return Result.failure(it) }.maxRuns
		val tagNames = tagNamesById().getOrElse { return Result.failure(it) }
		val entries = BenchmarkCatalogManager.listEntries(pathSegments).getOrElse { return Result.failure(it) }
		return Result.success(
			entries.map { entry ->
				summarizeEntry(providerName, modelName, pathSegments, entry, maxRuns, tagNames).getOrThrow()
			}
		)
	}

	fun registerActiveRun(
		sessionScopeId: UUID,
		sessionStoragePath: String,
		sessionId: UUID,
		providerName: String,
		modelName: String,
		benchmarkName: String,
		currentIteration: Int,
		totalIterations: Int,
		warpDimension: net.minecraft.resources.ResourceKey<Level>,
		warpX: Double,
		warpY: Double,
		warpZ: Double,
	): UUID {
		val id = UUID.randomUUID()
		activeRuns[id] = ActiveRun(
			id = id,
			sessionScopeId = sessionScopeId,
			sessionStoragePath = sessionStoragePath,
			sessionId = sessionId,
			providerName = providerName,
			modelName = modelName,
			benchmarkName = benchmarkName,
			currentIteration = currentIteration,
			totalIterations = totalIterations,
			warpDimension = warpDimension,
			warpX = warpX,
			warpY = warpY,
			warpZ = warpZ,
		)
		return id
	}

	fun updateActiveRunAction(id: UUID, action: String): Result<Unit> {
		val current = activeRuns[id] ?: return Result.failure(NoSuchElementException("Unknown active benchmark run: $id"))
		activeRuns[id] = current.copy(lastAction = action)
		return Result.success(Unit)
	}

	fun listActiveRuns(): Result<List<ActiveRun>> {
		return Result.success(
			activeRuns.values
				.sortedWith(compareBy<ActiveRun>({ it.providerName.lowercase() }, { it.modelName.lowercase() }, { it.benchmarkName.lowercase() }))
		)
	}

	fun requestForceStop(id: UUID): Result<Unit> {
		val current = activeRuns[id] ?: return Result.failure(NoSuchElementException("Unknown active benchmark run: $id"))
		activeRuns[id] = current.copy(
			deleteSessionOnFinish = true,
			lastAction = "force stopping",
		)
		AiService.interruptSession(current.sessionScopeId, "Force stop this benchmark iteration.")
			.getOrElse { return Result.failure(it) }
		return Result.success(Unit)
	}

	fun requestSafeStop(id: UUID): Result<Unit> {
		val current = activeRuns[id] ?: return Result.failure(NoSuchElementException("Unknown active benchmark run: $id"))
		activeRuns[id] = current.copy(
			safeStopRequested = true,
			lastAction = "finishing current iteration",
		)
		return Result.success(Unit)
	}

	fun consumeFinishedRun(id: UUID): ActiveRun? {
		return activeRuns.remove(id)
	}

	fun warpToActiveRun(playerId: UUID, runId: UUID): Result<Unit> {
		val run = activeRuns[runId] ?: return Result.failure(NoSuchElementException("Unknown active benchmark run: $runId"))
		val server = OpenBlock.currentServer().getOrElse { return Result.failure(it) }
		val player = server.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Player is not online: $playerId"))
		val level = server.getLevel(run.warpDimension)
			?: return Result.failure(IllegalStateException("Benchmark warp dimension is not currently loaded."))

		return runCatching {
			teleportPlayer(
				player = player,
				level = level,
				x = run.warpX,
				y = run.warpY,
				z = run.warpZ,
			)
			Unit
		}
	}

	private fun summarizeEntry(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		maxRuns: Int,
		tagNames: Map<String, String>,
	): Result<EntrySummary> {
		return when (entry.kind) {
			BenchmarkCatalogManager.EntryKind.PRESET -> summarizePresetEntry(providerName, modelName, pathSegments, entry, maxRuns, tagNames)
			BenchmarkCatalogManager.EntryKind.FOLDER -> summarizeFolderEntry(providerName, modelName, pathSegments, entry, maxRuns, tagNames)
		}
	}

	private fun summarizePresetEntry(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		maxRuns: Int,
		tagNames: Map<String, String>,
	): Result<EntrySummary> {
		val benchmarkPath = pathSegments + entry.storedName
		val runs = loadPresetResults(providerName, modelName, benchmarkPath)
			.map(PersistedPresetResults::runs)
			.getOrElse { return Result.failure(it) }
		val consideredRuns = consideredRuns(runs, maxRuns)
		val score = scoreForRuns(runs, maxRuns)
		val tagScores = BenchmarkPresetManager.selectedTagIds(pathSegments, entry)
			.map { tagIds ->
				tagIds.sorted().map { tagId ->
					TagScore(
						tagId = tagId,
						tagName = tagNames[tagId] ?: tagId,
						successCount = score.successCount,
						totalCount = score.totalCount,
					)
				}
			}
			.getOrElse { return Result.failure(it) }
		return Result.success(
			EntrySummary(
				entry = entry,
				total = score,
				tokenUsage = tokenUsageForRuns(consideredRuns),
				generationDuration = generationDurationForRuns(consideredRuns),
				tagScores = tagScores,
			)
		)
	}

	private fun summarizeFolderEntry(
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		maxRuns: Int,
		tagNames: Map<String, String>,
	): Result<EntrySummary> {
		val childSummaries = BenchmarkCatalogManager.listEntries(pathSegments + entry.storedName)
			.map { childEntries ->
				childEntries.map { child ->
					summarizeEntry(providerName, modelName, pathSegments + entry.storedName, child, maxRuns, tagNames).getOrThrow()
				}
			}
			.getOrElse { return Result.failure(it) }
		return Result.success(
			EntrySummary(
				entry = entry,
				total = aggregateScores(childSummaries.map(EntrySummary::total)),
				tokenUsage = aggregateTokenUsage(childSummaries.map(EntrySummary::tokenUsage)),
				generationDuration = aggregateGenerationDurations(childSummaries.map(EntrySummary::generationDuration)),
				tagScores = aggregateTagScores(childSummaries.flatMap(EntrySummary::tagScores)),
			)
		)
	}

	private fun rebuildAllCaches(): Result<Unit> {
		val trackedModels = listTrackedModels().getOrElse { return Result.failure(it) }
		for (model in trackedModels) {
			rebuildAndWriteModelCache(model.providerName, model.modelName).getOrElse { return Result.failure(it) }
		}
		return Result.success(Unit)
	}

	private fun rebuildAndWriteModelCache(providerName: String, modelName: String): Result<PersistedModelCache> {
		val cache = rebuildModelCache(providerName, modelName).getOrElse { return Result.failure(it) }
		writeModelCache(providerName, modelName, cache).getOrElse { return Result.failure(it) }
		return Result.success(cache)
	}

	private fun rebuildModelCache(providerName: String, modelName: String): Result<PersistedModelCache> {
		val maxRuns = settings().getOrElse { return Result.failure(it) }.maxRuns
		val presets = collectPresetEntries(emptyList()).getOrElse { return Result.failure(it) }
		val tagNames = tagNamesById().getOrElse { return Result.failure(it) }

		val presetSummaries = presets.map { preset ->
			val runs = loadPresetResults(providerName, modelName, preset.benchmarkPath)
				.map(PersistedPresetResults::runs)
				.getOrElse { return Result.failure(it) }
			val consideredRuns = consideredRuns(runs, maxRuns)
			val score = scoreForRuns(
				runs,
				maxRuns,
			)
			val tagIds = BenchmarkPresetManager.selectedTagIds(preset.pathSegments, preset.entry).getOrElse { return Result.failure(it) }
			PresetScore(
				score = score,
				tokenUsage = tokenUsageForRuns(consideredRuns),
				generationDuration = generationDurationForRuns(consideredRuns),
				tagIds = tagIds.sorted(),
			)
		}

		val totalScore = aggregateScores(presetSummaries.map(PresetScore::score))
		val groupedTags = linkedMapOf<String, MutableList<ScoreSummary>>()
		for (preset in presetSummaries) {
			for (tagId in preset.tagIds) {
				groupedTags.getOrPut(tagId, ::mutableListOf) += preset.score
			}
		}

		return Result.success(
			PersistedModelCache(
				providerName = providerName,
				modelName = modelName,
				total = PersistedCacheScore(
					successCount = totalScore.successCount,
					totalCount = totalScore.totalCount,
					complete = totalScore.complete,
					inputTokens = presetSummaries.sumOf { preset -> preset.tokenUsage.inputTokens },
					outputTokens = presetSummaries.sumOf { preset -> preset.tokenUsage.outputTokens },
					cachedInputTokens = presetSummaries.sumOf { preset -> preset.tokenUsage.cachedInputTokens },
					reasoningTokens = presetSummaries.sumOf { preset -> preset.tokenUsage.reasoningTokens },
					totalGenerationDurationMillis = presetSummaries.sumOf { preset -> preset.generationDuration.totalGenerationDurationMillis },
					measuredRunCount = presetSummaries.sumOf { preset -> preset.generationDuration.measuredRunCount },
				),
				tags = groupedTags.entries
					.map { (tagId, scores) ->
						val aggregated = aggregateScores(scores)
						PersistedCacheTagScore(
							tagId = tagId,
							tagName = tagNames[tagId] ?: tagId,
							successCount = aggregated.successCount,
							totalCount = aggregated.totalCount,
						)
					}
					.sortedBy(PersistedCacheTagScore::tagName),
			)
		)
	}

	private fun collectPresetEntries(pathSegments: List<String>): Result<List<PresetReference>> {
		val entries = BenchmarkCatalogManager.listEntries(pathSegments).getOrElse { return Result.failure(it) }
		val presets = mutableListOf<PresetReference>()
		for (entry in entries) {
			when (entry.kind) {
				BenchmarkCatalogManager.EntryKind.PRESET ->
					presets += PresetReference(
						pathSegments = pathSegments,
						entry = entry,
					)

				BenchmarkCatalogManager.EntryKind.FOLDER ->
					presets += collectPresetEntries(pathSegments + entry.storedName).getOrElse { return Result.failure(it) }
			}
		}
		return Result.success(presets)
	}

	private fun scoreForRuns(runs: List<RecordedRun>, maxRuns: Int): ScoreSummary {
		val consideredRuns = consideredRuns(runs, maxRuns)
		val statuses = consideredRuns.map { run -> validationStatus(run.validationStatus, run.success) }
		val successCount = statuses.count { status -> status == RunValidationStatus.SUCCESS }
		return scoreSummary(
			successCount = successCount,
			totalCount = maxRuns,
			complete = consideredRuns.size == maxRuns,
			actualRunCount = consideredRuns.size,
			anyUndetermined = statuses.any { status -> status == RunValidationStatus.UNDETERMINED },
		)
	}

	private fun aggregateScores(scores: List<ScoreSummary>): ScoreSummary {
		if (scores.isEmpty()) {
			return scoreSummary(
				successCount = 0,
				totalCount = 0,
				complete = false,
				actualRunCount = 0,
			)
		}

		return scoreSummary(
			successCount = scores.sumOf(ScoreSummary::successCount),
			totalCount = scores.sumOf(ScoreSummary::totalCount),
			complete = scores.all(ScoreSummary::complete),
			actualRunCount = scores.sumOf { score -> if (score.complete) score.totalCount else if (score.anyRun) 1 else 0 },
			anyUndetermined = scores.any(ScoreSummary::anyUndetermined),
		)
	}

	private fun aggregateTagScores(tagScores: List<TagScore>): List<TagScore> {
		return tagScores
			.groupBy(TagScore::tagId)
			.map { (tagId, groupedScores) ->
				TagScore(
					tagId = tagId,
					tagName = groupedScores.firstOrNull()?.tagName ?: tagId,
					successCount = groupedScores.sumOf(TagScore::successCount),
					totalCount = groupedScores.sumOf(TagScore::totalCount),
				)
			}
			.sortedBy(TagScore::tagName)
	}

	private fun aggregateTokenUsage(tokenUsages: List<TokenUsageSummary>): TokenUsageSummary {
		return TokenUsageSummary(
			inputTokens = tokenUsages.sumOf(TokenUsageSummary::inputTokens),
			outputTokens = tokenUsages.sumOf(TokenUsageSummary::outputTokens),
			cachedInputTokens = tokenUsages.sumOf(TokenUsageSummary::cachedInputTokens),
			reasoningTokens = tokenUsages.sumOf(TokenUsageSummary::reasoningTokens),
		)
	}

	private fun aggregateGenerationDurations(durations: List<GenerationDurationSummary>): GenerationDurationSummary {
		return GenerationDurationSummary(
			totalGenerationDurationMillis = durations.sumOf(GenerationDurationSummary::totalGenerationDurationMillis),
			measuredRunCount = durations.sumOf(GenerationDurationSummary::measuredRunCount),
		)
	}

	private fun tokenUsageForRuns(runs: List<RecordedRun>): TokenUsageSummary {
		return TokenUsageSummary(
			inputTokens = runs.sumOf(RecordedRun::inputTokens),
			outputTokens = runs.sumOf(RecordedRun::outputTokens),
			cachedInputTokens = runs.sumOf(RecordedRun::cachedInputTokens),
			reasoningTokens = runs.sumOf(RecordedRun::reasoningTokens),
		)
	}

	private fun generationDurationForRuns(runs: List<RecordedRun>): GenerationDurationSummary {
		val measuredRuns = runs.map(RecordedRun::generationDurationMillis).filter { duration -> duration > 0L }
		return GenerationDurationSummary(
			totalGenerationDurationMillis = measuredRuns.sum(),
			measuredRunCount = measuredRuns.size,
		)
	}

	private fun consideredRuns(runs: List<RecordedRun>, maxRuns: Int): List<RecordedRun> {
		return sortedRuns(runs).take(maxRuns)
	}

	private fun scoreSummary(
		successCount: Int,
		totalCount: Int,
		complete: Boolean,
		actualRunCount: Int = if (complete) totalCount else successCount.coerceAtMost(totalCount),
		anyUndetermined: Boolean = false,
	): ScoreSummary {
		return ScoreSummary(
			successCount = successCount,
			totalCount = totalCount,
			complete = complete,
			anyRun = actualRunCount > 0,
			anySuccess = successCount > 0,
			anyUndetermined = anyUndetermined,
			allValidated = complete && !anyUndetermined,
			allSuccessful = complete && !anyUndetermined && totalCount > 0 && successCount == totalCount,
		)
	}

	private fun tagNamesById(): Result<Map<String, String>> {
		return BenchmarkTagManager.listTags().map { tags ->
			tags.associate { tag -> tag.id to tag.name }
		}
	}

	private fun loadSettings(): Result<PersistedSettings> {
		val file = settingsFile().getOrElse { return Result.failure(it) }
		if (!Files.exists(file)) {
			return Result.success(PersistedSettings())
		}

		return readJson(file, PersistedSettings::class.java)
			.map { settings -> settings ?: PersistedSettings() }
	}

	private fun saveSettings(settings: Settings): Result<Unit> {
		val file = settingsFile().getOrElse { return Result.failure(it) }
		return runCatching {
			Files.createDirectories(file.parent)
			Files.writeString(
				file,
				gson.toJson(PersistedSettings(maxRuns = settings.maxRuns)) + "\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE,
			)
			Unit
		}
	}

	private fun loadPresetResults(
		providerName: String,
		modelName: String,
		benchmarkPath: List<String>,
	): Result<PersistedPresetResults> {
		val file = presetResultFile(providerName, modelName, benchmarkPath).getOrElse { return Result.failure(it) }
		if (!Files.exists(file)) {
			return Result.success(PersistedPresetResults(benchmarkPath = benchmarkPath))
		}

		return readJson(file, PersistedPresetResults::class.java)
			.map { results -> (results ?: PersistedPresetResults()).normalizedFor(benchmarkPath) }
	}

	private fun writePresetResults(
		providerName: String,
		modelName: String,
		results: PersistedPresetResults,
	): Result<Unit> {
		val file = presetResultFile(providerName, modelName, results.benchmarkPath).getOrElse { return Result.failure(it) }
		return writeJson(file, results.normalizedFor(results.benchmarkPath))
	}

	private fun writeModelCache(
		providerName: String,
		modelName: String,
		cache: PersistedModelCache,
	): Result<Unit> {
		val file = modelCacheFile(providerName, modelName).getOrElse { return Result.failure(it) }
		return writeJson(file, cache)
	}

	private fun migrateLegacyModelFileIfNeeded(providerName: String, modelName: String): Result<Unit> {
		val file = modelCacheFile(providerName, modelName).getOrElse { return Result.failure(it) }
		if (!Files.exists(file)) {
			return Result.success(Unit)
		}

		val contents = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrElse { return Result.failure(it) }
		if (contents.isBlank()) {
			return Result.success(Unit)
		}

		val rootObject = runCatching { JsonParser.parseString(contents).asJsonObject }.getOrElse { return Result.success(Unit) }
		if (!rootObject.has("runs")) {
			return Result.success(Unit)
		}

		val legacy = runCatching { gson.fromJson(contents, LegacyPersistedModelResults::class.java) }
			.getOrElse { return Result.failure(it) }
			?: return Result.success(Unit)

		for (run in legacy.runs) {
			val results = loadPresetResults(providerName, modelName, run.benchmarkPath).getOrElse { return Result.failure(it) }
			if (results.runs.none { existing -> existing.sessionId == run.sessionId }) {
				writePresetResults(
					providerName = providerName,
					modelName = modelName,
					results = results.copy(
						benchmarkPath = run.benchmarkPath,
						runs = results.runs + run,
					),
				).getOrElse { return Result.failure(it) }
			}
		}

		rebuildAndWriteModelCache(providerName, modelName).getOrElse { return Result.failure(it) }
		return Result.success(Unit)
	}

	private fun resultsRoot(): Result<Path> {
		return OpenBlock.currentServer().map { server ->
			server.getFile(ROOT_DIR)
		}
	}

	private fun settingsFile(): Result<Path> {
		return OpenBlock.currentServer().map { server ->
			server.getFile(SETTINGS_FILE)
		}
	}

	private fun providerDirectory(providerName: String): Result<Path> {
		val root = resultsRoot().getOrElse { return Result.failure(it) }
		return runCatching {
			Files.createDirectories(root.resolve(providerName))
		}
	}

	private fun modelCacheFile(providerName: String, modelName: String): Result<Path> {
		return providerDirectory(providerName).map { directory ->
			directory.resolve("$modelName$MODEL_CACHE_SUFFIX")
		}
	}

	private fun modelResultsDirectory(providerName: String, modelName: String): Result<Path> {
		return providerDirectory(providerName).map { directory ->
			directory.resolve(modelName)
		}
	}

	private fun presetResultFile(
		providerName: String,
		modelName: String,
		benchmarkPath: List<String>,
	): Result<Path> {
		return modelResultsDirectory(providerName, modelName).map { modelDirectory ->
			benchmarkPath.fold(modelDirectory) { current, segment ->
				current.resolve(segment)
			}.resolve(RESULT_FILE_NAME)
		}
	}

	private fun <T> readJson(path: Path, clazz: Class<T>): Result<T?> {
		return runCatching {
			Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
				gson.fromJson(reader, clazz)
			}
		}
	}

	private fun writeJson(path: Path, value: Any): Result<Unit> {
		return runCatching {
			Files.createDirectories(path.parent)
			Files.writeString(
				path,
				gson.toJson(value) + "\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE,
			)
			Unit
		}
	}

	private fun sortedRuns(runs: List<RecordedRun>): List<RecordedRun> {
		return runs.sortedWith(compareBy<RecordedRun>({ parseTimestamp(it.recordedAt) ?: OffsetDateTime.MIN }, { it.sessionId.toString() }))
	}

	private fun parseTimestamp(value: String?): OffsetDateTime? {
		return value?.takeIf(String::isNotBlank)?.let { timestamp ->
			runCatching { OffsetDateTime.parse(timestamp, timestampFormatter) }.getOrNull()
		}
	}

	private fun timestamp(): String {
		return OffsetDateTime.now().format(timestampFormatter)
	}

	private fun persistedValidationStatus(status: RunValidationStatus): String {
		return when (status) {
			RunValidationStatus.SUCCESS -> "success"
			RunValidationStatus.FAILURE -> "failure"
			RunValidationStatus.UNDETERMINED -> "unvalidated"
		}
	}

	private fun validationStatus(rawStatus: String?, success: Boolean): RunValidationStatus {
		return when (rawStatus?.trim()?.lowercase()) {
			"success" -> RunValidationStatus.SUCCESS
			"failure" -> RunValidationStatus.FAILURE
			"undetermined", "unvalidated", "" -> if (success) RunValidationStatus.SUCCESS else RunValidationStatus.UNDETERMINED
			null -> if (success) RunValidationStatus.SUCCESS else RunValidationStatus.UNDETERMINED
			else -> if (success) RunValidationStatus.SUCCESS else RunValidationStatus.UNDETERMINED
		}
	}

	private fun modelReference(providerName: String, modelName: String): ModelReference {
		val provider = Providers.getProviderByName(providerName).getOrNull()
		val model = Providers.resolveModel(providerName, modelName).getOrNull()
		return ModelReference(
			providerName = providerName,
			providerDisplayName = provider?.displayName ?: providerName,
			modelName = modelName,
			modelDisplayName = model?.displayName ?: modelName,
		)
	}

	private fun resolvedModelReference(providerName: String, modelName: String): Result<ModelReference> {
		val provider = Providers.getProviderByName(providerName).getOrElse { return Result.failure(it) }
		val model = Providers.resolveModel(providerName, modelName).getOrElse { return Result.failure(it) }
		return Result.success(
			ModelReference(
				providerName = provider.name,
				providerDisplayName = provider.displayName,
				modelName = model.apiName,
				modelDisplayName = model.displayName,
			)
		)
	}

	data class Settings(
		val maxRuns: Int = 3,
	)

	data class ModelReference(
		val providerName: String,
		val providerDisplayName: String,
		val modelName: String,
		val modelDisplayName: String,
	) {
		fun key(): String = "${providerName.lowercase()}:${modelName.lowercase()}"
	}

	data class ModelSummary(
		val model: ModelReference,
		val total: ScoreSummary,
		val tokenUsage: TokenUsageSummary,
		val generationDuration: GenerationDurationSummary,
		val tagScores: List<TagScore>,
	)

	data class EntrySummary(
		val entry: BenchmarkCatalogManager.CatalogEntry,
		val total: ScoreSummary,
		val tokenUsage: TokenUsageSummary,
		val generationDuration: GenerationDurationSummary,
		val tagScores: List<TagScore>,
	)

	data class TokenUsageSummary(
		val inputTokens: Long = 0,
		val outputTokens: Long = 0,
		val cachedInputTokens: Long = 0,
		val reasoningTokens: Long = 0,
	)

	data class GenerationDurationSummary(
		val totalGenerationDurationMillis: Long = 0,
		val measuredRunCount: Int = 0,
	) {
		val averageGenerationDurationMillis: Long?
			get() = if (measuredRunCount <= 0) null else totalGenerationDurationMillis / measuredRunCount
	}

	data class ScoreSummary(
		val successCount: Int,
		val totalCount: Int,
		val complete: Boolean,
		val anyRun: Boolean,
		val anySuccess: Boolean,
		val anyUndetermined: Boolean,
		val allValidated: Boolean,
		val allSuccessful: Boolean,
	)

	data class TagScore(
		val tagId: String,
		val tagName: String,
		val successCount: Int,
		val totalCount: Int,
	)

	enum class RunValidationStatus {
		SUCCESS,
		FAILURE,
		UNDETERMINED,
	}

	data class RecordedRun(
		val benchmarkPath: List<String>,
		val recordedAt: String = timestampFormatter.format(OffsetDateTime.now()),
		val sessionId: UUID,
		val validationMode: String,
		val validationStatus: String,
		val success: Boolean,
		val sandboxDescription: String,
		val targets: List<BenchmarkPresetManager.CapturedTarget>,
		val build: List<BenchmarkPresetManager.CapturedBuildBlock>,
		val inputTokens: Long = 0,
		val outputTokens: Long = 0,
		@SerializedName(value = "cachedInputTokens", alternate = ["cachedTokens"])
		val cachedInputTokens: Long = 0,
		val reasoningTokens: Long = 0,
		val generationDurationMillis: Long = 0,
	) {
		constructor(
			benchmarkPath: List<String>,
			sessionId: UUID,
			validationMode: String,
			validationStatus: String,
			success: Boolean,
			sandboxDescription: String,
			targets: List<BenchmarkPresetManager.CapturedTarget>,
			build: List<BenchmarkPresetManager.CapturedBuildBlock>,
		) : this(
			benchmarkPath = benchmarkPath,
			recordedAt = timestampFormatter.format(OffsetDateTime.now()),
			sessionId = sessionId,
			validationMode = validationMode,
			validationStatus = validationStatus,
			success = success,
			sandboxDescription = sandboxDescription,
			targets = targets,
			build = build,
			inputTokens = 0,
			outputTokens = 0,
			cachedInputTokens = 0,
			reasoningTokens = 0,
			generationDurationMillis = 0,
		)
	}

	data class PresetRunSession(
		val benchmarkPath: List<String>,
		val sessionId: UUID,
		val recordedAt: String?,
		val considered: Boolean,
		val status: RunValidationStatus,
		val tokenUsage: TokenUsageSummary,
		val generationDurationMillis: Long,
	)

	data class ActiveRun(
		val id: UUID,
		val sessionScopeId: UUID,
		val sessionStoragePath: String,
		val sessionId: UUID,
		val providerName: String,
		val modelName: String,
		val benchmarkName: String,
		val currentIteration: Int,
		val totalIterations: Int,
		val warpDimension: net.minecraft.resources.ResourceKey<Level>,
		val warpX: Double,
		val warpY: Double,
		val warpZ: Double,
		val lastAction: String? = null,
		val safeStopRequested: Boolean = false,
		val deleteSessionOnFinish: Boolean = false,
	)

	private data class PersistedSettings(
		val maxRuns: Int = 3,
	)

	private data class PersistedPresetResults(
		val benchmarkPath: List<String> = emptyList(),
		val runs: List<RecordedRun> = emptyList(),
	) {
		fun normalizedFor(expectedPath: List<String>): PersistedPresetResults {
			return copy(
				benchmarkPath = if (benchmarkPath.isEmpty()) expectedPath else benchmarkPath,
				runs = sortedRuns(runs),
			)
		}
	}

	private data class PersistedModelCache(
		val providerName: String = "",
		val modelName: String = "",
		val total: PersistedCacheScore = PersistedCacheScore(),
		val tags: List<PersistedCacheTagScore> = emptyList(),
	)

	private data class PersistedCacheScore(
		val successCount: Int = 0,
		val totalCount: Int = 0,
		val complete: Boolean = false,
		val inputTokens: Long = 0,
		val outputTokens: Long = 0,
		@SerializedName(value = "cachedInputTokens", alternate = ["cachedTokens"])
		val cachedInputTokens: Long = 0,
		val reasoningTokens: Long = 0,
		val totalGenerationDurationMillis: Long = 0,
		val measuredRunCount: Int = 0,
	)

	private data class PersistedCacheTagScore(
		val tagId: String = "",
		val tagName: String = "",
		val successCount: Int = 0,
		val totalCount: Int = 0,
	)

	private data class LegacyPersistedModelResults(
		val providerName: String = "",
		val modelName: String = "",
		val runs: List<RecordedRun> = emptyList(),
	)

	private data class PresetReference(
		val pathSegments: List<String>,
		val entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		val benchmarkPath: List<String>
			get() = pathSegments + entry.storedName
	}

	private data class PresetScore(
		val score: ScoreSummary,
		val tokenUsage: TokenUsageSummary,
		val generationDuration: GenerationDurationSummary,
		val tagIds: List<String>,
	)

	private fun teleportPlayer(
		player: ServerPlayer,
		level: net.minecraft.server.level.ServerLevel,
		x: Double,
		y: Double,
		z: Double,
	) {
		player.teleportTo(level, x, y, z, emptySet<Relative>().toMutableSet(), player.yRot, player.xRot, false)
	}
}
