package me.wanttobee.openblock.interfaces.menu.base

import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object MenuOpener {
	private val nextContainerCounterMethod: Method = ServerPlayer::class.java.getDeclaredMethod("nextContainerCounter").apply {
		isAccessible = true
	}
	private val initMenuMethod: Method = ServerPlayer::class.java.getDeclaredMethod("initMenu", AbstractContainerMenu::class.java).apply {
		isAccessible = true
	}
	private val containerCounterField: Field = ServerPlayer::class.java.getDeclaredField("containerCounter").apply {
		isAccessible = true
	}

	fun open(
		player: ServerPlayer,
		title: Component,
		factory: (containerId: Int, inventory: Inventory) -> AbstractContainerMenu,
	) {
		val provider = SimpleMenuProvider(
			{ containerId, inventory, _ -> factory(containerId, inventory) },
			title,
		)
		openWithoutReset(player, provider)
	}

	private fun openWithoutReset(player: ServerPlayer, provider: MenuProvider) {
		val currentMenu = player.containerMenu
		val containerId = nextContainerId(player)
		val nextMenu = provider.createMenu(containerId, player.inventory, player) ?: return

		player.connection.send(ClientboundOpenScreenPacket(nextMenu.containerId, nextMenu.type, provider.displayName))
		player.containerMenu = nextMenu
		initMenuMethod.invoke(player, nextMenu)

		if (currentMenu !== player.inventoryMenu && currentMenu !== nextMenu) {
			currentMenu.removed(player)
		}
	}

	private fun nextContainerId(player: ServerPlayer): Int {
		nextContainerCounterMethod.invoke(player)
		return containerCounterField.getInt(player)
	}
}
