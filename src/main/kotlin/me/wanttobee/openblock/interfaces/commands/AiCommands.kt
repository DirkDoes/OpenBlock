package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import me.wanttobee.openblock.ai.AiActionBarManager
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.sessions.AiTargetManager
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.util.MinecraftTextFormatter
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AiCommands {
	private const val MESSAGE_DIVIDER = "------------------------------"
	private val executor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
		Thread(runnable, "openblock-ai").apply {
			isDaemon = true
		}
	}

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		registerAiChatCommand(dispatcher)
	}

	fun sendPrompt(server: MinecraftServer, playerId: UUID, message: String) {
		sendToCurrentTarget(server, playerId, message)
	}

	private fun registerAiChatCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("ai")
				.requires(Commands::isOpPlayer)
				.executes { context ->
					OpenBlockCommands.showCurrentTarget(context.source)
					1
				}
				.then(
					MinecraftCommands.argument("message", StringArgumentType.greedyString())
						.executes { context ->
							val player = context.source.player ?: return@executes 0
							sendPrompt(
								context.source.server,
								player.uuid,
								StringArgumentType.getString(context, "message"),
							)
							1
						}
				)
		)
	}

	private fun sendToCurrentTarget(server: MinecraftServer, playerId: UUID, message: String) {
		val target = AiService.currentTarget(playerId).getOrElse {
			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				player.sendSystemMessage(
					Component.literal("No AI model selected. Use /openblock model <provider> first.")
						.withStyle(ChatFormatting.RED)
				)
			}
			return
		}

		server.execute {
			val player = server.playerList.getPlayer(playerId) ?: return@execute
			player.sendSystemMessage(Component.literal(MESSAGE_DIVIDER).withStyle(ChatFormatting.DARK_GRAY))
			player.sendSystemMessage(
				Component.literal("you: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(message).withStyle(ChatFormatting.WHITE))
			)
			AiActionBarManager.start(server, playerId, target, target.provider.startingAction(target.model))
		}

		executor.submit {
			val result = AiService.sendMessage(
				playerId = playerId,
				message = message,
				onActionChange = { action ->
					server.execute {
						AiActionBarManager.updateAction(server, playerId, action)
					}
				},
				onMessageAdded = { sessionMessage ->
					server.execute {
						val player = server.playerList.getPlayer(playerId) ?: return@execute
						player.sendSystemMessage(formatSessionMessage(target, sessionMessage))
					}
				},
			)

			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				AiActionBarManager.stop(server, playerId, 700L)
				val currentResult = result ?: return@execute
				for (sessionMessage in currentResult.second) {
					player.sendSystemMessage(formatSessionMessage(currentResult.first, sessionMessage))
				}
			}
		}
	}

	internal fun formatSessionMessage(target: AiTargetManager.AiTarget, message: SessionMessage): Component {
		return when (message.type) {
			SessionMessage.Type.ASSISTANT -> Component.literal("${target.model.displayName}: ")
				.withStyle(target.provider.chatColor)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			SessionMessage.Type.TOOL -> Component.literal(message.content).withStyle(ChatFormatting.GRAY)
			SessionMessage.Type.ERROR -> Component.literal("${target.model.displayName} error: ")
				.withStyle(ChatFormatting.RED)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			SessionMessage.Type.USER -> Component.literal("you: ").withStyle(ChatFormatting.GRAY)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
		}
	}
}
