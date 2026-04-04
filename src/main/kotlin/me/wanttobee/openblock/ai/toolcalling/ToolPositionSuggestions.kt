package me.wanttobee.openblock.ai.toolcalling

import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import java.util.UUID

object ToolPositionSuggestions {
	fun positionSuggestions(playerId: UUID?): Result<List<AiToolSuggestion>> {
		val suggestions = linkedMapOf<String, String?>()

		suggestions["~,~,~"] = "Current player position"
		suggestions["^,^,^"] = "Current eye position in local coordinates"
		suggestions["^,^1,^"] = "One block above the eyes in local coordinates"
		suggestions["^,^,^20"] = "Twenty blocks forward from where the player is looking"

		if (playerId == null) {
			return Result.success(suggestions.map { (value, description) ->
				AiToolSuggestion(value, description)
			})
		}

		val currentServer = OpenBlock.currentServer().getOrElse { return Result.failure(it) }
		val player = currentServer.playerList.getPlayer(playerId)
		if (player != null) {
			val blockPos = player.blockPosition()
			suggestions["${blockPos.x},${blockPos.y},${blockPos.z}"] = "Current player block position"
		}

		PlayerContextCapturer.capture(playerId).getOrElse { return Result.failure(it) }.lookingAt?.let { lookedAt ->
			suggestions["${lookedAt.positionX},${lookedAt.positionY},${lookedAt.positionZ}"] =
				"Block the player is currently looking at"
		}

		return Result.success(suggestions.map { (value, description) ->
			AiToolSuggestion(value, description)
		})
	}
}
