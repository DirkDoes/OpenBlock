package me.wanttobee.mineai

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component
import net.minecraft.server.players.NameAndId

object Commands {
	fun register() {
		EnvironmentVariables.ensureFileExists()

		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			dispatcher.register(
				MinecraftCommands.literal("ping").executes { context ->
					val response = Component.literal("\u25cf ").withStyle(ChatFormatting.GREEN)
						.append(Component.literal("pong").withStyle(ChatFormatting.WHITE))

					context.source.sendSuccess({ response }, false)
					1
				}
			)

			dispatcher.register(
				MinecraftCommands.literal("read-env")
					.requires { source -> isOpPlayer(source) }
					.executes { context ->
						sendEnvironmentVariables(context.source)
						1
					}
			)
		}
	}

	private fun isOpPlayer(source: CommandSourceStack): Boolean {
		val player = source.player ?: return false
		return source.server.playerList.isOp(NameAndId(player.gameProfile))
	}

	private fun sendEnvironmentVariables(source: CommandSourceStack) {
		val variables = EnvironmentVariables.read()

		if (variables.isEmpty()) {
			source.sendSuccess(
				{ Component.literal("No environment variables found in ${EnvironmentVariables.FILE_NAME}.").withStyle(ChatFormatting.YELLOW) },
				false
			)
			return
		}

		source.sendSuccess(
			{ Component.literal("Environment variables from ${EnvironmentVariables.FILE_NAME}:").withStyle(ChatFormatting.YELLOW) },
			false
		)

		for ((key, value) in variables) {
			source.sendSuccess(
				{
					Component.literal("$key=").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(value).withStyle(ChatFormatting.WHITE))
				},
				false
			)
		}
	}
}
