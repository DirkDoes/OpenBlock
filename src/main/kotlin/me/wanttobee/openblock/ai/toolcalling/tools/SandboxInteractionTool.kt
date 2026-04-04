package me.wanttobee.openblock.ai.toolcalling.tools

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.toolcalling.SandboxToolsSupport
import me.wanttobee.openblock.ai.toolcalling.ToolPositionSuggestions
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import net.minecraft.world.item.Items
import java.util.UUID

object SandboxInteractionTool : AiTool {
	private val actionParameter = AiToolParameter(
		name = "action",
		description = "Either add or remove.",
	)
	private val nameParameter = AiToolParameter(
		name = "name",
		description = "Unique sandbox interaction name.",
	)
	private val positionParameter = AiToolParameter(
		name = "position",
		description = "Block position used when action=add. AI tool calls should send x,y,z with no spaces, for example 10,64,-3 or ^,^1,^20. Manual /ob-invoke-tool usage accepts normal spaced Minecraft coordinates.",
		required = false,
		manualInput = AiToolParameter.ManualInput.BLOCK_POS,
	)

	override val name = "sandbox_interaction"
	override val description =
		"Adds or removes named sandbox interaction points inside the current sandbox. These points are shared with the session context and are intended for future automated testing flows."
	override val enabledByDefault = false
	override val parameters = listOf(actionParameter, nameParameter, positionParameter)
	override val menuIcon = Items.BELL

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		return when (parameterIndex) {
			0 -> Result.success(listOf(AiToolSuggestion("add"), AiToolSuggestion("remove")))
			1 -> {
				val action = arguments[actionParameter.name]?.trim()?.lowercase()
				if (action == "remove" && playerId != null) {
					AiService.sandboxInteractionNames(playerId).map { names ->
						names.map(::AiToolSuggestion)
					}
				} else {
					Result.success(emptyList())
				}
			}
			2 -> ToolPositionSuggestions.positionSuggestions(playerId)
			else -> Result.success(emptyList())
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val action = arguments.get<String>(actionParameter.name).getOrElse { return Result.failure(it) }
		val name = arguments.get<String>(nameParameter.name).getOrElse { return Result.failure(it) }
		val position = arguments.getOrNull<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return if (action.equals("add", ignoreCase = true) && position != null) {
			Result.success("adding sandbox interaction $name at $position")
		} else {
			Result.success("removing sandbox interaction $name")
		}
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val action = arguments.get<String>(actionParameter.name).getOrElse { return Result.failure(it) }
		val name = arguments.get<String>(nameParameter.name).getOrElse { return Result.failure(it) }
		val position = arguments.getOrNull<String>(positionParameter.name).getOrElse { return Result.failure(it) }
		return Result.success(
			SandboxToolsSupport.manageInteraction(
				playerId = boundedPlayerId,
				action = action,
				name = name,
				position = position,
			)
		)
	}
}
