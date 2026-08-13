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

object GetBlocksTool : AiTool {
	private val fromParameter = AiToolParameter(
		name = "from",
		description = "First corner. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^. Manual /ob-invoke-tool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)
	private val toParameter = AiToolParameter(
		name = "to",
		description = "Second corner. AI tool calls should send x,y,z with no spaces, for example 20,64,-3 or ^,^,^20. Manual /ob-invoke-tool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)
	private val modeParameter = AiToolParameter(
		name = "mode",
		description = "Read mode: 'area' returns compact palette-encoded Y layers for the entire cuboid, 'ray' returns only the blocks encountered while lerping from from to to.",
	)

	override val name = "get_blocks"
	override val description =
		"Reads blocks between two positions. Use mode=area for compact palette-encoded layers or mode=ray for one traced list from start to end."
	override val enabledByDefault = true
	override val parameters = listOf(fromParameter, toParameter, modeParameter)
	override val menuIcon = Items.MAP

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		return when (parameterIndex) {
			0, 1 -> ToolPositionSuggestions.positionSuggestions(playerId)
			2 -> Result.success(listOf(
				AiToolSuggestion("area", "Read the entire cuboid as compact encoded layers"),
				AiToolSuggestion("ray", "Read only the lerped path from start to end"),
			))
			else -> Result.success(emptyList())
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val mode = arguments.get<String>(modeParameter.name).getOrElse { return Result.failure(it) }
		val from = arguments.get<String>(fromParameter.name).getOrElse { return Result.failure(it) }
		val to = arguments.get<String>(toParameter.name).getOrElse { return Result.failure(it) }
		return Result.success("reading blocks ($mode) in $from -> $to")
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val from = arguments.get<String>(fromParameter.name).getOrElse { return Result.failure(it) }
		val to = arguments.get<String>(toParameter.name).getOrElse { return Result.failure(it) }
		val mode = arguments.get<String>(modeParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(BlockPlacementToolsSupport.getBlocks(
			playerId = boundedPlayerId,
			from = from,
			to = to,
			mode = mode,
		))
	}
}
