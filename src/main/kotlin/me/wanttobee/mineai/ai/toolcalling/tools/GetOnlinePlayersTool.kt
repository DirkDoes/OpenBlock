package me.wanttobee.mineai.ai.toolcalling.tools

import me.wanttobee.mineai.ai.context.PlayerInspectionFormatter
import me.wanttobee.mineai.ai.toolcalling.AiTool
import me.wanttobee.mineai.ai.toolcalling.ToolArguments

object GetOnlinePlayersTool : AiTool {
	override val name = "get_online_players"
	override val description = "Returns all online players with their UUID, username, and short live context."
	override val enabledByDefault = true
	override val parameters = emptyList<AiTool.Parameter>()

	override fun execute(
		playerId: java.util.UUID?,
		arguments: ToolArguments,
	): AiTool.ExecutionResult {
		val players = PlayerInspectionFormatter.onlinePlayers()
		return AiTool.ExecutionResult(
			payload = mapOf(
				"player_count" to players.size,
				"players" to players,
			)
		)
	}
}
