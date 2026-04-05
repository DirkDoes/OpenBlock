package me.wanttobee.openblock.ai.toolcalling

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.sandbox.SandboxRegion
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.round
import kotlin.streams.toList

object BlockPlacementToolsSupport {
	private const val AREA_EMPTY_TOKEN = '.'
	private const val AREA_PALETTE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&()*+-:;<=>?@[]{|}~"
	private val INTERACTION_PLAYER_UUID: UUID = UUID.fromString("8ac3568f-7a9f-45e7-9336-5456e5ccbb1c")
	private const val INTERACTION_PLAYER_NAME = "[OpenBlock]"
	private val INTERACTION_FACE: Direction = Direction.SOUTH
	private val INTERACTION_FACING: Direction = Direction.NORTH
	private val activeObservations = ConcurrentHashMap<UUID, ActiveObservation>()

	fun bind() {
		ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick(::advanceObservations))
	}

	fun getBlocks(
		playerId: UUID?,
		from: String,
		to: String,
		mode: String?,
	): AiToolExecution {
		val source = toolContext(playerId).getOrElse { return failedExecution(it.message ?: "Unknown tool context error.") }
		val fromPosition = parseBlockPos(source, from).getOrElse { return failedExecution("Invalid from position: $from") }
		val toPosition = parseBlockPos(source, to).getOrElse { return failedExecution("Invalid to position: $to") }
		val requestedRegion = SandboxRegion(
			firstCorner = fromPosition.immutable(),
			secondCorner = toPosition.immutable(),
		)
		if (!isAllowedArea(playerId, source, requestedRegion.minCorner(), requestedRegion.maxCorner())) {
			return failedExecution("Requested area is outside the active sandbox.")
		}
		val readMode = when (mode?.trim()?.lowercase()) {
			"full", "area" -> ReadMode.AREA
			"line", "ray" -> ReadMode.RAY
			else -> return failedExecution("Invalid read mode: ${mode ?: ""}. Use area or ray.")
		}

		val level = source.level
		val xAxis = (requestedRegion.minX..requestedRegion.maxX).toList()
		val yAxis = (requestedRegion.minY..requestedRegion.maxY).toList()
		val zAxis = (requestedRegion.minZ..requestedRegion.maxZ).toList()

		for (x in xAxis) {
			for (y in yAxis) {
				for (z in zAxis) {
					val position = BlockPos(x, y, z)
					if (!level.isLoaded(position)) {
						return failedExecution("Requested area contains unloaded blocks.")
					}
				}
			}
		}

		if (readMode == ReadMode.RAY) {
			val linePositions = linePositions(requestedRegion.minCorner(), requestedRegion.maxCorner())
			val blocks = linePositions.map { position ->
				blockIdOrNull(level.getBlockState(position))
			}
			return AiToolExecution(
				payload = linkedMapOf(
					"mode" to "ray",
					"from" to formatPos(requestedRegion.minCorner()),
					"to" to formatPos(requestedRegion.maxCorner()),
					"positions" to linePositions.map(::formatPos),
					"blocks" to blocks,
				)
			)
		}

		val blocks = yAxis.map { y ->
			zAxis.map { z ->
				xAxis.map { x ->
					blockIdOrNull(level.getBlockState(BlockPos(x, y, z)))
				}
			}
		}
		val palette = areaPalette(blocks)
		val layers = yAxis.mapIndexed { yIndex, y ->
			linkedMapOf(
				"y" to y,
				"rows" to zAxis.mapIndexed { zIndex, _ ->
					blocks[yIndex][zIndex].joinToString(separator = "") { blockId ->
						palette.tokenFor(blockId)
					}
				},
			)
		}

		return AiToolExecution(
			payload = linkedMapOf(
				"mode" to "area",
				"from" to formatPos(requestedRegion.minCorner()),
				"to" to formatPos(requestedRegion.maxCorner()),
				"grid_order" to "layers[y].rows[z][x]",
				"token_width" to palette.tokenWidth,
				"palette" to palette.legend,
				"layers" to layers,
			)
		)
	}

	fun getBlockDetails(
		playerId: UUID?,
		position: String,
	): AiToolExecution {
		val source = toolContext(playerId).getOrElse { return failedExecution(it.message ?: "Unknown tool context error.") }
		val targetPosition = parseBlockPos(source, position).getOrElse { return failedExecution("Invalid position: $position") }
		if (!isAllowedPosition(playerId, source, targetPosition)) {
			return failedExecution("Requested position is outside the active sandbox.")
		}

		val level = source.level
		if (!level.isLoaded(targetPosition)) {
			return failedExecution("Requested position is not currently loaded.")
		}

		val blockState = level.getBlockState(targetPosition)
		val blockId = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()

		return AiToolExecution(
			payload = linkedMapOf(
				"position" to formatPos(targetPosition),
				"block" to blockId,
				"is_air" to blockState.isAir,
				"properties" to blockProperties(blockState),
			)
		)
	}

	fun interact(
		playerId: UUID?,
		position: String,
	): AiToolExecution {
		val source = toolContext(playerId).getOrElse { return failedExecution(it.message ?: "Unknown tool context error.") }
		val targetPosition = parseBlockPos(source, position).getOrElse { return failedExecution("Invalid position: $position") }
		if (!isAllowedPosition(playerId, source, targetPosition)) {
			return failedExecution("Requested position is outside the active sandbox.")
		}

		val level = source.level
		if (!level.isLoaded(targetPosition)) {
			return failedExecution("Requested position is not currently loaded.")
		}

		val beforeState = level.getBlockState(targetPosition)
		val interactionResult = simulateInteraction(level, targetPosition)
			.getOrElse { return failedExecution(it.message ?: "Unknown interaction error.") }
		val afterState = level.getBlockState(targetPosition)
		if (!interactionResult.consumesAction() && beforeState == afterState) {
			return failedExecution("Block did not respond to interaction.")
		}

		return AiToolExecution(
			payload = linkedMapOf(
				"position" to formatPos(targetPosition),
				"result" to interactionResultName(interactionResult),
				"changed_state" to (beforeState != afterState),
				"block_before" to BuiltInRegistries.BLOCK.getKey(beforeState.block).toString(),
				"properties_before" to blockProperties(beforeState),
				"block_after" to BuiltInRegistries.BLOCK.getKey(afterState.block).toString(),
				"properties_after" to blockProperties(afterState),
				"signal" to directionalSignals(level, targetPosition),
				"direct_signal" to directionalDirectSignals(level, targetPosition),
				"best_neighbor_signal" to level.getBestNeighborSignal(targetPosition),
			)
		)
	}

	fun observeState(
		playerId: UUID?,
		position: String,
		tickCount: String,
	): AiToolExecution {
		val source = toolContext(playerId).getOrElse { return failedExecution(it.message ?: "Unknown tool context error.") }
		val targetPosition = parseBlockPos(source, position).getOrElse { return failedExecution("Invalid position: $position") }
		if (!isAllowedPosition(playerId, source, targetPosition)) {
			return failedExecution("Requested position is outside the active sandbox.")
		}

		val requestedTicks = parseObservationTicks(tickCount)
			.getOrElse { return failedExecution(it.message ?: "Invalid tick count.") }
		val level = source.level
		if (!level.isLoaded(targetPosition)) {
			return failedExecution("Requested position is not currently loaded.")
		}

		val observation = ActiveObservation(
			id = UUID.randomUUID(),
			dimension = level.dimension(),
			position = targetPosition.immutable(),
			totalTicks = requestedTicks,
			future = CompletableFuture(),
		)
		source.server.execute {
			startObservation(source.server, observation)
		}

		val timeoutMillis = 5_000L + (requestedTicks.toLong() * 200L)
		val snapshots = runCatching {
			observation.future.get(timeoutMillis, TimeUnit.MILLISECONDS)
		}.getOrElse { error ->
			activeObservations.remove(observation.id)
			return failedExecution(error.message ?: "State observation timed out.")
		}

		return AiToolExecution(
			payload = linkedMapOf(
				"position" to formatPos(targetPosition),
				"observed_ticks" to requestedTicks,
				"states" to describeObservedStates(snapshots),
			)
		)
	}

	fun placeBlock(
		playerId: UUID?,
		position: String,
		block: String,
		properties: String?,
	): AiToolExecution {
		val source = toolContext(playerId).getOrElse { return failedExecution(it.message ?: "Unknown tool context error.") }
		val targetPosition = parseBlockPos(source, position).getOrElse { return failedExecution("Invalid position: $position") }
		if (!isAllowedPosition(playerId, source, targetPosition)) {
			return failedExecution("Requested position is outside the active sandbox.")
		}
		val exclusionNames = exclusionNamesAt(playerId, source, targetPosition)
		if (exclusionNames.isNotEmpty()) {
			return skippedExecution(
				message = "Skipped placement because the target block is a sandbox exclusion block.",
				payload = linkedMapOf(
					"position" to formatPos(targetPosition),
					"skipped" to true,
					"exclusions" to exclusionNames,
					"warning" to "Target block is an exclusion block and was left unchanged.",
				),
			)
		}

		val blockSpec = buildBlockSpec(block, properties)
			.getOrElse { return failedExecution(it.message ?: "Invalid block or block properties.") }
		return executeModificationCommand(
			playerId = playerId,
			command = "setblock ${formatPos(targetPosition)} $blockSpec",
		).getOrElse { failedExecution(it.message ?: "Unknown command execution error.") }
	}

	fun fillBlocks(
		playerId: UUID?,
		from: String,
		to: String,
		block: String,
		properties: String?,
	): AiToolExecution {
		val source = toolContext(playerId).getOrElse { return failedExecution(it.message ?: "Unknown tool context error.") }
		val fromPosition = parseBlockPos(source, from).getOrElse { return failedExecution("Invalid from position: $from") }
		val toPosition = parseBlockPos(source, to).getOrElse { return failedExecution("Invalid to position: $to") }

		val blockSpec = buildBlockSpec(block, properties)
			.getOrElse { return failedExecution(it.message ?: "Invalid block or block properties.") }
		val requestedRegion = SandboxRegion(
			firstCorner = fromPosition.immutable(),
			secondCorner = toPosition.immutable(),
		)
		val sandbox = playerId?.let(AiService::currentSandbox)?.getOrNull()
		if (sandbox == null) {
			return CommandToolsSupport.executeInternal(
				playerId = playerId,
				command = "fill ${formatPos(fromPosition)} ${formatPos(toPosition)} $blockSpec",
			).getOrElse { failedExecution(it.message ?: "Unknown command execution error.") }
		}
		if (sandbox.dimension != source.level.dimension()) {
			return failedExecution("Requested fill area is outside the active sandbox.")
		}
		if (!sandbox.boundary.fullyContains(requestedRegion)) {
			return failedExecution("Requested fill area extends outside the active sandbox.")
		}

		val excludedEntries = sandbox.exclusionEntriesInside(requestedRegion)
		if (excludedEntries.isEmpty()) {
			return executeModificationCommand(
				playerId = playerId,
				command = "fill ${formatPos(fromPosition)} ${formatPos(toPosition)} $blockSpec",
			).getOrElse { failedExecution(it.message ?: "Unknown command execution error.") }
		}

		val remainingRegions = splitRegionAroundExclusions(requestedRegion, excludedEntries.map { it.position }.distinct())
		for (region in remainingRegions) {
			executeModificationCommand(
				playerId = playerId,
				command = "fill ${formatPos(region.minCorner())} ${formatPos(region.maxCorner())} $blockSpec",
			).getOrElse { return failedExecution(it.message ?: "Unknown command execution error.") }
		}

		val warnings = excludedEntries.map { entry ->
			"Skipped exclusion block ${entry.name} at ${formatPos(entry.position)}."
		}
		return skippedExecution(
			message = "Fill completed with exclusion blocks skipped.",
			payload = linkedMapOf(
				"from" to formatPos(fromPosition),
				"to" to formatPos(toPosition),
				"skipped" to excludedEntries.map { entry ->
					mapOf(
						"name" to entry.name,
						"position" to formatPos(entry.position),
					)
				},
				"warnings" to warnings,
				"filled_regions" to remainingRegions.map { region ->
					mapOf(
						"from" to formatPos(region.minCorner()),
						"to" to formatPos(region.maxCorner()),
					)
				},
			),
		)
	}

	private fun parseObservationTicks(rawTickCount: String): Result<Int> {
		val trimmed = rawTickCount.trim()
		if (trimmed.isBlank()) {
			return Result.failure(IllegalArgumentException("Tick count is required."))
		}

		val value = trimmed.toIntOrNull()
			?: return Result.failure(IllegalArgumentException("Tick count must be a whole number between 0 and 1200."))
		if (value !in 0..1200) {
			return Result.failure(IllegalArgumentException("Tick count must be between 0 and 1200."))
		}
		return Result.success(value)
	}

	private fun startObservation(server: net.minecraft.server.MinecraftServer, observation: ActiveObservation) {
		val initialState = sampleObservedState(server, observation.dimension, observation.position)
			.getOrElse {
				observation.future.completeExceptionally(it)
				return
			}
		observation.snapshots += ObservedSnapshot(
			tick = 0,
			state = initialState,
		)
		observation.previousState = initialState

		if (observation.totalTicks == 0) {
			observation.future.complete(observation.snapshots.toList())
			return
		}

		activeObservations[observation.id] = observation
	}

	private fun advanceObservations(server: net.minecraft.server.MinecraftServer) {
		for (observation in activeObservations.values.toList()) {
			val nextTick = observation.elapsedTicks + 1
			val stateResult = sampleObservedState(server, observation.dimension, observation.position)
			if (stateResult.isFailure) {
				val error = stateResult.exceptionOrNull() ?: IllegalStateException("Unknown observation error.")
				activeObservations.remove(observation.id)
				observation.future.completeExceptionally(error)
				continue
			}
			val state = stateResult.getOrThrow()
			if (observation.previousState != state) {
				observation.snapshots += ObservedSnapshot(
					tick = nextTick,
					state = state,
				)
				observation.previousState = state
			}
			observation.elapsedTicks = nextTick
			if (observation.elapsedTicks >= observation.totalTicks) {
				activeObservations.remove(observation.id)
				observation.future.complete(observation.snapshots.toList())
			}
		}
	}

	private fun sampleObservedState(
		server: net.minecraft.server.MinecraftServer,
		dimension: net.minecraft.resources.ResourceKey<Level>,
		position: BlockPos,
	): Result<ObservedBlockState> {
		val level = server.getLevel(dimension)
			?: return Result.failure(IllegalStateException("Observation dimension is no longer available."))
		if (!level.isLoaded(position)) {
			return Result.success(
				ObservedBlockState(
					blockId = "unloaded",
					properties = emptyMap(),
				)
			)
		}

		val blockState = level.getBlockState(position)
		return Result.success(
			ObservedBlockState(
				blockId = BuiltInRegistries.BLOCK.getKey(blockState.block).toString(),
				properties = blockProperties(blockState),
			)
		)
	}

	private fun describeObservedStates(snapshots: List<ObservedSnapshot>): List<Map<String, Any>> {
		if (snapshots.isEmpty()) {
			return emptyList()
		}

		val segments = mutableListOf<List<ObservedSnapshot>>()
		var currentSegment = mutableListOf(snapshots.first())
		for (snapshot in snapshots.drop(1)) {
			if (snapshot.state.blockId == currentSegment.last().state.blockId) {
				currentSegment += snapshot
			} else {
				segments += currentSegment
				currentSegment = mutableListOf(snapshot)
			}
		}
		segments += currentSegment

		return buildList {
			for (segment in segments) {
				val relevantKeys = relevantPropertyKeys(segment)
				for ((index, snapshot) in segment.withIndex()) {
					val entry = linkedMapOf<String, Any>(
						"tick" to snapshot.tick,
					)
					if (index == 0) {
						entry["block"] = snapshot.state.blockId
						if (relevantKeys.isNotEmpty()) {
							entry["properties"] = relevantKeys.associateWith { key ->
								snapshot.state.properties[key].orEmpty()
							}
						}
					} else {
						val previousState = segment[index - 1].state
						val changedProperties = linkedMapOf<String, String>()
						for (key in relevantKeys) {
							val previousValue = previousState.properties[key]
							val currentValue = snapshot.state.properties[key]
							if (previousValue != currentValue) {
								changedProperties[key] = currentValue.orEmpty()
							}
						}
						if (changedProperties.isNotEmpty()) {
							entry["properties"] = changedProperties
						}
					}
					add(entry)
				}
			}
		}
	}

	private fun relevantPropertyKeys(segment: List<ObservedSnapshot>): List<String> {
		if (segment.size <= 1) {
			return emptyList()
		}

		val relevantKeys = linkedSetOf<String>()
		for ((previous, current) in segment.zipWithNext()) {
			for (key in previous.state.properties.keys + current.state.properties.keys) {
				if (previous.state.properties[key] != current.state.properties[key]) {
					relevantKeys += key
				}
			}
		}
		return relevantKeys.sorted()
	}

	private fun toolContext(playerId: UUID?): Result<CommandSourceStack> {
		val server = OpenBlock.currentServer().getOrElse {
			return Result.failure(it)
		}
		if (playerId == null) {
			return Result.success(CommandToolsSupport.createCommandSource(server, null))
		}

		val player = server.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Bound player is not online."))
		return Result.success(player.createCommandSourceStack())
	}

	private fun parseBlockPos(source: CommandSourceStack, rawPosition: String): Result<BlockPos> {
		val trimmed = rawPosition.trim()
		if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) {
			return Result.failure(IllegalArgumentException("Position must be a comma-separated x,y,z string."))
		}

		val parts = trimmed.split(',')
		if (parts.size != 3 || parts.any { part -> part.isBlank() }) {
			return Result.failure(IllegalArgumentException("Position must be a comma-separated x,y,z string."))
		}
		val normalized = parts.joinToString(" ")

		return runCatching {
				BlockPosArgument.blockPos()
					.parse(StringReader(normalized))
					.getBlockPos(source)
		}.fold(
			onSuccess = { Result.success(it) },
			onFailure = {
				val message = if (it is CommandSyntaxException) {
					"Position must be a valid block position."
				} else {
					it.message ?: "Position must be a valid block position."
				}
				Result.failure(IllegalArgumentException(message, it))
			},
		)
	}

	private fun blockIdOrNull(blockState: net.minecraft.world.level.block.state.BlockState): String? {
		return if (blockState.isAir) null else BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
	}

	private fun areaPalette(blocks: List<List<List<String?>>>): AreaPalette {
		val uniqueBlocks = linkedSetOf<String?>()
		uniqueBlocks += null
		for (layer in blocks) {
			for (row in layer) {
				for (blockId in row) {
					uniqueBlocks += blockId
				}
			}
		}

		val tokenByBlock = linkedMapOf<String?, String>()
		val legend = linkedMapOf<String, String?>()
		val tokenWidth = tokenWidth(uniqueBlocks.size - 1)
		val emptyToken = AREA_EMPTY_TOKEN.toString().repeat(tokenWidth)
		tokenByBlock[null] = emptyToken
		legend[emptyToken] = null

		var blockIndex = 0
		for (blockId in uniqueBlocks) {
			if (blockId == null) {
				continue
			}
			val token = encodeAreaToken(blockIndex++, tokenWidth)
			tokenByBlock[blockId] = token
			legend[token] = blockId
		}

		return AreaPalette(
			tokenByBlock = tokenByBlock,
			legend = legend,
			tokenWidth = tokenWidth,
		)
	}

	private fun tokenWidth(nonNullBlockCount: Int): Int {
		if (nonNullBlockCount <= 0) {
			return 1
		}

		var width = 1
		var capacity = AREA_PALETTE_ALPHABET.length
		while (nonNullBlockCount > capacity) {
			width += 1
			capacity *= AREA_PALETTE_ALPHABET.length
		}
		return width
	}

	private fun encodeAreaToken(index: Int, width: Int): String {
		val base = AREA_PALETTE_ALPHABET.length
		var value = index
		val chars = CharArray(width) { AREA_PALETTE_ALPHABET[0] }
		for (tokenIndex in width - 1 downTo 0) {
			chars[tokenIndex] = AREA_PALETTE_ALPHABET[value % base]
			value /= base
		}
		return String(chars)
	}

	private fun isAllowedPosition(playerId: UUID?, source: CommandSourceStack, position: BlockPos): Boolean {
		val scopedPlayerId = playerId ?: return true
		val sandbox = AiService.currentSandbox(scopedPlayerId).getOrNull() ?: return true
		return sandbox.contains(source.level.dimension(), position)
	}

	private fun exclusionNamesAt(playerId: UUID?, source: CommandSourceStack, position: BlockPos): List<String> {
		val sandbox = playerId?.let(AiService::currentSandbox)?.getOrNull() ?: return emptyList()
		if (sandbox.dimension != source.level.dimension()) {
			return emptyList()
		}

		return sandbox.exclusions
			.filterValues { it == position }
			.keys
			.sorted()
	}

	private fun isAllowedArea(
		playerId: UUID?,
		source: CommandSourceStack,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Boolean {
		val scopedPlayerId = playerId ?: return true
		val sandbox = AiService.currentSandbox(scopedPlayerId).getOrNull() ?: return true
		return sandbox.containsArea(source.level.dimension(), firstCorner, secondCorner)
	}

	private fun buildBlockSpec(block: String, properties: String?): Result<String> {
		val normalizedBlock = block.trim()
		if (normalizedBlock.isBlank() || ' ' in normalizedBlock) {
			return Result.failure(IllegalArgumentException("Invalid block id."))
		}

		val normalizedProperties = normalizeProperties(properties).getOrElse { return Result.failure(it) }
		return Result.success(normalizedBlock + normalizedProperties)
	}

	private fun normalizeProperties(rawProperties: String?): Result<String> {
		val trimmed = rawProperties?.trim().orEmpty()
		if (trimmed.isBlank()) return Result.success("")
		if (trimmed.startsWith("{")) return jsonProperties(trimmed)
		val body = trimmed.removePrefix("[").removeSuffix("]").trim()
		if (body.isBlank()) return Result.success("")
		return Result.success("[$body]")
	}

	private fun jsonProperties(rawProperties: String): Result<String> {
		val element = runCatching { JsonParser.parseString(rawProperties) }
			.getOrElse { return Result.failure(IllegalArgumentException("Properties must be valid JSON.", it)) }
		if (!element.isJsonObject) {
			return Result.failure(IllegalArgumentException("Properties JSON must be an object."))
		}

		val entries = mutableListOf<String>()
		for ((key, value) in element.asJsonObject.entrySet()) {
			val propertyValue = propertyValue(value).getOrElse { return Result.failure(it) }
			if (key.isBlank()) {
				return Result.failure(IllegalArgumentException("Property names cannot be blank."))
			}
			entries += "$key=$propertyValue"
		}
		if (entries.isEmpty()) {
			return Result.success("")
		}

		return Result.success(entries.joinToString(prefix = "[", postfix = "]", separator = ","))
	}

	private fun propertyValue(value: JsonElement): Result<String> {
		if (!value.isJsonPrimitive) {
			return Result.failure(IllegalArgumentException("Property values must be primitive JSON values."))
		}

		val primitive = value.asJsonPrimitive
		return when {
			primitive.isBoolean -> Result.success(primitive.asBoolean.toString())
			primitive.isNumber -> Result.success(primitive.asNumber.toString())
			primitive.isString -> Result.success(primitive.asString)
			else -> Result.failure(IllegalArgumentException("Unsupported property value."))
		}
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

	private fun simulateInteraction(level: ServerLevel, targetPosition: BlockPos): Result<InteractionResult> {
		val actor = interactionActor(level, targetPosition).getOrElse { return Result.failure(it) }
		val hitResult = interactionHitResult(targetPosition)
		return runCatching {
			actor.gameMode.useItemOn(actor, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hitResult)
		}
	}

	private fun interactionActor(level: ServerLevel, targetPosition: BlockPos): Result<ServerPlayer> {
		return runCatching {
			val profile = GameProfile(INTERACTION_PLAYER_UUID, INTERACTION_PLAYER_NAME)
			val actor = ServerPlayer(
				level.server,
				level,
				profile,
				ClientInformation.createDefault(),
			)
			actor.connection = ServerGamePacketListenerImpl(
				level.server,
				Connection(PacketFlow.SERVERBOUND),
				actor,
				CommonListenerCookie.createInitial(profile, false),
			)
			actor.setGameMode(GameType.CREATIVE)
			actor.abilities.mayBuild = true
			actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
			actor.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY)
			actor.setPos(interactionActorPosition(targetPosition))
            actor.yRot = INTERACTION_FACING.toYRot()
            actor.xRot = 0f
			actor.setYHeadRot(INTERACTION_FACING.toYRot())
			actor.setYBodyRot(INTERACTION_FACING.toYRot())
			actor
		}
	}

	private fun interactionActorPosition(targetPosition: BlockPos): Vec3 {
		return Vec3(
			targetPosition.x + 0.5,
			targetPosition.y + 0.5,
			targetPosition.z + 1.75,
		)
	}

	private fun interactionHitResult(targetPosition: BlockPos): BlockHitResult {
		return BlockHitResult(
			Vec3(
				targetPosition.x + 0.5,
				targetPosition.y + 0.5,
				targetPosition.z + 0.999,
			),
			INTERACTION_FACE,
			targetPosition,
			false,
		)
	}

	private fun interactionResultName(result: InteractionResult): String {
		return when (result) {
			InteractionResult.SUCCESS -> "success"
			InteractionResult.SUCCESS_SERVER -> "success_server"
			InteractionResult.CONSUME -> "consume"
			InteractionResult.FAIL -> "fail"
			InteractionResult.PASS -> "pass"
			InteractionResult.TRY_WITH_EMPTY_HAND -> "try_with_empty_hand"
			else -> result.toString().lowercase()
		}
	}

	private fun directionalSignals(level: ServerLevel, position: BlockPos): Map<String, Int> {
		return Direction.entries.associate { direction ->
			direction.name.lowercase() to level.getSignal(position, direction)
		}
	}

	private fun directionalDirectSignals(level: ServerLevel, position: BlockPos): Map<String, Int> {
		return Direction.entries.associate { direction ->
			direction.name.lowercase() to level.getDirectSignal(position, direction)
		}
	}

	private fun formatPos(position: BlockPos): String {
		return "${position.x} ${position.y} ${position.z}"
	}

	private fun skippedExecution(
		message: String,
		payload: Map<String, Any?>,
	): AiToolExecution {
		return AiToolExecution(
			payload = linkedMapOf<String, Any?>(
				"message" to message,
				"warning" to true,
			).apply {
				putAll(payload)
			},
		)
	}

	private fun failedExecution(message: String): AiToolExecution {
		return AiToolExecution(
			payload = mapOf("message" to message),
			isError = true,
		)
	}

	private fun executeModificationCommand(playerId: UUID?, command: String): Result<AiToolExecution> {
		val execution = CommandToolsSupport.executeInternal(playerId, command).getOrElse { return Result.failure(it) }
		if (execution.isError) {
			return Result.failure(IllegalStateException(execution.payload["message"] as? String ?: "Command execution failed."))
		}

		val succeeded = execution.payload["success"] as? Boolean ?: true
		if (!succeeded) {
			val output = execution.payload["output"] as? List<*>
			val message = output
				?.joinToString("\n") { it?.toString().orEmpty() }
				?.ifBlank { null }
				?: "Command execution failed."
			return Result.failure(IllegalStateException(message))
		}

		return Result.success(execution)
	}

	private fun splitRegionAroundExclusions(region: SandboxRegion, exclusions: List<BlockPos>): List<SandboxRegion> {
		var remainingRegions = listOf(region)
		for (exclusion in exclusions) {
			remainingRegions = remainingRegions.flatMap { current ->
				if (!current.contains(exclusion)) {
					listOf(current)
				} else {
					splitRegionAroundPosition(current, exclusion)
				}
			}
		}
		return remainingRegions
	}

	private fun splitRegionAroundPosition(region: SandboxRegion, exclusion: BlockPos): List<SandboxRegion> {
		val excludedRegion = SandboxRegion(
			firstCorner = exclusion.immutable(),
			secondCorner = exclusion.immutable(),
		)
		val pieces = mutableListOf<SandboxRegion>()

		fun addRegion(first: BlockPos, second: BlockPos) {
			val candidate = SandboxRegion(firstCorner = first.immutable(), secondCorner = second.immutable())
			if (!candidate.fullyContains(excludedRegion) && candidate.minX <= candidate.maxX && candidate.minY <= candidate.maxY && candidate.minZ <= candidate.maxZ) {
				pieces += candidate
			}
		}

		if (region.minX <= exclusion.x - 1) {
			addRegion(
				BlockPos(region.minX, region.minY, region.minZ),
				BlockPos(exclusion.x - 1, region.maxY, region.maxZ),
			)
		}
		if (exclusion.x + 1 <= region.maxX) {
			addRegion(
				BlockPos(exclusion.x + 1, region.minY, region.minZ),
				BlockPos(region.maxX, region.maxY, region.maxZ),
			)
		}

		val middleMinX = maxOf(region.minX, exclusion.x)
		val middleMaxX = minOf(region.maxX, exclusion.x)
		if (region.minY <= exclusion.y - 1) {
			addRegion(
				BlockPos(middleMinX, region.minY, region.minZ),
				BlockPos(middleMaxX, exclusion.y - 1, region.maxZ),
			)
		}
		if (exclusion.y + 1 <= region.maxY) {
			addRegion(
				BlockPos(middleMinX, exclusion.y + 1, region.minZ),
				BlockPos(middleMaxX, region.maxY, region.maxZ),
			)
		}

		val middleMinY = maxOf(region.minY, exclusion.y)
		val middleMaxY = minOf(region.maxY, exclusion.y)
		if (region.minZ <= exclusion.z - 1) {
			addRegion(
				BlockPos(middleMinX, middleMinY, region.minZ),
				BlockPos(middleMaxX, middleMaxY, exclusion.z - 1),
			)
		}
		if (exclusion.z + 1 <= region.maxZ) {
			addRegion(
				BlockPos(middleMinX, middleMinY, exclusion.z + 1),
				BlockPos(middleMaxX, middleMaxY, region.maxZ),
			)
		}

		return pieces
	}

	private fun linePositions(start: BlockPos, end: BlockPos): List<BlockPos> {
		val deltaX = end.x - start.x
		val deltaY = end.y - start.y
		val deltaZ = end.z - start.z
		val steps = maxOf(abs(deltaX), abs(deltaY), abs(deltaZ))
		if (steps == 0) {
			return listOf(start)
		}

		val positions = mutableListOf<BlockPos>()
		for (step in 0..steps) {
			val progress = step.toDouble() / steps.toDouble()
			val x = round(start.x + (deltaX * progress)).toInt()
			val y = round(start.y + (deltaY * progress)).toInt()
			val z = round(start.z + (deltaZ * progress)).toInt()
			val position = BlockPos(x, y, z)
			if (positions.lastOrNull() != position) {
				positions += position
			}
		}
		return positions
	}

	private enum class ReadMode {
		AREA,
		RAY,
	}

	private data class AreaPalette(
		val tokenByBlock: Map<String?, String>,
		val legend: Map<String, String?>,
		val tokenWidth: Int,
	) {
		fun tokenFor(blockId: String?): String {
			val token: String? = tokenByBlock[blockId]
			return token ?: kotlin.error("Missing palette token for $blockId")
		}
	}

	private data class ActiveObservation(
		val id: UUID,
		val dimension: net.minecraft.resources.ResourceKey<Level>,
		val position: BlockPos,
		val totalTicks: Int,
		val future: CompletableFuture<List<ObservedSnapshot>>,
		val snapshots: MutableList<ObservedSnapshot> = mutableListOf(),
		var previousState: ObservedBlockState? = null,
		var elapsedTicks: Int = 0,
	)

	private data class ObservedSnapshot(
		val tick: Int,
		val state: ObservedBlockState,
	)

	private data class ObservedBlockState(
		val blockId: String,
		val properties: Map<String, String>,
	)
}
