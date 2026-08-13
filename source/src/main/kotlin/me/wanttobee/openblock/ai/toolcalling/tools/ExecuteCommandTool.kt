package me.wanttobee.openblock.ai.toolcalling.tools

import me.wanttobee.openblock.ai.toolcalling.CommandToolsSupport
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import net.minecraft.world.item.Items
import java.util.UUID

object ExecuteCommandTool : AiTool {
	private val commandParameter = AiToolParameter(
		name = "command",
		description = "The full whitelisted command to execute, without needing to include the leading slash.",
	)

	override val name = "execute_command"
	override val description =
		"Executes one whitelisted command from the bound player's current location, but as the server command source."
	override val enabledByDefault = false
	override val parameters = listOf(commandParameter)
	override val menuIcon = Items.COMMAND_BLOCK
	override val hasConfigurationMenu = true

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		if (parameterIndex != 0) {
			return Result.success(emptyList())
		}

		return CommandToolsSupport.availableCommands(playerId).map { commands ->
			commands.map(::AiToolSuggestion)
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		val command = arguments.get<String>(commandParameter.name)
			.getOrElse { return Result.failure(it) }
			.trim()
			.removePrefix("/")
		return Result.success("executing: /$command")
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val command = arguments.get<String>(commandParameter.name).getOrElse { return Result.failure(it) }
		return CommandToolsSupport.execute(
			playerId = boundedPlayerId,
			command = command,
		)
	}
}
