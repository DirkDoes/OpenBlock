package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import me.wanttobee.openblock.ai.providers.codex.CodexSubscriptionService
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CodexCommands {
	private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "openblock-codex-auth").apply { isDaemon = true }
	}

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("ob-codex")
				.requires(Commands::isAdminSource)
				.executes { context ->
					sendStatus(context.source)
					1
				}
				.then(
					MinecraftCommands.literal("status")
						.executes { context ->
							sendStatus(context.source)
							1
						}
				)
				.then(
					MinecraftCommands.literal("login")
						.executes { context ->
							startLogin(context.source)
							1
						}
				)
				.then(
					MinecraftCommands.literal("logout")
						.executes { context ->
							logout(context.source)
							1
						}
				)
		)
	}

	private fun sendStatus(source: CommandSourceStack) {
		runAsync(source) {
			CodexSubscriptionService.accountStatus().map { status ->
				when {
					status.type == "chatgpt" -> "Codex is signed in as ${status.email ?: "a ChatGPT user"} (${status.plan ?: "unknown plan"})."
					status.loginPending -> "Codex ChatGPT sign-in is waiting for completion."
					status.loginError != null -> "Codex sign-in failed: ${status.loginError}"
					status.type != null -> "Codex is using ${status.type} authentication, not a ChatGPT subscription. Run /ob-codex logout, then /ob-codex login."
					else -> "Codex is not signed in. Run /ob-codex login."
				}
			}
		}
	}

	private fun startLogin(source: CommandSourceStack) {
		runAsync(source) {
			CodexSubscriptionService.startDeviceLogin().map { login ->
				"Open ${login.verificationUrl} and enter code ${login.userCode}. You can sign in with your Google-backed ChatGPT account."
			}
		}
	}

	private fun logout(source: CommandSourceStack) {
		runAsync(source) {
			CodexSubscriptionService.logout().map { "Codex signed out." }
		}
	}

	private fun runAsync(source: CommandSourceStack, operation: () -> Result<String>) {
		val server = source.server
		executor.submit {
			val result = operation()
			server.execute {
				result.fold(
					onSuccess = { message ->
						source.sendSuccess({ Component.literal(message).withStyle(ChatFormatting.GREEN) }, false)
					},
					onFailure = { error ->
						source.sendFailure(Component.literal(error.message ?: "Codex operation failed.").withStyle(ChatFormatting.RED))
					},
				)
			}
		}
	}
}
