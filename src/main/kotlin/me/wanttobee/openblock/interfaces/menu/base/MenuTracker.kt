package me.wanttobee.openblock.interfaces.menu.base

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface ManagedMenu {
	val playerId: UUID
	val refreshIntervalTicks: Long
	fun tick(player: ServerPlayer, tick: Long)
}

internal object MenuTracker {
	private val openMenus = ConcurrentHashMap<UUID, ManagedMenu>()
	@Volatile private var tickCounter = 0L
	@Volatile private var bound = false

	fun bind() {
		if (bound) {
			return
		}
		bound = true

		ServerTickEvents.END_SERVER_TICK.register { server ->
			tickCounter += 1L
			for ((playerId, menu) in openMenus.entries.toList()) {
				val player = server.playerList.getPlayer(playerId)
				if (player == null) {
					openMenus.remove(playerId, menu)
					continue
				}
				if (tickCounter % menu.refreshIntervalTicks == 0L) {
					menu.tick(player, tickCounter)
				}
			}
		}
	}

	fun register(menu: ManagedMenu) {
		openMenus[menu.playerId] = menu
	}

	fun unregister(menu: ManagedMenu) {
		openMenus.remove(menu.playerId, menu)
	}
}
