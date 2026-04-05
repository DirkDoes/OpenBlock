package me.wanttobee.openblock

import me.wanttobee.openblock.ai.toolcalling.BlockPlacementToolsSupport
import me.wanttobee.openblock.interfaces.chat.ChatModeManager
import me.wanttobee.openblock.interfaces.commands.Commands
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenu
import me.wanttobee.openblock.interfaces.sandbox.DisplayEntitySandboxRenderer
import me.wanttobee.openblock.interfaces.sandbox.ParticleSandboxRenderer
import me.wanttobee.openblock.sandbox.SandboxManager
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer

object OpenBlock : DedicatedServerModInitializer {
	@Volatile
	var Server: MinecraftServer? = null
		private set

	fun currentServer(): Result<MinecraftServer> {
		return Server?.let(Result.Companion::success)
			?: Result.failure(IllegalStateException("Minecraft server is not bound yet."))
	}

	override fun onInitializeServer() {
		BlockPlacementToolsSupport.bind()
		SandboxManager.bind()
		ParticleSandboxRenderer.bind()
		DisplayEntitySandboxRenderer.bind()
		ChatModeManager.bind()
		OpenBlockMenu.bind()
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			Server = server
		}
		ServerLifecycleEvents.SERVER_STOPPED.register { server ->
			if (Server === server) {
				Server = null
			}
		}
		Commands.register()
	}
}
