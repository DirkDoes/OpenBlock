package me.wanttobee.mineai.ai.tools

import java.util.UUID

object GetCommandDocumentationTool : AiTool {
	private val commandParameter = AiTool.Parameter(
		name = "command",
		description = "The root command name to inspect, for example time or weather.",
	)

	override val name = "get_command_documentation"
	override val description =
		"Returns live documentation for a whitelisted command from the current server command tree."
	override val enabledByDefault = true
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

	override fun execute(playerId: UUID?, arguments: ToolArguments): AiTool.ExecutionResult {
		return CommandToolsSupport.documentation(
			playerId = playerId,
			commandName = arguments.get(commandParameter.name),
		)
	}
}
