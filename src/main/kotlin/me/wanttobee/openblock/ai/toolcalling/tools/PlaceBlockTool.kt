package me.wanttobee.openblock.ai.toolcalling.tools

import me.wanttobee.openblock.ai.toolcalling.BlockPlacementToolsSupport
import me.wanttobee.openblock.ai.toolcalling.ToolPositionSuggestions
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import net.minecraft.world.item.Items
import java.util.UUID

object PlaceBlockTool : AiTool {
	private val blockParameter = AiToolParameter(
		name = "block",
		description = "Block id to place, for example minecraft:stone or oak_stairs.",
	)
	private val positionParameter = AiToolParameter(
		name = "position",
		description = "Block position. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)
	private val propertiesParameter = AiToolParameter(
		name = "properties",
		description = "Optional block-state properties as facing=north,half=top or as a JSON object string like {\"facing\":\"north\",\"half\":\"top\"}.",
		required = false,
	)

	override val name = "place_block"
	override val description =
		"Places one block at a position. The optional properties field can describe facing, half, shape, waterlogged, or fence and wall connections."
	override val enabledByDefault = true
	override val parameters = listOf(blockParameter, positionParameter, propertiesParameter)
	override val menuIcon = Items.STONE

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		return when (parameterIndex) {
			0 -> Result.success(listOf(
				AiToolSuggestion("minecraft:stone"),
				AiToolSuggestion("minecraft:oak_planks"),
				AiToolSuggestion("minecraft:oak_stairs"),
				AiToolSuggestion("minecraft:oak_slab"),
			))
			1 -> ToolPositionSuggestions.positionSuggestions(playerId)
			else -> Result.success(emptyList())
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val block = arguments.get<String>(blockParameter.name).getOrElse { return Result.failure(it) }
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return Result.success("placing $block at $position")
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		val block = arguments.get<String>(blockParameter.name).getOrElse { return Result.failure(it) }
		val properties = arguments.getOrNull<String>(propertiesParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(BlockPlacementToolsSupport.placeBlock(
			playerId = boundedPlayerId,
			position = position,
			block = block,
			properties = properties,
		))
	}
}
