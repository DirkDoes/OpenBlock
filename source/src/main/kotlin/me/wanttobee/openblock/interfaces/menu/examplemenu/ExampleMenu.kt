package me.wanttobee.openblock.interfaces.menu.examplemenu

import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ExampleMenu {
	private val enabledPlayers = ConcurrentHashMap.newKeySet<UUID>()

	fun open(player: ServerPlayer) {
		player.openMenu(
			SimpleMenuProvider(
				{ containerId, inventory, _ -> ExampleMenuView(containerId, inventory, player.uuid) },
				Component.literal("Example Menu"),
			)
		)
	}

	private fun isEnabled(playerId: UUID): Boolean = enabledPlayers.contains(playerId)

	private fun toggle(playerId: UUID): Boolean {
		val enabled = !isEnabled(playerId)
		if (enabled) {
			enabledPlayers += playerId
		} else {
			enabledPlayers -= playerId
		}
		return enabled
	}

	private class ExampleMenuView(
		containerId: Int,
		playerInventory: Inventory,
		playerId: UUID,
	) : BaseMenu(playerId, containerId, playerInventory, 3) {
		init {
			refreshMenu()
		}

		override fun tick(player: ServerPlayer, tick: Long) = Unit

		private fun refreshMenu() {
			resetMenu()

			for (slot in 0 until 9) {
				setDisplayItem(
					slot,
					MenuItems.menuItem(
						item = Items.GRAY_STAINED_GLASS_PANE,
						name = Component.literal("Locked").withStyle(ChatFormatting.DARK_GRAY),
					)
				)
			}

			setButton(
				2,
				MenuItems.menuItem(
					item = Items.PAPER,
					name = Component.literal("Example Button").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Sends a chat message").withStyle(ChatFormatting.GRAY)),
				)
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					player.sendSystemMessage(Component.literal("Example button clicked.").withStyle(ChatFormatting.YELLOW))
				}
			}

			val enabled = toggleStateItem()
			setButton(4, enabled) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					toggle(playerId)
					refreshMenu()
				}
			}

			setDisplayItem(
				6,
				MenuItems.menuItem(
					item = Items.CHEST,
					name = Component.literal("Storage Area").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Rows below are movable slots").withStyle(ChatFormatting.GRAY)),
				)
			)

			setStorageSlots(9 until 27)
			broadcastChanges()
		}

		private fun toggleStateItem() = MenuItems.menuItem(
			item = if (isEnabled(playerId)) Items.LIME_WOOL else Items.RED_WOOL,
			name = Component.literal("Toggle").withStyle(ChatFormatting.YELLOW),
			lore = listOf(
				Component.literal("Current state: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(if (isEnabled(playerId)) "on" else "off").withStyle(if (isEnabled(playerId)) ChatFormatting.GREEN else ChatFormatting.RED))
			),
			glint = isEnabled(playerId),
		)
	}
}
