package me.wanttobee.mineai.commands

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.wanttobee.mineai.ai.AiProviderStatus
import me.wanttobee.mineai.ai.AiService
import me.wanttobee.mineai.ai.AiSessionState
import me.wanttobee.mineai.ai.AiTarget
import me.wanttobee.mineai.ai.Providers
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AiCommands {
	private val executor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
		Thread(runnable, "mineai-ai").apply {
			isDaemon = true
		}
	}

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		registerAlias(dispatcher, "ai")
		registerAlias(dispatcher, "mineai")
	}

	private fun registerAlias(dispatcher: CommandDispatcher<CommandSourceStack>, alias: String) {
		dispatcher.register(
			MinecraftCommands.literal(alias)
				.requires(Commands::isOpPlayer)
				.executes { context ->
					showCurrentTarget(context.source)
					1
				}
				.then(
					MinecraftCommands.literal("ping")
						.executes { context ->
							val player = context.source.player ?: return@executes 0
							runPing(context.source.server, player.uuid)
							1
						}
				)
				.then(
					MinecraftCommands.literal("target")
						.executes { context ->
							showCurrentTarget(context.source)
							1
						}
						.then(
							MinecraftCommands.argument("provider", StringArgumentType.word())
								.suggests(::suggestProviders)
								.executes { context ->
									val player = context.source.player ?: return@executes 0
									selectTarget(
										context.source,
										player.uuid,
										StringArgumentType.getString(context, "provider"),
										null
									)
									1
								}
								.then(
									MinecraftCommands.argument("model", StringArgumentType.greedyString())
										.suggests(::suggestProviderModels)
										.executes { context ->
											val player = context.source.player ?: return@executes 0
											selectTarget(
												context.source,
												player.uuid,
												StringArgumentType.getString(context, "provider"),
												StringArgumentType.getString(context, "model")
											)
											1
										}
								)
						)
				)
				.then(
					MinecraftCommands.literal("send")
						.then(
							MinecraftCommands.argument("message", StringArgumentType.greedyString())
								.executes { context ->
									val player = context.source.player ?: return@executes 0
									sendToCurrentTarget(
										context.source.server,
										player.uuid,
										StringArgumentType.getString(context, "message")
									)
									1
								}
						)
				)
		)
	}

	private fun runPing(server: MinecraftServer, playerId: UUID) {
		executor.submit {
			val statuses = AiService.pingProviders()

			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				for (status in statuses) {
					player.sendSystemMessage(formatProviderStatus(status))
				}
			}
		}
	}

	private fun sendToCurrentTarget(server: MinecraftServer, playerId: UUID, message: String) {
		val target = AiSessionState.currentTarget(playerId)
		if (target == null) {
			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				player.sendSystemMessage(
					Component.literal("No AI target selected. Use /ai target <provider> first.")
						.withStyle(ChatFormatting.RED)
				)
			}
			return
		}

		server.execute {
			val player = server.playerList.getPlayer(playerId) ?: return@execute
			player.sendSystemMessage(
				Component.literal("you: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(message).withStyle(ChatFormatting.WHITE))
			)
		}

		executor.submit {
			try {
				val response = AiService.generateResponse(target, message)
				server.execute {
					val player = server.playerList.getPlayer(playerId) ?: return@execute
					player.sendSystemMessage(formatTargetResponse(target, response))
				}
			} catch (exception: Exception) {
				server.execute {
					val player = server.playerList.getPlayer(playerId) ?: return@execute
					player.sendSystemMessage(
						Component.literal("${target.displayName} error: ${exception.message ?: "Unknown error"}")
							.withStyle(ChatFormatting.RED)
					)
				}
			}
		}
	}

	private fun selectTarget(source: CommandSourceStack, playerId: UUID, providerName: String, modelName: String?) {
		val target = AiSessionState.selectTarget(playerId, providerName, modelName)
		if (target == null) {
			source.sendFailure(
				Component.literal("Unknown AI provider: $providerName")
					.withStyle(ChatFormatting.RED)
			)
			return
		}

		source.sendSuccess(
			{
				Component.literal("Target set to ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(target.displayName).withStyle(target.provider.chatColor))
			},
				false
			)
	}

	private fun showCurrentTarget(source: CommandSourceStack) {
		val player = source.player ?: return
		val target = AiSessionState.currentTarget(player.uuid)
		if (target == null) {
			source.sendSuccess(
				{ Component.literal("No AI target selected.").withStyle(ChatFormatting.YELLOW) },
				false
			)
			return
		}

		source.sendSuccess(
			{
				Component.literal("Current AI target: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(target.displayName).withStyle(target.provider.chatColor))
			},
			false
		)
	}

	private fun formatProviderStatus(status: AiProviderStatus): Component {
		return Component.literal("${status.provider.displayName}: ").withStyle(status.provider.chatColor)
			.append(
				Component.literal(status.message)
					.withStyle(if (status.isReady) ChatFormatting.WHITE else ChatFormatting.RED)
			)
	}

	private fun formatTargetResponse(target: AiTarget, response: String): Component {
		return Component.literal("${target.displayName}: ").withStyle(target.provider.chatColor)
			.append(Component.literal(response).withStyle(ChatFormatting.WHITE))
	}

	private fun suggestProviders(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		return SharedSuggestionProvider.suggest(Providers.providerNames(), builder)
	}

	private fun suggestProviderModels(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		val providerName = StringArgumentType.getString(context, "provider")
		for (model in Providers.modelList(providerName)) {
			builder.suggest(model.displaySlug, LiteralMessage(model.displayName))
		}
		return builder.buildFuture()
	}
}
