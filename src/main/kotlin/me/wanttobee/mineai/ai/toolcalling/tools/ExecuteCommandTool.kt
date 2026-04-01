package me.wanttobee.mineai.ai.toolcalling.tools

import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.CommandToolsSupport
import me.wanttobee.mineai.ai.toolcalling.ToolArguments
import java.util.UUID

object ExecuteCommandTool : AiTool {
	private val commandParameter = AiTool.Parameter(
		name = "command",
		description = "The full whitelisted command to execute, without needing to include the leading slash.",
	)

	override val name = "execute_command"
	override val description =
		"Executes one whitelisted command from the bound player's current location, but as the server command source."
	override val enabledByDefault = false
	override val parameters = listOf(commandParameter)

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): List<AiTool.Suggestion> {
		if (parameterIndex != 0) {
			return emptyList()
		}

		return CommandToolsSupport.availableCommands().map { command ->
			AiTool.Suggestion(command)
		}
	}

	override fun conversationMessage(playerId: UUID?, arguments: ToolArguments): String? {
		val command = arguments.get<String>(commandParameter.name).trim().removePrefix("/")
		return "executing: /$command"
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): AiTool.ExecutionResult {
		return CommandToolsSupport.execute(
			playerId = playerId,
			command = arguments.get(commandParameter.name),
		)
	}
}
