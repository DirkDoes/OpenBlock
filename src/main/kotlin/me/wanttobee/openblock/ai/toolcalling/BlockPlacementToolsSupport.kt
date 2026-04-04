package me.wanttobee.openblock.ai.toolcalling

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.sandbox.SandboxManager
import me.wanttobee.openblock.sandbox.SandboxRegion
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round
import kotlin.streams.toList

object BlockPlacementToolsSupport {
	private const val AREA_EMPTY_TOKEN = '.'
	private const val AREA_PALETTE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&()*+-:;<=>?@[]{|}~"

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
		val properties = blockState.getValues()
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

		return AiToolExecution(
			payload = linkedMapOf(
				"position" to formatPos(targetPosition),
				"block" to blockId,
				"is_air" to blockState.isAir,
				"properties" to properties,
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

		val blockSpec = buildBlockSpec(block, properties)
			.getOrElse { return failedExecution(it.message ?: "Invalid block or block properties.") }
		return CommandToolsSupport.executeInternal(
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
		val sandbox = playerId?.let { scopedPlayerId -> SandboxManager.getSandbox(scopedPlayerId).getOrNull() }
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
		return CommandToolsSupport.executeInternal(
			playerId = playerId,
			command = "fill ${formatPos(fromPosition)} ${formatPos(toPosition)} $blockSpec",
		).getOrElse { failedExecution(it.message ?: "Unknown command execution error.") }
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

		return try {
			Result.success(
				BlockPosArgument.blockPos()
				.parse(StringReader(normalized))
				.getBlockPos(source)
			)
		} catch (_: CommandSyntaxException) {
			Result.failure(IllegalArgumentException("Position must be a valid block position."))
		}
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
		return SandboxManager.isAllowed(scopedPlayerId, source.level.dimension(), position)
	}

	private fun isAllowedArea(
		playerId: UUID?,
		source: CommandSourceStack,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Boolean {
		val scopedPlayerId = playerId ?: return true
		return SandboxManager.isAreaAllowed(scopedPlayerId, source.level.dimension(), firstCorner, secondCorner)
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
		val element = try {
			JsonParser.parseString(rawProperties)
		} catch (_: Exception) {
			return Result.failure(IllegalArgumentException("Properties must be valid JSON."))
		}
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

	private fun formatPos(position: BlockPos): String {
		return "${position.x} ${position.y} ${position.z}"
	}

	private fun failedExecution(message: String): AiToolExecution {
		return AiToolExecution(
			payload = mapOf("message" to message),
			isError = true,
		)
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
}
