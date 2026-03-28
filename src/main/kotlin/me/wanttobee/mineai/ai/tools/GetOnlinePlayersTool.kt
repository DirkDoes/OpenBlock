package me.wanttobee.mineai.ai.tools

import me.wanttobee.mineai.ai.context.PlayerInspectionFormatter

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
