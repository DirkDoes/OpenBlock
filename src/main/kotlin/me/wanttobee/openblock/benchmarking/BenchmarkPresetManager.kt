package me.wanttobee.openblock.benchmarking

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.sandbox.Sandbox
import me.wanttobee.openblock.sandbox.SandboxFloorBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.commands.CommandSource
import net.minecraft.resources.Identifier
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

object BenchmarkPresetManager {
	private val gson = GsonBuilder()
		.setPrettyPrinting()
		.create()

	fun hasCurrentSandbox(playerId: UUID): Boolean {
		return AiService.currentSandbox(playerId).isSuccess
	}

	fun currentCaptureSummary(playerId: UUID): Result<CaptureSummary> {
		return captureCurrentPreset(playerId).map { preset ->
			CaptureSummary(
				acceptedToolCallCount = preset.acceptedToolCalls.size,
				exclusionCount = preset.relativeSandbox.exclusions.size,
				targetCount = preset.relativeSandbox.targets.size,
				buildBlockCount = preset.build?.size ?: 0,
				sandboxDescription = preset.relativeSandbox.boundary.description(),
			)
		}
	}

	fun createPreset(playerId: UUID, pathSegments: List<String>, rawName: String): Result<BenchmarkCatalogManager.CatalogEntry> {
		val document = captureCurrentPreset(playerId)
			.map { preset -> preset.copy(name = rawName) }
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.createPreset(pathSegments, rawName, gson.toJson(document) + "\n")
	}

	fun overwritePreset(
		playerId: UUID,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<BenchmarkCatalogManager.CatalogEntry> {
		val existingMetadata = loadPreset(pathSegments, entry).map { preset ->
			ExistingPresetMetadata(
				tagIds = preset.normalizedTagIds(),
				summary = preset.normalizedSummary(),
				task = preset.normalizedTask(),
				targetDefinitions = preset.normalizedTargetDefinitions(),
			)
		}.getOrDefault(ExistingPresetMetadata())
		val document = captureCurrentPreset(playerId)
			.map { preset ->
				preset.copy(
					name = entry.displayName,
					tagIds = existingMetadata.tagIds,
					summary = existingMetadata.summary,
					task = existingMetadata.task,
					targetDefinitions = existingMetadata.targetDefinitions,
				)
			}
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(document) + "\n")
	}

	fun selectedTagIds(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<Set<String>> {
		return loadPreset(pathSegments, entry).map { preset ->
			preset.normalizedTagIds().toSet()
		}
	}

	fun updateSelectedTagIds(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		selectedTagIds: Set<String>,
	): Result<BenchmarkCatalogManager.CatalogEntry> {
		val updatedPreset = loadPreset(pathSegments, entry)
			.map { preset ->
				preset.copy(tagIds = selectedTagIds.toList().distinct().sorted())
			}
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(updatedPreset) + "\n")
	}

	fun metadata(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<PresetMetadata> {
		return loadPreset(pathSegments, entry).map { preset ->
			PresetMetadata(
				sizeX = preset.relativeSandbox.boundary.sizeX(),
				sizeY = preset.relativeSandbox.boundary.sizeY(),
				sizeZ = preset.relativeSandbox.boundary.sizeZ(),
				summary = preset.normalizedSummary(),
				task = preset.normalizedTask(),
			)
		}
	}

	fun updateSummary(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		summary: String,
	): Result<BenchmarkCatalogManager.CatalogEntry> {
		val updatedPreset = loadPreset(pathSegments, entry)
			.map { preset -> preset.copy(summary = summary.trim()) }
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(updatedPreset) + "\n")
	}

