package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseHopperMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkPostValidationMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val pathSegments: List<String>,
	private val returnPage: Int,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()
		setDisplayItem(0, MenuItems.blockedPaneItem())
		setDisplayItem(1, MenuItems.blockedPaneItem())
		setButton(
			2,
			MenuItems.menuItem(
				item = Items.COMPARATOR,
				name = Component.literal("Manual").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Validation is done manually after the run.").withStyle(ChatFormatting.GRAY)),
				glint = true,
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkPresetManager.updatePostValidation(pathSegments, entry, "manual")
					.onSuccess {
						BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
					}
					.onFailure { error ->
						player.sendSystemMessage(
							Component.literal(error.message ?: "Unable to update post-validation mode.").withStyle(ChatFormatting.RED),
						)
						BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
					}
			}
		}
		setDisplayItem(3, MenuItems.blockedPaneItem())
		setDisplayItem(4, MenuItems.blockedPaneItem())
		broadcastChanges()
	}
}
