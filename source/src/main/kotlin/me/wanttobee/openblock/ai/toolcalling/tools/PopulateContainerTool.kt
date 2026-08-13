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

object PopulateContainerTool : AiTool {
	private val positionParameter = AiToolParameter(
		name = "position",
		description = "Block position. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /ob-invoke-tool usage accepts normal spaced Minecraft coordinates.",
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)
	private val clearFirstParameter = AiToolParameter(
		name = "clear_first",
		description = "Whether to clear the whole container before applying the listed entries. Must be true or false.",
	)
	private val entriesParameter = AiToolParameter(
		name = "entries",
		description = "JSON array of slot overrides, for example [{\"slot\":0,\"item\":\"minecraft:redstone\",\"count\":32}]. Each entry must include slot, item, and count.",
	)

	override val name = "populate_container"
	override val description =
		"Puts items into a container block such as a hopper, chest, barrel, dispenser, dropper, or furnace-like block. The listed entries overwrite the specified slots exactly."
	override val enabledByDefault = true
	override val parameters = listOf(positionParameter, clearFirstParameter, entriesParameter)
	override val menuIcon = Items.HOPPER

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		return when (parameterIndex) {
			0 -> ToolPositionSuggestions.positionSuggestions(playerId)
			1 -> Result.success(listOf(
				AiToolSuggestion("false"),
				AiToolSuggestion("true"),
			))
			2 -> Result.success(listOf(
				AiToolSuggestion("[{\"slot\":0,\"item\":\"minecraft:redstone\",\"count\":32}]"),
			))
			else -> Result.success(emptyList())
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return Result.success("populating container at $position")
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val position = arguments.get<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		val clearFirst = arguments.get<String>(clearFirstParameter.name).getOrElse { return Result.failure(it) }
		val entries = arguments.get<String>(entriesParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(BlockPlacementToolsSupport.populateContainer(
			playerId = boundedPlayerId,
			position = position,
			clearFirst = clearFirst,
			entries = entries,
		))
	}
}
