package me.wanttobee.openblock.interfaces.commands

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.players.NameAndId
import net.minecraft.ChatFormatting
import java.util.UUID

object Commands {
	fun register() {
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			CodexCommands.register(dispatcher)
			EnvCommands.register(dispatcher)
			OpenBlockCommands.register(dispatcher)
			BenchmarkCommands.register(dispatcher)
			SandboxCommands.register(dispatcher)
			ExampleCommands.register(dispatcher)
			AiCommands.register(dispatcher)
			InvokeToolCommands.register(dispatcher)
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

	internal fun requirePlayerId(source: CommandSourceStack): UUID? {
		return source.player?.uuid ?: run {
			source.sendFailure(Component.literal("This command requires a player.").withStyle(ChatFormatting.RED))
			null
		}
	}
}
