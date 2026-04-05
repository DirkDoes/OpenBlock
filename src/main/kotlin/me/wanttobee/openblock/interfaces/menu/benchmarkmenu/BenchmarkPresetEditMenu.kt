package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkPresetEditMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val pathSegments: List<String>,
	private val returnPage: Int,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
) : BaseMenu(playerId, containerId, playerInventory, rows = 1) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()
		val hasCurrentSandbox = BenchmarkPresetManager.hasCurrentSandbox(playerId)

		for (slot in 0 until 9) {
			setDisplayItem(slot, MenuItems.namelessPlaceholderPaneItem())
		}

		setButton(
			0,
			MenuItems.menuItem(
				item = Items.PAPER,
				name = Component.literal("Summary").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Edit the benchmark preset summary.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openSummaryInput(player, pathSegments, returnPage, entry)
			}
		}
		setButton(
			1,
			MenuItems.menuItem(
				item = Items.WRITABLE_BOOK,
				name = Component.literal("Task").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Edit the benchmark preset task text.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openTaskInput(player, pathSegments, returnPage, entry)
			}
		}
		setButton(
			2,
			MenuItems.menuItem(
				item = Items.CANDLE,
				name = Component.literal("Edit Tags").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Choose the tags for this benchmark preset.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openPresetTagMenu(player, pathSegments, returnPage, entry)
			}
		}
		setButton(
			3,
			MenuItems.menuItem(
				item = Items.NAME_TAG,
				name = Component.literal("Rename").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Rename the selected benchmark preset.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openRenameInput(player, pathSegments, returnPage, entry)
			}
		}
		setButton(
			4,
			MenuItems.menuItem(
				item = Items.BARRIER,
				name = Component.literal("Delete").withStyle(ChatFormatting.RED),
				lore = listOf(Component.literal("Delete the selected benchmark preset.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkCatalogManager.deleteEntry(pathSegments, entry)
					.onSuccess {
						BenchmarkMenu.openCatalog(player, pathSegments, returnPage)
					}
					.onFailure { error ->
						player.sendSystemMessage(
							Component.literal(error.message ?: "Unable to delete benchmark preset.").withStyle(ChatFormatting.RED),
						)
						BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
					}
			}
		}
		setButton(
			5,
			if (hasCurrentSandbox) {
				MenuItems.menuItem(
					item = Items.SLIME_BALL,
					name = Component.literal("Re-save").withStyle(ChatFormatting.GREEN),
					lore = listOf(Component.literal("Override this preset with the current sandbox and build.").withStyle(ChatFormatting.GRAY)),
				)
			} else {
				MenuItems.menuItem(
					item = Items.IRON_NUGGET,
					name = Component.literal("You did not create a sandbox").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal("Create a sandbox before re-saving this benchmark preset.").withStyle(ChatFormatting.GRAY)),
				)
			},
		) { player, button, input ->
			if (hasCurrentSandbox && button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openOverrideConfirm(player, pathSegments, returnPage, entry)
			}
		}
		broadcastChanges()
	}
}
