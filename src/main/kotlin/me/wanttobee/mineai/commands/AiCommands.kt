package me.wanttobee.mineai.commands

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.wanttobee.mineai.ai.AiActionBarManager
import me.wanttobee.mineai.ai.AiService
import me.wanttobee.mineai.ai.Providers
import me.wanttobee.mineai.ai.sessions.AiTargetManager
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.util.MinecraftTextFormatter
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
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
							MinecraftCommands.literal("get")
								.executes { context ->
									showCurrentTarget(context.source)
									1
								}
						)
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
				.then(
					MinecraftCommands.literal("clear")
						.executes { context ->
							clearSession(context.source)
							1
						}
				)
		)
	}

	private fun runPing(server: MinecraftServer, playerId: UUID) {
		executor.submit {
			val statuses = AiService.pingProviders()

			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				for ((provider, exception) in statuses) {
					player.sendSystemMessage(formatProviderStatus(provider, exception))
				}
			}
		}
	}

	private fun sendToCurrentTarget(server: MinecraftServer, playerId: UUID, message: String) {
		val target = AiService.currentTarget(playerId)
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
			AiActionBarManager.start(server, playerId, target, "generating")
		}

		executor.submit {
			val result = AiService.sendMessage(playerId, message)
			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				AiActionBarManager.stop(server, playerId)
				val currentResult = result ?: return@execute
				player.sendSystemMessage(formatSessionMessage(currentResult.first, currentResult.second))
			}
		}
	}

	private fun selectTarget(source: CommandSourceStack, playerId: UUID, providerName: String, modelName: String?) {
		val target = AiService.selectTarget(playerId, providerName, modelName)
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
					.append(Component.literal(target.model.displayName).withStyle(target.provider.chatColor))
			},
				false
			)
	}

	private fun showCurrentTarget(source: CommandSourceStack) {
		val player = source.player ?: return
		val target = AiService.currentTarget(player.uuid)
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
					.append(Component.literal(target.provider.displayName).withStyle(target.provider.chatColor))
					.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(target.model.displayName).withStyle(ChatFormatting.WHITE))
			},
			false
		)
	}

	private fun clearSession(source: CommandSourceStack) {
		val player = source.player ?: return
		val cleared = AiService.clearSession(player.uuid)

		source.sendSuccess(
			{
				Component.literal(
					if (cleared) "AI session cleared."
					else "No AI session to clear."
				).withStyle(ChatFormatting.YELLOW)
			},
			false
		)
	}

	private fun formatProviderStatus(
		provider: me.wanttobee.mineai.ai.providers.AiProvider,
		exception: Exception?,
	): Component {
		return Component.literal("${provider.displayName}: ").withStyle(provider.chatColor)
			.append(
				Component.literal(exception?.message ?: "ready")
					.withStyle(if (exception == null) ChatFormatting.WHITE else ChatFormatting.RED)
			)
	}

	private fun formatSessionMessage(target: AiTargetManager.AiTarget, message: Session.Message): Component {
		return when (message.type) {
			Session.Message.Type.ASSISTANT -> Component.literal("${target.model.displayName}: ")
				.withStyle(target.provider.chatColor)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			Session.Message.Type.ERROR -> Component.literal("${target.model.displayName} error: ")
				.withStyle(ChatFormatting.RED)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			Session.Message.Type.USER -> Component.literal("you: ").withStyle(ChatFormatting.GRAY)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
		}
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
