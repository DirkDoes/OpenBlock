package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkPresetTargetsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val pathSegments: List<String>,
	private val returnPage: Int,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
	initialPage: Int = 0,
	initialSelection: String? = null,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 4, contentRows = 3, initialPage = initialPage) {
	private var selectedTargetKey: String? = initialSelection

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val targets = BenchmarkPresetManager.targets(pathSegments, entry).getOrElse { error ->
			showLoadError("Unable to load targets", error.message ?: "Unknown preset target error.")
			return
		}

		if (selectedTargetKey !in targets.map(BenchmarkPresetManager.PresetTargetEntry::key).toSet()) {
			selectedTargetKey = null
		}

		if (targets.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.STAINED_GLASS.yellow(),
					name = Component.literal("No targets").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Use the footer to add a preset target.").withStyle(ChatFormatting.GRAY)),
				),
			)
		} else {
			pageEntries(targets).forEachIndexed { index, target ->
				val selected = selectedTargetKey == target.key
				setButton(index, targetItem(target, selected)) { _, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP) {
						selectedTargetKey = target.key
						refreshMenu()
					}
				}
			}
		}

		addTargetsFooter(targets)
		broadcastChanges()
	}

	override fun hasDeselectableSelection(): Boolean = selectedTargetKey != null

	override fun clearSelection() {
		selectedTargetKey = null
		refreshMenu()
	}

	private fun addTargetsFooter(targets: List<BenchmarkPresetManager.PresetTargetEntry>) {
		addPageNavigation(targets.size) { player ->
			BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
		}

		val selectedTarget = targets.firstOrNull { it.key == selectedTargetKey }
		setButton(
			footerLeftOuterSlot,
			MenuItems.menuItem(
				item = Items.STAINED_GLASS_PANE.yellow(),
				name = Component.literal("Add Target").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Create a new preset target.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openCreatePresetTargetKeyInput(player, pathSegments, returnPage, entry, page)
			}
		}

		if (selectedTarget != null) {
			setButton(
				footerLeftInnerSlot,
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Delete").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal("Delete the selected preset target.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.deletePresetTarget(player, pathSegments, returnPage, entry, page, selectedTarget)
				}
			}
			setButton(
				footerRightInnerSlot,
				MenuItems.menuItem(
					item = Items.NAME_TAG,
					name = Component.literal("Change Name").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Edit the selected target key.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.openRenamePresetTargetInput(player, pathSegments, returnPage, entry, page, selectedTarget)
				}
			}
			setButton(
				footerRightOuterSlot,
				MenuItems.menuItem(
					item = Items.WRITABLE_BOOK,
					name = Component.literal("Edit Description").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal(selectedTarget.description.ifBlank { " " }).withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.openPresetTargetDescriptionInput(player, pathSegments, returnPage, entry, page, selectedTarget)
				}
			}
		}
	}

	private fun showLoadError(title: String, details: String) {
		setDisplayItem(
			centerContentSlot(),
			MenuItems.menuItem(
				item = Items.BARRIER,
				name = Component.literal(title).withStyle(ChatFormatting.RED),
				lore = listOf(Component.literal(details).withStyle(ChatFormatting.GRAY)),
			),
		)
		addPageNavigation(0) { player ->
			BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
		}
		broadcastChanges()
	}

	private fun targetItem(target: BenchmarkPresetManager.PresetTargetEntry, selected: Boolean) = MenuItems.menuItem(
		item = Items.STAINED_GLASS.yellow(),
		name = Component.literal(target.key).withStyle(ChatFormatting.YELLOW),
		lore = listOf(Component.literal(target.description.ifBlank { " " }).withStyle(ChatFormatting.GRAY)),
		glint = selected,
	)
}
