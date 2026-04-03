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

object GetBlockDetailsTool : AiTool {
	private val positionParameter = AiToolParameter(
		name = "position",
		description = "Block position. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)

	override val name = "get_block_details"
	override val description =
		"Reads one block at a position and returns its block id plus all exposed block-state properties like facing, half, powered, waterlogged, and similar details."
	override val enabledByDefault = true
	override val parameters = listOf(positionParameter)
	override val menuIcon = Items.GRASS_BLOCK

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		if (parameterIndex != 0) {
			return Result.success(emptyList())
		}
		return Result.success(ToolPositionSuggestions.positionSuggestions(playerId))
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return Result.success("reading block at $position")
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(BlockPlacementToolsSupport.getBlockDetails(
			playerId = playerId,
			position = position,
		))
	}
}
