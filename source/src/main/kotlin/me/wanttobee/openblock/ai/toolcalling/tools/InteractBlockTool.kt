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

object InteractBlockTool : AiTool {
	private val positionParameter = AiToolParameter(
		name = "position",
		description = "Block position to right click. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /ob-invoke-tool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)

	override val name = "interact"
	override val description =
		"Simulates a server-side empty-hand right click on one block. Useful for buttons, levers, trapdoors, doors, fence gates, note blocks, and similar interactable blocks."
	override val enabledByDefault = true
	override val parameters = listOf(positionParameter)
	override val menuIcon = Items.STONE_BUTTON

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		if (parameterIndex != 0) {
			return Result.success(emptyList())
		}
		return ToolPositionSuggestions.positionSuggestions(playerId)
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return Result.success("interacting with block at $position")
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(BlockPlacementToolsSupport.interact(
			playerId = boundedPlayerId,
			position = position,
		))
	}
}
