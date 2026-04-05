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

object WatchTool : AiTool {
	private val positionParameter = AiToolParameter(
		name = "position",
		description = "Block position to watch. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /ob-invoke-tool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)
	private val tickCountParameter = AiToolParameter(
		name = "tick_count",
		description = "How many ticks to watch this block for. Must be between 0 and 1200.",
	)

	override val name = "watch"
	override val description =
		"Watches one block for a number of ticks and returns only the initial state plus the later state changes. Useful for lamps, doors, pistons, redstone components, and similar time-based behavior."
	override val enabledByDefault = true
	override val runsAsync = true
	override val parameters = listOf(positionParameter, tickCountParameter)
	override val menuIcon = Items.OBSERVER

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		return when (parameterIndex) {
			0 -> ToolPositionSuggestions.positionSuggestions(playerId)
			1 -> Result.success(
				listOf(
					AiToolSuggestion("20", "1 second"),
					AiToolSuggestion("40", "2 seconds"),
					AiToolSuggestion("80", "4 seconds"),
					AiToolSuggestion("200", "10 seconds"),
					AiToolSuggestion("1200", "1 minute"),
				)
			)
			else -> Result.success(emptyList())
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		val tickCount = arguments.get<String>(tickCountParameter.name).getOrElse { return Result.failure(it) }
		return Result.success("watching block state at $position for $tickCount ticks")
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		val tickCount = arguments.get<String>(tickCountParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(
			BlockPlacementToolsSupport.observeState(
				playerId = boundedPlayerId,
				position = position,
				tickCount = tickCount,
			)
		)
	}
}
