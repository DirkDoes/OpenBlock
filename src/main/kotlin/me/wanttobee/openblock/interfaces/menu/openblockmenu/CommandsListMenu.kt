package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.toolcalling.CommandToolsSupport
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class CommandsListMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	initialPage: Int,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5, initialPage = initialPage) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val commands = CommandToolsSupport.commandEntries()
		pageEntries(commands).forEachIndexed { index, command ->
			setButton(index, commandItem(command)) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					CommandToolsSupport.setAllowed(command.name, !CommandToolsSupport.isAllowed(command.name))
					refreshMenu()
				}
			}
		}
		addPageNavigation(commands.size) { player ->
			OpenBlockMenu.openTools(player)
		}
		broadcastChanges()
	}

	private fun commandItem(command: CommandToolsSupport.CommandEntry) = MenuItems.menuItem(
		item = Items.NAME_TAG,
		name = Component.literal(command.name).withStyle(if (command.allowed) ChatFormatting.GREEN else ChatFormatting.RED),
		lore = listOf(
			Component.literal("State: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(if (command.allowed) "on" else "off").withStyle(if (command.allowed) ChatFormatting.GREEN else ChatFormatting.RED)),
			Component.literal("Default: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(if (command.defaultAllowed) "on" else "off").withStyle(if (command.defaultAllowed) ChatFormatting.GREEN else ChatFormatting.RED)),
		),
		glint = command.allowed,
	)
}
