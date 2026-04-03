package me.wanttobee.openblock.ai.toolcalling.tools

import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import net.minecraft.world.item.Items

object GetOnlinePlayersTool : AiTool {
	override val name = "get_online_players"
	override val description = "Returns all online players with their UUID, username, and short live context."
	override val enabledByDefault = true
	override val parameters = emptyList<AiToolParameter>()
	override val menuIcon = Items.PLAYER_HEAD

	override fun execute(
		playerId: java.util.UUID?,
		arguments: ToolArguments,
	): Result<AiToolExecution> {
		val players = PlayerContextCapturer.onlinePlayers().getOrElse { error ->
			return Result.success(AiToolExecution(
				payload = mapOf("message" to (error.message ?: "Unable to inspect online players.")),
				isError = true,
			))
		}
		return Result.success(AiToolExecution(
			payload = mapOf(
				"player_count" to players.size,
				"players" to players,
			)
		))
	}
}
