package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.wanttobee.openblock.interfaces.menu.examplemenu.ExampleMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenu
import me.wanttobee.openblock.util.EnvironmentVariables
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component
import net.minecraft.server.players.NameAndId
import java.util.concurrent.CompletableFuture

object Commands {
	fun register() {
		EnvironmentVariables.ensureFileExists()

		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			registerEnvCommand(dispatcher)
			registerOpenBlockCommand(dispatcher)
			registerExampleMenuCommand(dispatcher)
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

	private fun registerEnvCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("env")
				.requires(::isOpPlayer)
				.executes { context ->
					sendEnvironmentVariables(context.source)
					1
				}
				.then(
					MinecraftCommands.literal("reveal")
						.then(
							MinecraftCommands.argument("key", StringArgumentType.word())
								.suggests(::suggestEnvironmentKeys)
								.executes { context ->
									revealEnvironmentVariable(
										context.source,
										StringArgumentType.getString(context, "key")
									)
									1
								}
						)
				)
				.then(
					MinecraftCommands.literal("set")
						.then(
							MinecraftCommands.argument("key", StringArgumentType.word())
								.suggests(::suggestEnvironmentKeys)
								.then(
									MinecraftCommands.argument("value", StringArgumentType.greedyString())
										.executes { context ->
											setEnvironmentVariable(
												context.source,
												StringArgumentType.getString(context, "key"),
												StringArgumentType.getString(context, "value")
											)
											1
										}
								)
						)
				)
		)
	}

	private fun registerOpenBlockCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
		val root = MinecraftCommands.literal("openblock")
			.requires(::isAdminSource)
			.executes { context ->
				val player = context.source.player
				if (player == null) {
					context.source.sendFailure(
						Component.literal("Only players can open the OpenBlock menu.").withStyle(ChatFormatting.RED)
					)
					return@executes 0
				}

				OpenBlockMenu.open(player)
				1
			}

		AiCommands.attachOpenBlockCommands(root)
		dispatcher.register(root)
	}

	private fun registerExampleMenuCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("examplemenu")
				.requires(::isAdminSource)
				.executes { context ->
					val player = context.source.player
					if (player == null) {
						context.source.sendFailure(
							Component.literal("Only players can open the example menu.").withStyle(ChatFormatting.RED)
						)
						return@executes 0
					}

					ExampleMenu.open(player)
					1
				}
		)
	}

	private fun sendEnvironmentVariables(source: CommandSourceStack) {
		val variables = EnvironmentVariables.read()

		if (variables.isEmpty()) {
			source.sendSuccess(
				{ Component.literal("No environment variables found in ${EnvironmentVariables.OPENBLOCK_FILE_NAME} or ${EnvironmentVariables.DOTENV_FILE_NAME}.").withStyle(ChatFormatting.YELLOW) },
				false
			)
			return
		}

		source.sendSuccess(
			{ Component.literal("Merged environment variables (.env -> openblock.env -> runtime overrides):").withStyle(ChatFormatting.YELLOW) },
			false
		)

		for ((key, value) in variables) {
			source.sendSuccess(
				{
					Component.literal("$key=").withStyle(ChatFormatting.GRAY)
						.append(
							Component.literal(if (value.isBlank()) "<empty>" else "****")
								.withStyle(if (value.isBlank()) ChatFormatting.DARK_GRAY else ChatFormatting.WHITE)
						)
				},
				false
			)
		}
	}

	private fun revealEnvironmentVariable(source: CommandSourceStack, key: String) {
		val value = EnvironmentVariables.reveal(key).getOrElse {
			source.sendFailure(Component.literal(it.message ?: "Unknown environment variable: $key").withStyle(ChatFormatting.RED))
			return
		}

		source.sendSuccess(
			{
				Component.literal("$key=").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(if (value.isBlank()) "<empty>" else value).withStyle(ChatFormatting.WHITE))
			},
			false
		)
	}

	private fun setEnvironmentVariable(source: CommandSourceStack, key: String, rawValue: String) {
		val parsedValue = EnvironmentVariables.parseQuotedValue(rawValue).getOrElse {
			source.sendFailure(
				Component.literal("Value must be wrapped in double quotes, for example: /env set $key \"value\"")
					.withStyle(ChatFormatting.RED)
			)
			return
		}

		EnvironmentVariables.setRuntimeOverride(key, parsedValue)
		source.sendSuccess(
			{
				Component.literal("Runtime override set for $key=").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(if (parsedValue.isBlank()) "<empty>" else "****").withStyle(ChatFormatting.WHITE))
			},
			false
		)
	}

	private fun suggestEnvironmentKeys(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		return SharedSuggestionProvider.suggest(EnvironmentVariables.keySet(), builder)
	}
}
