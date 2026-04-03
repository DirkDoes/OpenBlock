package me.wanttobee.openblock.ai.toolcalling

import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import java.util.UUID

object ToolPositionSuggestions {
	fun positionSuggestions(playerId: UUID?): List<AiToolSuggestion> {
		val suggestions = linkedMapOf<String, String?>()

		suggestions["~,~,~"] = "Current player position"
		suggestions["^,^,^"] = "Current eye position in local coordinates"
		suggestions["^,^1,^"] = "One block above the eyes in local coordinates"
		suggestions["^,^,^20"] = "Twenty blocks forward from where the player is looking"

		val player = playerId
			?.let { id -> PlayerContextCapturer.currentServer().getOrNull()?.playerList?.getPlayer(id) }
		if (player != null) {
			val blockPos = player.blockPosition()
			suggestions["${blockPos.x},${blockPos.y},${blockPos.z}"] = "Current player block position"
		}

		PlayerContextCapturer.capture(playerId ?: return suggestions.map { (value, description) ->
			AiToolSuggestion(value, description)
		}).getOrNull()?.lookingAt?.let { lookedAt ->
			suggestions["${lookedAt.positionX},${lookedAt.positionY},${lookedAt.positionZ}"] =
				"Block the player is currently looking at"
		}

		return suggestions.map { (value, description) ->
			AiToolSuggestion(value, description)
	}
}
}
