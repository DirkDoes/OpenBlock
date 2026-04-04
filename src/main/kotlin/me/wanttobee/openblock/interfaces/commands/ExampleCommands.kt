package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import me.wanttobee.openblock.interfaces.menu.examplemenu.ExampleMenu
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.network.chat.Component

object ExampleCommands {
	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("examplemenu")
				.requires(Commands::isAdminSource)
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
}
