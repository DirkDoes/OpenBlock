package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.benchmarking.BenchmarkTagManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkCatalogMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val pathSegments: List<String>,
	initialPage: Int = 0,
	initialSelection: String? = null,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5, initialPage = initialPage) {
	private var selectedStoredName: String? = initialSelection
	private var movingStoredName: String? = null

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val entries = BenchmarkCatalogManager.listEntries(pathSegments).getOrElse { error ->
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Unable to load catalog").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal(error.message ?: "Unknown benchmark catalog error.").withStyle(ChatFormatting.GRAY)),
				),
			)
			addCatalogFooter(emptyList())
			broadcastChanges()
			return
		}

		if (selectedStoredName !in entries.map(BenchmarkCatalogManager.CatalogEntry::storedName).toSet()) {
			selectedStoredName = null
			movingStoredName = null
		}

		if (entries.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.CHEST,
					name = Component.literal("No benchmark items").withStyle(ChatFormatting.YELLOW),
					lore = listOf(
						Component.literal("Use the footer buttons to add a folder or benchmark preset.").withStyle(ChatFormatting.GRAY),
					),
				),
			)
		} else {
			pageEntries(entries).forEachIndexed { index, entry ->
				val selected = selectedStoredName == entry.storedName
				val moving = movingStoredName == entry.storedName
				setButton(index, entryItem(entry, selected, moving)) { player, button, input ->
					if (button != 0 || input != ContainerInput.PICKUP) {
						return@setButton
					}

					if (movingStoredName != null) {
						handleMoveModeClick(player, entry)
						return@setButton
					}

					if (selected && entry.kind == BenchmarkCatalogManager.EntryKind.FOLDER) {
						BenchmarkMenu.openCatalog(player, pathSegments + entry.storedName)
						return@setButton
					}

					selectedStoredName = entry.storedName
					refreshMenu()
				}
			}
		}

		addCatalogFooter(entries)
		broadcastChanges()
	}

	override fun hasDeselectableSelection(): Boolean = selectedStoredName != null && movingStoredName == null

	override fun clearSelection() {
		selectedStoredName = null
		refreshMenu()
	}

	private fun addCatalogFooter(entries: List<BenchmarkCatalogManager.CatalogEntry>) {
		addPageNavigation(entries.size) { player ->
			if (movingStoredName != null) {
				moveSelectedEntryToParent(player)
			} else if (pathSegments.isEmpty()) {
				BenchmarkMenu.openMain(player)
			} else {
				BenchmarkMenu.openCatalog(player, pathSegments.dropLast(1))
			}
		}

		val selectedEntry = entries.firstOrNull { it.storedName == selectedStoredName }
		val hasCurrentSandbox = BenchmarkPresetManager.hasCurrentSandbox(playerId)
		if (movingStoredName != null || selectedEntry == null) {
			if (movingStoredName != null) {
				return
			}
			setButton(
				footerLeftOuterSlot,
				MenuItems.menuItem(
					item = Items.DYED_SHULKER_BOX.lime(),
					name = Component.literal("Add Folder").withStyle(ChatFormatting.YELLOW),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.openCreateFolderInput(player, pathSegments, page)
				}
			}
			setButton(
				footerLeftInnerSlot,
				if (hasCurrentSandbox) {
					MenuItems.menuItem(
						item = Items.DYED_CANDLE.lime(),
						name = Component.literal("Add Benchmark").withStyle(ChatFormatting.YELLOW),
					)
				} else {
					MenuItems.menuItem(
						item = Items.DYED_CANDLE.lightGray(),
						name = Component.literal("You did not create a sandbox").withStyle(ChatFormatting.RED),
						lore = listOf(Component.literal("Create a sandbox before saving a benchmark preset.").withStyle(ChatFormatting.GRAY)),
					)
				},
			) { player, button, input ->
				if (hasCurrentSandbox && button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.openCreatePresetInput(player, pathSegments, page)
				}
			}
			return
		}

		if (selectedEntry.kind == BenchmarkCatalogManager.EntryKind.PRESET) {
			setButton(
				footerLeftOuterSlot,
				MenuItems.menuItem(
					item = Items.STRUCTURE_BLOCK,
					name = Component.literal("Place Here").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Place this preset at your current position.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					placeSelectedPreset(player, selectedEntry)
				}
			}
			setButton(
				footerLeftInnerSlot,
				MenuItems.menuItem(
					item = Items.WRITABLE_BOOK,
					name = Component.literal("Edit").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Open rename, delete, and re-save actions.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.openPresetEditMenu(player, pathSegments, page, selectedEntry)
				}
			}
		} else {
			setButton(
				footerLeftOuterSlot,
				MenuItems.menuItem(
					item = Items.NAME_TAG,
					name = Component.literal("Rename").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Rename the selected benchmark folder.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.openRenameInput(player, pathSegments, page, selectedEntry)
				}
			}
			setButton(
				footerLeftInnerSlot,
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Delete").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal("Delete the selected benchmark folder.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					deleteSelectedEntry(player, selectedEntry)
				}
			}
		}

		setButton(
			footerRightOuterSlot,
			MenuItems.menuItem(
				item = Items.PISTON,
				name = Component.literal("Move").withStyle(ChatFormatting.YELLOW),
				lore = listOf(
					Component.literal(
						if (pathSegments.isEmpty()) {
							"Select a folder to move into, or use Back when possible."
						} else {
							"Select a folder to move into, or use Back to move it up one level."
						}
					).withStyle(ChatFormatting.GRAY)
				),
			),
		) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				movingStoredName = selectedEntry.storedName
				refreshMenu()
			}
		}
	}

	private fun deleteSelectedEntry(player: ServerPlayer, entry: BenchmarkCatalogManager.CatalogEntry) {
		BenchmarkCatalogManager.deleteEntry(pathSegments, entry)
			.onSuccess {
				selectedStoredName = null
				BenchmarkMenu.openCatalog(player, pathSegments, page)
			}
			.onFailure { error ->
				player.sendSystemMessage(
					Component.literal(error.message ?: "Unable to delete benchmark item.").withStyle(ChatFormatting.RED)
				)
				BenchmarkMenu.openCatalog(player, pathSegments, page, entry.storedName)
			}
	}

	private fun handleMoveModeClick(player: ServerPlayer, clickedEntry: BenchmarkCatalogManager.CatalogEntry) {
		val selectedEntry = BenchmarkCatalogManager.listEntries(pathSegments).getOrNull()
			?.firstOrNull { it.storedName == movingStoredName }
			?: return
		if (clickedEntry.storedName == movingStoredName) {
			movingStoredName = null
			refreshMenu()
			return
		}
		if (clickedEntry.kind != BenchmarkCatalogManager.EntryKind.FOLDER) {
			return
		}

		moveSelectedEntry(player, selectedEntry, pathSegments + clickedEntry.storedName) { movedEntry ->
			BenchmarkMenu.openCatalog(player, pathSegments + clickedEntry.storedName, initialSelection = movedEntry.storedName)
		}
	}

	private fun moveSelectedEntryToParent(player: ServerPlayer) {
		val selectedEntry = BenchmarkCatalogManager.listEntries(pathSegments).getOrNull()
			?.firstOrNull { it.storedName == movingStoredName }
			?: return
		if (pathSegments.isEmpty()) {
			player.sendSystemMessage(
				Component.literal("That benchmark item is already at the top level.").withStyle(ChatFormatting.RED)
			)
			refreshMenu()
			return
		}

		val parentPath = pathSegments.dropLast(1)
		moveSelectedEntry(player, selectedEntry, parentPath) { movedEntry ->
			BenchmarkMenu.openCatalog(player, parentPath, initialSelection = movedEntry.storedName)
		}
	}

	private fun moveSelectedEntry(
		player: ServerPlayer,
		entry: BenchmarkCatalogManager.CatalogEntry,
		targetPathSegments: List<String>,
		onSuccess: (BenchmarkCatalogManager.CatalogEntry) -> Unit,
	) {
		BenchmarkCatalogManager.moveEntry(pathSegments, entry, targetPathSegments)
			.onSuccess { movedEntry ->
				movingStoredName = null
				selectedStoredName = null
				onSuccess(movedEntry)
			}
			.onFailure { error ->
				player.sendSystemMessage(
					Component.literal(error.message ?: "Unable to move benchmark item.").withStyle(ChatFormatting.RED)
				)
				refreshMenu()
			}
	}

	private fun placeSelectedPreset(player: ServerPlayer, entry: BenchmarkCatalogManager.CatalogEntry) {
		BenchmarkPresetManager.placePresetHere(playerId, pathSegments, entry)
			.onSuccess { summary ->
				player.sendSystemMessage(
					Component.literal("Placed benchmark preset at your position. ").withStyle(ChatFormatting.YELLOW)
						.append(
							Component.literal(
								"Blocks: ${summary.placedBlockCount}, tools: ${summary.acceptedToolCallCount}, sandbox: ${summary.sandboxDescription}"
							).withStyle(ChatFormatting.GRAY)
						)
				)
				BenchmarkMenu.openCatalog(player, pathSegments, page, entry.storedName)
			}
			.onFailure { error ->
				player.sendSystemMessage(
					Component.literal(error.message ?: "Unable to place benchmark preset.").withStyle(ChatFormatting.RED)
				)
				BenchmarkMenu.openCatalog(player, pathSegments, page, entry.storedName)
			}
	}

	private fun entryItem(
		entry: BenchmarkCatalogManager.CatalogEntry,
		selected: Boolean,
		moving: Boolean,
	) = MenuItems.menuItem(
		item = when {
			entry.kind == BenchmarkCatalogManager.EntryKind.FOLDER && moving -> Items.DYED_SHULKER_BOX.yellow()
			entry.kind == BenchmarkCatalogManager.EntryKind.FOLDER && selected -> Items.DYED_SHULKER_BOX.orange()
			entry.kind == BenchmarkCatalogManager.EntryKind.FOLDER -> Items.DYED_SHULKER_BOX.white()
			moving -> Items.DYED_CANDLE.yellow()
			selected -> Items.DYED_CANDLE.orange()
			else -> Items.DYED_CANDLE.white()
		},
		name = Component.literal(entry.displayName).withStyle(ChatFormatting.YELLOW),
		lore = if (entry.kind == BenchmarkCatalogManager.EntryKind.FOLDER) {
			listOf(Component.literal(folderActionText(selected, moving)).withStyle(ChatFormatting.GRAY))
		} else {
			presetLore(entry)
		},
	)

	private fun folderActionText(selected: Boolean, moving: Boolean): String {
		return when {
			moving -> "Selected for moving. Click a folder to move it there, or use Back to move it up one level."
			movingStoredName != null -> "Click to move the selected item into this folder."
			selected -> "Click again to open this folder."
			else -> "Click to select this folder."
		}
	}

	private fun presetActionText(selected: Boolean, moving: Boolean): String {
		return when {
			moving -> "Selected for moving. Click a folder to move it there, or use Back to move it up one level."
			movingStoredName != null -> "Only folders are valid move targets."
			selected -> "Use the footer buttons to place, edit, or move this preset."
			else -> "Click to select this preset."
		}
	}

	private fun presetLore(entry: BenchmarkCatalogManager.CatalogEntry): List<Component> {
		val metadata = BenchmarkPresetManager.metadata(pathSegments, entry).getOrNull()
		val tagNames = BenchmarkPresetManager.selectedTagIds(pathSegments, entry).getOrNull()
			?.let { selectedTagIds ->
				BenchmarkTagManager.listTags().getOrNull()
					?.filter { tag -> tag.id in selectedTagIds }
					?.sortedBy { tag -> tag.name.lowercase() }
					?.map { tag -> tag.name }
			}
		if (metadata == null) {
			return listOf(
				Component.literal("size: unavailable").withStyle(ChatFormatting.GRAY),
				Component.literal("summary: unavailable").withStyle(ChatFormatting.GRAY),
			)
		}

		return listOf(
			Component.literal("size: ${metadata.sizeX} x ${metadata.sizeY} x ${metadata.sizeZ}").withStyle(ChatFormatting.GRAY),
			Component.literal("summary: ${metadata.summary.ifBlank { "none" }}").withStyle(ChatFormatting.GRAY),
			Component.literal("tags: ${tagNames?.takeIf(List<String>::isNotEmpty)?.joinToString(" ● ") ?: "none"}").withStyle(ChatFormatting.GRAY),
			Component.literal("validation: ${metadata.postValidation}").withStyle(ChatFormatting.GRAY),
		)
	}
}
