package me.wanttobee.openblock.ai.toolcalling.tools

import me.wanttobee.openblock.ai.toolcalling.CommandToolsSupport
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import net.minecraft.world.item.Items
import java.util.UUID

object GetCommandDocumentationTool : AiTool {
	private val commandParameter = AiToolParameter(
		name = "command",
		description = "The root command name to inspect, for example time or weather.",
	)

	override val name = "get_command_documentation"
	override val description =
		"Returns live documentation for a whitelisted command from the current server command tree."
	override val enabledByDefault = false
	override val parameters = listOf(commandParameter)
	override val menuIcon = Items.CHAIN_COMMAND_BLOCK
	override val hasConfigurationMenu = true

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		if (parameterIndex != 0) {
			return Result.success(emptyList())
		}

		return CommandToolsSupport.availableCommands().map { commands ->
			commands.map(::AiToolSuggestion)
		}
	}

	override fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val commandName = arguments.get<String>(commandParameter.name).getOrElse { return Result.failure(it) }
		return CommandToolsSupport.documentation(
			playerId = boundedPlayerId,
			commandName = commandName,
		)
	}
}
