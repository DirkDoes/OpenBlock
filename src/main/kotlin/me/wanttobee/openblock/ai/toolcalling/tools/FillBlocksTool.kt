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

object FillBlocksTool : AiTool {
	private val blockParameter = AiToolParameter(
		name = "block",
		description = "Block id to fill with, for example minecraft:stone or oak_planks.",
	)
	private val fromParameter = AiToolParameter(
		name = "from",
		description = "First corner. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)
	private val toParameter = AiToolParameter(
		name = "to",
		description = "Second corner. AI tool calls should send x,y,z with no spaces, for example 20,64,-3 or ^,^,^20. Manual /aitool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)
	private val propertiesParameter = AiToolParameter(
		name = "properties",
		description = "Optional block-state properties as facing=north,half=top or as a JSON object string like {\"facing\":\"north\",\"half\":\"top\"}.",
		required = false,
	)

	override val name = "fill_blocks"
	override val description =
		"Fills a rectangular area with one block state. The entire volume must stay inside the sandbox."
	override val enabledByDefault = true
	override val parameters = listOf(blockParameter, fromParameter, toParameter, propertiesParameter)
	override val menuIcon = Items.WOODEN_AXE

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		return Result.success(when (parameterIndex) {
			0 -> listOf(
				AiToolSuggestion("minecraft:stone"),
				AiToolSuggestion("minecraft:oak_planks"),
				AiToolSuggestion("minecraft:glass"),
				AiToolSuggestion("minecraft:air"),
			)
			1, 2 -> ToolPositionSuggestions.positionSuggestions(playerId)
			else -> emptyList()
		})
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val from = arguments.get<String>(fromParameter.name).getOrElse { return Result.failure(it) }
		val to = arguments.get<String>(toParameter.name).getOrElse { return Result.failure(it) }
		val block = arguments.get<String>(blockParameter.name).getOrElse { return Result.failure(it) }
		return Result.success("filling $from -> $to with $block")
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val from = arguments.get<String>(fromParameter.name).getOrElse { return Result.failure(it) }
		val to = arguments.get<String>(toParameter.name).getOrElse { return Result.failure(it) }
		val block = arguments.get<String>(blockParameter.name).getOrElse { return Result.failure(it) }
		val properties = arguments.getOrNull<String>(propertiesParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(BlockPlacementToolsSupport.fillBlocks(
			playerId = playerId,
			from = from,
			to = to,
			block = block,
			properties = properties,
		))
	}
}
