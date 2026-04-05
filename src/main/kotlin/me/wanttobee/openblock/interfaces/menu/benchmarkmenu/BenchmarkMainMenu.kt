package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseHopperMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkMainMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()
		setDisplayItem(0, MenuItems.blockedPaneItem())
		setDisplayItem(4, MenuItems.blockedPaneItem())
		setButton(
			1,
			MenuItems.menuItem(
				item = Items.OXIDIZED_COPPER_CHEST,
				name = Component.literal("Tag").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Open benchmark tags.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openTags(player)
			}
		}
		setButton(
			2,
			MenuItems.menuItem(
				item = Items.CHEST,
				name = Component.literal("Benchmark Catalog").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Open benchmark folders and presets.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openCatalog(player)
			}
		}
		setButton(
			3,
			MenuItems.menuItem(
				item = Items.ENDER_CHEST,
				name = Component.literal("Runs").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Benchmark runs are not implemented yet.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				player.sendSystemMessage(Component.literal("Benchmark runs are not implemented yet.").withStyle(ChatFormatting.GRAY))
			}
		}
		broadcastChanges()
	}
}
