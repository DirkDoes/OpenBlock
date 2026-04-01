package me.wanttobee.openblock

import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.sessions.SandboxOutlineRenderer
import me.wanttobee.openblock.commands.Commands
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

object OpenBlock : DedicatedServerModInitializer {
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
