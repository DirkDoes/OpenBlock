package me.wanttobee.mineai.ai.toolcalling.tools

import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.BlockPlacementToolsSupport
import me.wanttobee.mineai.ai.toolcalling.ToolArguments
import me.wanttobee.mineai.ai.toolcalling.ToolPositionSuggestions
import java.util.UUID

object GetBlockDetailsTool : AiTool {
	private val positionParameter = AiTool.Parameter(
		name = "position",
		description = "Block position. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiTool.ManualInput.BLOCK_POS,
	)

	override val name = "get_block_details"
	override val description =
		"Reads one block at a position and returns its block id plus all exposed block-state properties like facing, half, powered, waterlogged, and similar details."
	override val enabledByDefault = true
	override val parameters = listOf(positionParameter)

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): List<AiTool.Suggestion> {
		if (parameterIndex != 0) {
			return emptyList()
		}
		return ToolPositionSuggestions.positionSuggestions(playerId)
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): String? {
		return "reading block at ${arguments.get<String>(positionParameter.name)}"
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): AiTool.ExecutionResult {
		return BlockPlacementToolsSupport.getBlockDetails(
			playerId = playerId,
			position = arguments.get(positionParameter.name),
		)
	}
}
