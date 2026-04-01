package me.wanttobee.openblock.ai.toolcalling

import com.mojang.brigadier.tree.CommandNode
import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import net.minecraft.commands.CommandResultCallback
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.util.UUID

object CommandToolsSupport {
	private val allowedRoots = setOf(
		"advancement", "attribute", "bossbar", "clear", "damage", "data", "datapack",
		"defaultgamemode", "difficulty", "effect", "enchant", "execute", "experience", "xp",
		"forceload", "function", "gamemode", "gamerule", "give", "item", "kill", "kick",
		"list", "locate", "loot", "me", "msg", "tell", "w", "particle",
		"playsound", "recipe", "ride", "say", "schedule", "scoreboard", "seed",
		"setworldspawn", "spawnpoint", "spectate", "spreadplayers", "summon", "tag", "team",
		"teammsg", "teleport", "tp", "tellraw", "time", "title", "trigger", "weather",
	)

	fun availableCommands(): List<String> {
		val server = PlayerContextCapturer.currentServer() ?: return allowedRoots.sorted()
		return server.commands.dispatcher.root.children
			.map(CommandNode<CommandSourceStack>::getName)
			.filter { it in allowedRoots }
			.sorted()
	}

	fun documentation(playerId: UUID?, commandName: String): AiTool.ExecutionResult {
		val rootName = normalizeRoot(commandName)
			?: return error("Command name cannot be blank.")
		if (rootName !in allowedRoots) {
			return error("Command is not allowed for AI documentation: $rootName")
		}

		val server = PlayerContextCapturer.currentServer()
			?: return error("Server is not available.")
		val dispatcher = server.commands.dispatcher
		val node = dispatcher.root.getChild(rootName)
			?: return error("Command is not currently registered: $rootName")
		val source = createCommandSource(server, playerId)

		val smartUsage = dispatcher.getSmartUsage(node, source)
			.values
			.map { usage -> prefixedUsage(rootName, usage) }
			.distinct()
			.sorted()
		val allUsage = dispatcher.getAllUsage(node, source, false)
			.map { usage -> prefixedUsage(rootName, usage) }
			.distinct()
			.sorted()

		return AiTool.ExecutionResult(
			payload = linkedMapOf(
				"command" to rootName,
				"available" to true,
				"subcommands" to node.children.map(CommandNode<CommandSourceStack>::getName).sorted(),
				"smart_usage" to smartUsage,
				"all_usage" to allUsage,
			)
		)
	}

	fun execute(playerId: UUID?, command: String): AiTool.ExecutionResult {
		val normalizedCommand = normalizeCommand(command)
			?: return error("Command cannot be blank.")
		val validationError = validateExecutableCommand(normalizedCommand)
		if (validationError != null) {
			return error(validationError)
		}

		return runCommand(playerId, normalizedCommand)
	}

	internal fun executeInternal(playerId: UUID?, command: String): AiTool.ExecutionResult {
		val normalizedCommand = normalizeCommand(command)
			?: return error("Command cannot be blank.")
		return runCommand(playerId, normalizedCommand)
	}

	private fun runCommand(playerId: UUID?, normalizedCommand: String): AiTool.ExecutionResult {
		val server = PlayerContextCapturer.currentServer()
			?: return error("Server is not available.")
		val output = mutableListOf<String>()
		var success = false
		var resultCount = 0
		val source = createCommandSource(server, playerId, output).withCallback(
			CommandResultCallback { succeeded, result ->
				success = succeeded
				resultCount = result
			}
		)

		return try {
			server.commands.performPrefixedCommand(source, normalizedCommand)
			AiTool.ExecutionResult(
				payload = linkedMapOf(
					"command" to "/$normalizedCommand",
					"success" to success,
					"result_count" to resultCount,
					"output" to output,
				)
			)
		} catch (exception: Exception) {
			error(exception.message ?: "Unknown command execution error.")
		}
	}

	internal fun createCommandSource(
		server: net.minecraft.server.MinecraftServer,
		playerId: UUID?,
		output: MutableList<String> = mutableListOf(),
	): CommandSourceStack {
		val capturingSource = object : CommandSource {
			override fun sendSystemMessage(component: Component) {
				output += component.string
			}

			override fun acceptsSuccess(): Boolean = true
			override fun acceptsFailure(): Boolean = true
			override fun shouldInformAdmins(): Boolean = false
		}

		var source = server.createCommandSourceStack().withSource(capturingSource)
		val player = playerId?.let(server.playerList::getPlayer)
		if (player != null) {
			source = player.createCommandSourceStack()
				.withSource(capturingSource)
		}
		return source
	}

	private fun prefixedUsage(rootName: String, usage: String): String {
		val trimmed = usage.trim()
		return when {
			trimmed.isEmpty() -> "/$rootName"
			trimmed.startsWith("/") -> trimmed
			trimmed.startsWith(rootName) -> "/$trimmed"
			else -> "/$rootName $trimmed"
		}
	}

	private fun normalizeRoot(commandName: String): String? {
		return commandName.trim()
			.removePrefix("/")
			.substringBefore(' ')
			.lowercase()
			.ifBlank { null }
	}

	private fun normalizeCommand(command: String): String? {
		return command.trim()
			.removePrefix("/")
			.ifBlank { null }
	}

	private fun validateExecutableCommand(command: String): String? {
		val rootName = command.substringBefore(' ')
		if (rootName !in allowedRoots) {
			return "Command is not allowed for AI execution: $rootName"
		}

		if (rootName != "execute") {
			return null
		}

		val runCommand = nestedRunCommand(command) ?: return null
		return validateExecutableCommand(runCommand)
	}

	private fun nestedRunCommand(command: String): String? {
		val tokens = command.split(Regex("\\s+"))
		val runIndex = tokens.indexOfFirst { it == "run" }
		if (runIndex < 0 || runIndex == tokens.lastIndex) {
			return null
		}
		return tokens.drop(runIndex + 1)
			.joinToString(" ")
			.ifBlank { null }
	}

	private fun error(message: String): AiTool.ExecutionResult {
		return AiTool.ExecutionResult(
			payload = mapOf("message" to message),
			isError = true,
		)
	}
}
