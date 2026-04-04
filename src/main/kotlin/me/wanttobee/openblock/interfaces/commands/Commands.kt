package me.wanttobee.openblock.interfaces.commands

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.players.NameAndId

object Commands {
	fun register() {
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			EnvCommands.register(dispatcher)
			OpenBlockCommands.register(dispatcher)
			ExampleCommands.register(dispatcher)
			AiCommands.register(dispatcher)
		}
	}

	internal fun isAdminSource(source: CommandSourceStack): Boolean {
		val player = source.player
		return if (player == null) {
			true
		} else {
			source.server.playerList.isOp(NameAndId(player.gameProfile))
		}
	}

	internal fun isOpPlayer(source: CommandSourceStack): Boolean {
		val player = source.player ?: return false
		return source.server.playerList.isOp(NameAndId(player.gameProfile))
	}
}
