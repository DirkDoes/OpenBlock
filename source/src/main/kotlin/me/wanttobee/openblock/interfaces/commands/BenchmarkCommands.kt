package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import me.wanttobee.openblock.interfaces.menu.benchmarkmenu.BenchmarkMenu
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component

object BenchmarkCommands {
	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("ob-benchmark")
				.requires(Commands::isAdminSource)
				.executes { context ->
					val player = context.source.player
					if (player == null) {
						context.source.sendFailure(
							Component.literal("Only players can open the benchmark menu.").withStyle(ChatFormatting.RED),
						)
						return@executes 0
					}

					BenchmarkMenu.open(player)
					1
				},
		)
	}
}
