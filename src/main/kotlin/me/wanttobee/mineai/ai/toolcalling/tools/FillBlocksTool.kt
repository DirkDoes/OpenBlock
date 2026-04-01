package me.wanttobee.mineai.ai.toolcalling.tools

import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.BlockPlacementToolsSupport
import me.wanttobee.mineai.ai.toolcalling.ToolArguments
import me.wanttobee.mineai.ai.toolcalling.ToolPositionSuggestions
import java.util.UUID

object FillBlocksTool : AiTool {
	private val blockParameter = AiTool.Parameter(
		name = "block",
		description = "Block id to fill with, for example minecraft:stone or oak_planks.",
	)
	private val fromParameter = AiTool.Parameter(
		name = "from",
		description = "First corner. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiTool.ManualInput.BLOCK_POS,
	)
	private val toParameter = AiTool.Parameter(
		name = "to",
		description = "Second corner. AI tool calls should send x,y,z with no spaces, for example 20,64,-3 or ^,^,^20. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiTool.ManualInput.BLOCK_POS,
	)
	private val propertiesParameter = AiTool.Parameter(
		name = "properties",
		description = "Optional block-state properties as facing=north,half=top or as a JSON object string like {\"facing\":\"north\",\"half\":\"top\"}.",
		required = false,
	)

	override val name = "fill_blocks"
	override val description =
		"Fills a rectangular area with one block state. The entire volume must stay inside the sandbox."
	override val enabledByDefault = true
	override val parameters = listOf(blockParameter, fromParameter, toParameter, propertiesParameter)

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): List<AiTool.Suggestion> {
		return when (parameterIndex) {
			0 -> listOf(
				AiTool.Suggestion("minecraft:stone"),
				AiTool.Suggestion("minecraft:oak_planks"),
				AiTool.Suggestion("minecraft:glass"),
				AiTool.Suggestion("minecraft:air"),
			)
			1, 2 -> ToolPositionSuggestions.positionSuggestions(playerId)
			else -> emptyList()
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): String? {
		return "filling ${arguments.get<String>(fromParameter.name)} -> ${arguments.get<String>(toParameter.name)} with ${arguments.get<String>(blockParameter.name)}"
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): AiTool.ExecutionResult {
		return BlockPlacementToolsSupport.fillBlocks(
			playerId = playerId,
			from = arguments.get(fromParameter.name),
			to = arguments.get(toParameter.name),
			block = arguments.get(blockParameter.name),
			properties = arguments.getOrNull(propertiesParameter.name),
		)
	}
}
