package me.wanttobee.mineai

import me.wanttobee.mineai.ai.context.PlayerContextCapturer
import me.wanttobee.mineai.ai.sessions.SandboxOutlineRenderer
import me.wanttobee.mineai.commands.Commands
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

object MineAI : DedicatedServerModInitializer {
	override fun onInitializeServer() {
		SandboxOutlineRenderer.bind()
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			PlayerContextCapturer.bind(server)
		}
		ServerLifecycleEvents.SERVER_STOPPED.register { server ->
			PlayerContextCapturer.clear(server)
		}
		Commands.register()
	}
}
