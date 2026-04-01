package me.wanttobee.openblock.ai.toolcalling.tools

import me.wanttobee.openblock.ai.toolcalling.AiTool
import me.wanttobee.openblock.ai.toolcalling.BlockPlacementToolsSupport
import me.wanttobee.openblock.ai.toolcalling.ToolArguments
import me.wanttobee.openblock.ai.toolcalling.ToolPositionSuggestions
import java.util.UUID

object GetBlocksTool : AiTool {
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
	private val modeParameter = AiTool.Parameter(
		name = "mode",
		description = "Read mode: 'area' returns compact palette-encoded Y layers for the entire cuboid, 'ray' returns only the blocks encountered while lerping from from to to.",
	)

	override val name = "get_blocks"
	override val description =
		"Reads blocks between two positions. Use mode=area for compact palette-encoded layers or mode=ray for one traced list from start to end."
	override val enabledByDefault = true
	override val parameters = listOf(fromParameter, toParameter, modeParameter)

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): List<AiTool.Suggestion> {
		return when (parameterIndex) {
			0, 1 -> ToolPositionSuggestions.positionSuggestions(playerId)
			2 -> listOf(
				AiTool.Suggestion("area", "Read the entire cuboid as compact encoded layers"),
				AiTool.Suggestion("ray", "Read only the lerped path from start to end"),
			)
			else -> emptyList()
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): String? {
		val mode = arguments.get<String>(modeParameter.name)
		return "reading blocks ($mode) in ${arguments.get<String>(fromParameter.name)} -> ${arguments.get<String>(toParameter.name)}"
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): AiTool.ExecutionResult {
		return BlockPlacementToolsSupport.getBlocks(
			playerId = playerId,
			from = arguments.get(fromParameter.name),
			to = arguments.get(toParameter.name),
			mode = arguments.get(modeParameter.name),
		)
	}
}
