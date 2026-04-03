package me.wanttobee.openblock.interfaces.chat

import me.wanttobee.openblock.interfaces.commands.AiCommands
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ChatModeManager {
	private val enabledPlayers = ConcurrentHashMap.newKeySet<UUID>()

	fun bind() {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
			if (!isEnabled(sender.uuid)) {
				return@register true
			}

			val content = message.signedContent().trim()
			if (content.isEmpty()) {
				return@register false
			}

			AiCommands.sendPrompt(sender.level().server, sender.uuid, content)
			false
		}
	}

	fun isEnabled(playerId: UUID): Boolean = enabledPlayers.contains(playerId)

	fun setEnabled(playerId: UUID, enabled: Boolean) {
		if (enabled) {
			enabledPlayers += playerId
		} else {
			enabledPlayers -= playerId
		}
	}

	fun toggle(playerId: UUID): Boolean {
		val enabled = !isEnabled(playerId)
		setEnabled(playerId, enabled)
		return enabled
	}
}
