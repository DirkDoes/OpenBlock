package me.wanttobee.mineai

import net.fabricmc.api.DedicatedServerModInitializer

object MineAI : DedicatedServerModInitializer {
	override fun onInitializeServer() {
		Commands.register()
	}
}
