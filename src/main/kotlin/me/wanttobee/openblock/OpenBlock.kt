package me.wanttobee.openblock

import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.interfaces.chat.ChatModeManager
import me.wanttobee.openblock.interfaces.commands.Commands
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenu
import me.wanttobee.openblock.sandbox.SandboxOutlineRenderer
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

object OpenBlock : DedicatedServerModInitializer {
	override fun onInitializeServer() {
		SandboxOutlineRenderer.bind()
		ChatModeManager.bind()
		OpenBlockMenu.bind()
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			PlayerContextCapturer.bind(server)
		}
		ServerLifecycleEvents.SERVER_STOPPED.register { server ->
			PlayerContextCapturer.clear(server)
		}
		Commands.register()
	}
}
