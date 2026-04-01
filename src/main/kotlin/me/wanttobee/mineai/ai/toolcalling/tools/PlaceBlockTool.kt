package me.wanttobee.mineai.ai.toolcalling.tools

import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.BlockPlacementToolsSupport
import me.wanttobee.mineai.ai.toolcalling.ToolArguments
import me.wanttobee.mineai.ai.toolcalling.ToolPositionSuggestions
import java.util.UUID

object PlaceBlockTool : AiTool {
	private val blockParameter = AiTool.Parameter(
		name = "block",
		description = "Block id to place, for example minecraft:stone or oak_stairs.",
	)
	private val positionParameter = AiTool.Parameter(
		name = "position",
		description = "Block position. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiTool.ManualInput.BLOCK_POS,
	)
	private val propertiesParameter = AiTool.Parameter(
		name = "properties",
		description = "Optional block-state properties as facing=north,half=top or as a JSON object string like {\"facing\":\"north\",\"half\":\"top\"}.",
		required = false,
	)

	override val name = "place_block"
	override val description =
		"Places one block at a position. The optional properties field can describe facing, half, shape, waterlogged, or fence and wall connections."
	override val enabledByDefault = true
	override val parameters = listOf(blockParameter, positionParameter, propertiesParameter)

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): List<AiTool.Suggestion> {
		return when (parameterIndex) {
			0 -> listOf(
				AiTool.Suggestion("minecraft:stone"),
				AiTool.Suggestion("minecraft:oak_planks"),
				AiTool.Suggestion("minecraft:oak_stairs"),
				AiTool.Suggestion("minecraft:oak_slab"),
			)
			1 -> ToolPositionSuggestions.positionSuggestions(playerId)
			else -> emptyList()
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): String? {
		return "placing ${arguments.get<String>(blockParameter.name)} at ${arguments.get<String>(positionParameter.name)}"
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): AiTool.ExecutionResult {
		return BlockPlacementToolsSupport.placeBlock(
			playerId = playerId,
			position = arguments.get(positionParameter.name),
			block = arguments.get(blockParameter.name),
			properties = arguments.getOrNull(propertiesParameter.name),
		)
	}
}
