package me.wanttobee.mineai

import me.wanttobee.mineai.commands.Commands
import net.fabricmc.api.DedicatedServerModInitializer

object MineAI : DedicatedServerModInitializer {
	override fun onInitializeServer() {
		Commands.register()
	}
}