	fun updateTask(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		task: String,
	): Result<BenchmarkCatalogManager.CatalogEntry> {
		val updatedPreset = loadPreset(pathSegments, entry)
			.map { preset -> preset.copy(task = task.trim()) }
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(updatedPreset) + "\n")
	}

	fun targets(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<List<PresetTargetEntry>> {
		return loadPreset(pathSegments, entry).map { preset ->
			preset.normalizedTargetDefinitions().map { target ->
				PresetTargetEntry(
					key = target.key,
					description = target.normalizedDescription(),
				)
			}
		}
	}

	fun createTarget(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		rawKey: String,
		description: String,
	): Result<PresetTargetEntry> {
		val normalizedKey = normalizedTargetKey(rawKey).getOrElse { return Result.failure(it) }
		val normalizedDescription = description.trim()
		val updatedPreset = loadPreset(pathSegments, entry)
			.map { preset ->
				if (preset.normalizedTargetDefinitions().any { it.key == normalizedKey }) {
					error("A preset target with that key already exists.")
				}
				preset.copy(
					targetDefinitions = (preset.normalizedTargetDefinitions() + PersistedTargetDefinition(
						key = normalizedKey,
						description = normalizedDescription,
					)).sortedBy(PersistedTargetDefinition::key)
				)
			}
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(updatedPreset) + "\n")
			.map {
				PresetTargetEntry(
					key = normalizedKey,
					description = normalizedDescription,
				)
			}
	}

	fun renameTarget(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		targetKey: String,
		rawKey: String,
	): Result<PresetTargetEntry> {
		val normalizedKey = normalizedTargetKey(rawKey).getOrElse { return Result.failure(it) }
		val updatedPreset = loadPreset(pathSegments, entry)
			.map { preset ->
				val existingTarget = preset.normalizedTargetDefinitions().firstOrNull { it.key == targetKey }
					?: error("That preset target no longer exists.")
				if (targetKey != normalizedKey && preset.normalizedTargetDefinitions().any { it.key == normalizedKey }) {
					error("A preset target with that key already exists.")
				}
				preset.copy(
					targetDefinitions = preset.normalizedTargetDefinitions()
						.filterNot { it.key == targetKey }
						.plus(existingTarget.copy(key = normalizedKey))
						.sortedBy(PersistedTargetDefinition::key)
				)
			}
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(updatedPreset) + "\n")
			.map {
				val renamedTarget = updatedPreset.normalizedTargetDefinitions().first { it.key == normalizedKey }
				PresetTargetEntry(
					key = renamedTarget.key,
					description = renamedTarget.normalizedDescription(),
				)
			}
	}

	fun updateTargetDescription(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		targetKey: String,
		description: String,
	): Result<PresetTargetEntry> {
		val normalizedDescription = description.trim()
		val updatedPreset = loadPreset(pathSegments, entry)
			.map { preset ->
				val existingTarget = preset.normalizedTargetDefinitions().firstOrNull { it.key == targetKey }
					?: error("That preset target no longer exists.")
				preset.copy(
					targetDefinitions = preset.normalizedTargetDefinitions()
						.filterNot { it.key == targetKey }
						.plus(existingTarget.copy(description = normalizedDescription))
						.sortedBy(PersistedTargetDefinition::key)
				)
			}
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(updatedPreset) + "\n")
			.map {
				PresetTargetEntry(
					key = targetKey,
					description = normalizedDescription,
				)
			}
	}

	fun deleteTarget(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		targetKey: String,
	): Result<Unit> {
		val updatedPreset = loadPreset(pathSegments, entry)
			.map { preset ->
				if (preset.normalizedTargetDefinitions().none { it.key == targetKey }) {
					error("That preset target no longer exists.")
				}
				preset.copy(
					targetDefinitions = preset.normalizedTargetDefinitions().filterNot { it.key == targetKey }
				)
			}
			.getOrElse { return Result.failure(it) }
		return BenchmarkCatalogManager.overwritePreset(pathSegments, entry, gson.toJson(updatedPreset) + "\n")
			.map { Unit }
	}

	fun placePresetHere(
		playerId: UUID,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<AppliedPresetSummary> {
		if (entry.kind != BenchmarkCatalogManager.EntryKind.PRESET) {
			return Result.failure(IllegalArgumentException("Only benchmark presets can be placed."))
		}

		val server = OpenBlock.currentServer().getOrElse { return Result.failure(it) }
		val player = server.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Player is not online: $playerId"))
		val preset = loadPreset(pathSegments, entry).getOrElse { return Result.failure(it) }
		val level = player.level()
		val anchor = player.blockPosition().immutable()
		val resolvedBuild = resolveBuildStates(preset.build.orEmpty()).getOrElse { return Result.failure(it) }
		val sandbox = applySandbox(playerId, level, anchor, preset.relativeSandbox).getOrElse { return Result.failure(it) }

		SandboxFloorBuilder.placeFloor(level, sandbox).getOrElse { return Result.failure(it) }
		placeBuild(level, anchor, resolvedBuild).getOrElse { return Result.failure(it) }
		applyAcceptedToolCalls(playerId, preset.acceptedToolCalls)

		return Result.success(
			AppliedPresetSummary(
				placedBlockCount = resolvedBuild.size,
				acceptedToolCallCount = preset.acceptedToolCalls.size,
				exclusionCount = preset.relativeSandbox.exclusions.size,
				targetCount = preset.relativeSandbox.targets.size,
				sandboxDescription = absoluteBoundaryDescription(anchor, preset.relativeSandbox.boundary),
			)
		)
	}

	private fun captureCurrentPreset(playerId: UUID): Result<PersistedPreset> {
		val session = AiService.currentSession(playerId).getOrElse { return Result.failure(it) }
		val sandbox = session.sandbox() ?: return Result.failure(NoSuchElementException("No active sandbox."))
		val server = OpenBlock.currentServer().getOrElse { return Result.failure(it) }
		val level = server.getLevel(sandbox.dimension)
			?: return Result.failure(IllegalStateException("Sandbox dimension is not currently loaded."))
		val origin = sandbox.minCorner()
		val buildBlocks = captureBuild(level, sandbox, origin)

		return Result.success(
			PersistedPreset(
				name = "",
				acceptedToolCalls = session.enabledToolNames().sorted(),
				tagIds = emptyList(),
				summary = "",
				task = "",
				targetDefinitions = emptyList(),
				relativeSandbox = PersistedRelativeSandbox(
					boundary = PersistedRelativeRegion(
						firstCorner = relativePosition(origin, sandbox.minCorner()),
						secondCorner = relativePosition(origin, sandbox.maxCorner()),
					),
					exclusions = sandbox.exclusions
						.map { (name, position) ->
							PersistedNamedRelativePosition(name, relativePosition(origin, position))
						}
						.sortedBy(PersistedNamedRelativePosition::name),
					targets = sandbox.targets
						.map { (name, position) ->
							PersistedNamedRelativePosition(name, relativePosition(origin, position))
						}
						.sortedBy(PersistedNamedRelativePosition::name),
				),
				build = buildBlocks.takeIf(List<PersistedRelativeBlock>::isNotEmpty),
			)
		)
	}

	private fun loadPreset(
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
	): Result<PersistedPreset> {
		val contents = BenchmarkCatalogManager.readPreset(pathSegments, entry)
			.getOrElse { return Result.failure(it) }
		return runCatching {
			gson.fromJson(contents, PersistedPreset::class.java)
		}.fold(
			onSuccess = { preset ->
				if (preset == null) {
					Result.failure(IllegalArgumentException("Benchmark preset is empty."))
				} else {
					Result.success(preset)
				}
			},
			onFailure = { error ->
				Result.failure(IllegalArgumentException("Benchmark preset could not be parsed.", error))
			},
		)
	}

	private fun captureBuild(level: ServerLevel, sandbox: me.wanttobee.openblock.sandbox.Sandbox, origin: BlockPos): List<PersistedRelativeBlock> {
		val min = sandbox.minCorner()
		val max = sandbox.maxCorner()
		val capturedBlocks = mutableListOf<PersistedRelativeBlock>()
		for (y in min.y..max.y) {
			for (z in min.z..max.z) {
				for (x in min.x..max.x) {
					val position = BlockPos(x, y, z)
					val state = level.getBlockState(position)
					if (state.isAir) {
						continue
					}

					capturedBlocks += PersistedRelativeBlock(
						position = relativePosition(origin, position),
						blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString(),
						properties = blockProperties(state),
						entries = (level.getBlockEntity(position) as? Container)
							?.let(::captureContainerEntries)
							?.takeIf(List<PersistedContainerEntry>::isNotEmpty),
					)
				}
			}
		}
		return capturedBlocks
	}

	private fun relativePosition(origin: BlockPos, absolute: BlockPos): PersistedRelativePosition {
		return PersistedRelativePosition(
			x = absolute.x - origin.x,
			y = absolute.y - origin.y,
			z = absolute.z - origin.z,
		)
	}

	private fun blockProperties(blockState: BlockState): Map<String, String> {
		return blockState.values
			.toList()
			.map(Any::toString)
			.sorted()
			.associate { entry ->
				val separatorIndex = entry.indexOf('=')
				if (separatorIndex < 0) {
					entry to ""
				} else {
					entry.substring(0, separatorIndex) to entry.substring(separatorIndex + 1)
				}
		}
	}

	private fun resolveBuildStates(build: List<PersistedRelativeBlock>): Result<List<ResolvedRelativeBlock>> {
		return runCatching {
			build.map { block ->
				ResolvedRelativeBlock(
					position = block.position,
					blockSpec = blockSpec(block.blockId, block.properties).getOrThrow(),
					entries = resolveContainerEntries(block.entries.orEmpty()).getOrThrow(),
				)
			}
		}
	}

	private fun placeBuild(level: ServerLevel, anchor: BlockPos, build: List<ResolvedRelativeBlock>): Result<Unit> {
		val output = mutableListOf<String>()
		var success = false
		val commandSource = object : CommandSource {
			override fun sendSystemMessage(component: Component) {
				output += component.string
			}

			override fun acceptsSuccess(): Boolean = true
			override fun acceptsFailure(): Boolean = true
			override fun shouldInformAdmins(): Boolean = false
		}
		val source = level.server.createCommandSourceStack()
			.withSource(commandSource)
			.withLevel(level)
			.withCallback { succeeded, _ ->
				success = succeeded
			}

		return runCatching {
			for (block in build) {
				val position = absolutePosition(anchor, block.position)
				if (position.y !in level.minY until level.maxY) {
					error("Preset block is outside the world build height at [${position.x}, ${position.y}, ${position.z}].")
				}
				success = false
				output.clear()
				level.server.commands.performPrefixedCommand(
					source,
					"setblock ${position.x} ${position.y} ${position.z} ${block.blockSpec}",
				)
				if (!success) {
					error(
						output.lastOrNull()
							?: "Unable to place preset block at [${position.x}, ${position.y}, ${position.z}]."
					)
				}
			}

			for (block in build) {
				if (block.entries.isEmpty()) {
					continue
				}

				val position = absolutePosition(anchor, block.position)
				val container = level.getBlockEntity(position) as? Container
					?: error("Placed preset block at [${position.x}, ${position.y}, ${position.z}] does not support storing items.")
				container.clearContent()
				for (entry in block.entries) {
					if (entry.slot !in 0 until container.containerSize) {
						error(
							"Placed preset container at [${position.x}, ${position.y}, ${position.z}] does not support slot ${entry.slot}."
						)
					}
					container.setItem(entry.slot, ItemStack(entry.item, entry.count))
				}
				container.setChanged()
			}
		}
	}

	private fun applySandbox(
		playerId: UUID,
		level: ServerLevel,
		anchor: BlockPos,
		relativeSandbox: PersistedRelativeSandbox,
	): Result<Sandbox> {
		val firstCorner = absolutePosition(anchor, relativeSandbox.boundary.firstCorner)
		val secondCorner = absolutePosition(anchor, relativeSandbox.boundary.secondCorner)
		return AiService.setSandbox(playerId, level.dimension(), firstCorner, secondCorner)
			.mapCatching {
				for (entry in relativeSandbox.exclusions) {
					AiService.addSandboxExclusion(
						playerId,
						level.dimension(),
						entry.name,
						absolutePosition(anchor, entry.position),
					).getOrThrow()
				}
				for (entry in relativeSandbox.targets) {
					AiService.addSandboxTarget(
						playerId,
						level.dimension(),
						entry.name,
						absolutePosition(anchor, entry.position),
					).getOrThrow()
				}
				AiService.currentSandbox(playerId).getOrThrow()
			}
	}

	private fun applyAcceptedToolCalls(playerId: UUID, acceptedToolCalls: List<String>) {
		val acceptedToolNames = acceptedToolCalls
			.map(String::lowercase)
			.toSet()
		for (tool in AiService.allTools()) {
			AiService.setToolEnabled(playerId, tool.name, tool.name.lowercase() in acceptedToolNames)
		}
	}

	private fun absoluteBoundaryDescription(anchor: BlockPos, boundary: PersistedRelativeRegion): String {
		val firstCorner = absolutePosition(anchor, boundary.firstCorner)
		val secondCorner = absolutePosition(anchor, boundary.secondCorner)
		return "[${firstCorner.x}, ${firstCorner.y}, ${firstCorner.z}] -> [${secondCorner.x}, ${secondCorner.y}, ${secondCorner.z}]"
	}

	private fun absolutePosition(anchor: BlockPos, relative: PersistedRelativePosition): BlockPos {
		return BlockPos(anchor.x + relative.x, anchor.y + relative.y, anchor.z + relative.z)
	}

	private fun blockSpec(blockId: String, properties: Map<String, String>): Result<String> {
		val normalizedBlockId = blockId.trim()
		if (normalizedBlockId.isBlank() || ' ' in normalizedBlockId) {
			return Result.failure(IllegalArgumentException("Invalid block id in preset: $blockId"))
		}
		if (properties.isEmpty()) {
			return Result.success(normalizedBlockId)
		}
		val normalizedProperties = properties.toSortedMap().entries.joinToString(prefix = "[", postfix = "]", separator = ",") { entry ->
			if (entry.key.isBlank()) {
				throw IllegalArgumentException("Benchmark preset contains a blank block property name.")
			}
			"${entry.key}=${entry.value}"
		}
		return Result.success(normalizedBlockId + normalizedProperties)
	}

	private fun captureContainerEntries(container: Container): List<PersistedContainerEntry> {
		return (0 until container.containerSize).mapNotNull { slot ->
			val stack = container.getItem(slot)
			if (stack.isEmpty) {
				null
			} else {
				PersistedContainerEntry(
					slot = slot,
					item = BuiltInRegistries.ITEM.getKey(stack.item).toString(),
					count = stack.count,
				)
			}
		}
	}

	private fun resolveContainerEntries(entries: List<PersistedContainerEntry>): Result<List<ResolvedContainerEntry>> {
		return runCatching {
			entries.map { entry ->
				val item = resolveItem(entry.item).getOrThrow()
				ResolvedContainerEntry(
					slot = entry.slot,
					item = item,
					count = validateItemCount(item, entry.item, entry.count).getOrThrow(),
				)
			}
		}
	}

	private fun resolveItem(rawItem: String): Result<Item> {
		val normalized = rawItem.trim()
		if (normalized.isBlank() || normalized.any(Char::isWhitespace)) {
			return Result.failure(IllegalArgumentException("Invalid item id in preset: $rawItem"))
		}
		val identifier = parseIdentifier(normalized).getOrElse { return Result.failure(it) }
		if (!BuiltInRegistries.ITEM.containsKey(identifier)) {
			return Result.failure(IllegalArgumentException("Unknown item id in preset: $rawItem"))
		}
		return Result.success(BuiltInRegistries.ITEM.getValue(identifier))
	}

	private fun validateItemCount(item: Item, rawItem: String, count: Int): Result<Int> {
		if (count <= 0) {
			return Result.failure(IllegalArgumentException("Invalid item count $count for preset item $rawItem."))
		}
		if (count > ItemStack(item).maxStackSize) {
			return Result.failure(IllegalArgumentException("Preset item $rawItem cannot stack to $count."))
		}
		return Result.success(count)
	}

	private fun parseIdentifier(rawValue: String): Result<Identifier> {
		val namespace = rawValue.substringBefore(':', missingDelimiterValue = "minecraft")
		val path = rawValue.substringAfter(':', missingDelimiterValue = rawValue)
		return runCatching {
			Identifier.fromNamespaceAndPath(namespace, path)
		}.fold(
			onSuccess = Result.Companion::success,
			onFailure = { Result.failure(IllegalArgumentException("Invalid identifier: $rawValue", it)) },
		)
	}

	private fun normalizedTargetKey(rawKey: String): Result<String> {
		val sanitized = rawKey.trim()
			.lowercase()
			.map { character ->
				if (character in 'a'..'z' || character in '0'..'9' || character == '_') {
					character
				} else {
					'_'
				}
			}
			.joinToString("")
		if (sanitized.isBlank()) {
			return Result.failure(IllegalArgumentException("Target key cannot be blank."))
		}
		return Result.success(sanitized)
	}

	data class CaptureSummary(
		val acceptedToolCallCount: Int,
		val exclusionCount: Int,
		val targetCount: Int,
		val buildBlockCount: Int,
		val sandboxDescription: String,
	)

	data class AppliedPresetSummary(
		val placedBlockCount: Int,
		val acceptedToolCallCount: Int,
		val exclusionCount: Int,
		val targetCount: Int,
		val sandboxDescription: String,
	)

	data class PresetMetadata(
		val sizeX: Int,
		val sizeY: Int,
		val sizeZ: Int,
		val summary: String,
		val task: String,
	)

	data class PresetTargetEntry(
		val key: String,
		val description: String,
	)

	private data class PersistedPreset(
		val name: String,
		val acceptedToolCalls: List<String>,
		@SerializedName(value = "tagIds", alternate = ["tags"])
		val tagIds: List<String>? = emptyList(),
		val summary: String? = "",
		val task: String? = "",
		@SerializedName(value = "targets", alternate = ["targetDefinitions"])
		val targetDefinitions: List<PersistedTargetDefinition>? = emptyList(),
		val relativeSandbox: PersistedRelativeSandbox,
		val build: List<PersistedRelativeBlock>? = null,
	) {
		fun normalizedTagIds(): List<String> {
			return tagIds.orEmpty()
				.filter(String::isNotBlank)
				.distinct()
				.sorted()
		}

		fun normalizedSummary(): String = summary?.trim().orEmpty()

		fun normalizedTask(): String = task?.trim().orEmpty()

		fun normalizedTargetDefinitions(): List<PersistedTargetDefinition> {
			val normalizedTargets = linkedMapOf<String, PersistedTargetDefinition>()
			for (target in targetDefinitions.orEmpty()) {
				val normalizedKey = normalizedTargetKey(target.key).getOrNull() ?: continue
				normalizedTargets[normalizedKey] = PersistedTargetDefinition(
					key = normalizedKey,
					description = target.normalizedDescription(),
				)
			}
			return normalizedTargets.values.sortedBy(PersistedTargetDefinition::key)
		}
	}

	private data class PersistedRelativeSandbox(
		val boundary: PersistedRelativeRegion,
		val exclusions: List<PersistedNamedRelativePosition>,
		val targets: List<PersistedNamedRelativePosition>,
	)

	private data class PersistedRelativeRegion(
		val firstCorner: PersistedRelativePosition,
		val secondCorner: PersistedRelativePosition,
	) {
		fun description(): String {
			return "[${firstCorner.x}, ${firstCorner.y}, ${firstCorner.z}] -> [${secondCorner.x}, ${secondCorner.y}, ${secondCorner.z}]"
		}

		fun sizeX(): Int = kotlin.math.abs(secondCorner.x - firstCorner.x) + 1
		fun sizeY(): Int = kotlin.math.abs(secondCorner.y - firstCorner.y) + 1
		fun sizeZ(): Int = kotlin.math.abs(secondCorner.z - firstCorner.z) + 1
	}

	private data class PersistedRelativeBlock(
		val position: PersistedRelativePosition,
		val blockId: String,
		val properties: Map<String, String>,
		val entries: List<PersistedContainerEntry>? = null,
	)

	private data class PersistedContainerEntry(
		val slot: Int,
		val item: String,
		val count: Int,
	)

	private data class PersistedTargetDefinition(
		val key: String,
		val description: String = "",
	) {
		fun normalizedDescription(): String = description.trim()
	}

	private data class PersistedNamedRelativePosition(
		val name: String,
		val position: PersistedRelativePosition,
	)

	private data class PersistedRelativePosition(
		val x: Int,
		val y: Int,
		val z: Int,
	)

	private data class ResolvedRelativeBlock(
		val position: PersistedRelativePosition,
		val blockSpec: String,
		val entries: List<ResolvedContainerEntry>,
	)

	private data class ResolvedContainerEntry(
		val slot: Int,
		val item: Item,
		val count: Int,
	)

	private data class ExistingPresetMetadata(
		val tagIds: List<String> = emptyList(),
		val summary: String = "",
		val task: String = "",
		val targetDefinitions: List<PersistedTargetDefinition> = emptyList(),
	)
}
