package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

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

internal class BenchmarkTagMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	initialPage: Int = 0,
	initialSelection: String? = null,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5, initialPage = initialPage) {
	private var selectedTagId: String? = initialSelection

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val tags = BenchmarkTagManager.listTags().getOrElse { error ->
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Unable to load tags").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal(error.message ?: "Unknown benchmark tag error.").withStyle(ChatFormatting.GRAY)),
				),
			)
			addTagFooter(emptyList())
			broadcastChanges()
			return
		}

		if (selectedTagId !in tags.map(BenchmarkTagManager.TagEntry::id).toSet()) {
			selectedTagId = null
		}

		if (tags.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.WHITE_CANDLE,
					name = Component.literal("No tags").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Use the lime candle below to add a benchmark tag.").withStyle(ChatFormatting.GRAY)),
				),
			)
		} else {
			pageEntries(tags).forEachIndexed { index, tag ->
				val selected = selectedTagId == tag.id
				setButton(index, tagItem(tag, selected)) { _, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP) {
						selectedTagId = tag.id
						refreshMenu()
					}
				}
			}
		}

		addTagFooter(tags)
		broadcastChanges()
	}

	private fun addTagFooter(tags: List<BenchmarkTagManager.TagEntry>) {
		addPageNavigation(tags.size) { player ->
			BenchmarkMenu.openMain(player)
		}

		val selectedTag = tags.firstOrNull { it.id == selectedTagId }
		setButton(
			footerLeftOuterSlot,
			MenuItems.menuItem(
				item = Items.LIME_CANDLE,
				name = Component.literal("Add Tag").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Create a new benchmark tag.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openCreateTagInput(player, page)
			}
		}

		if (selectedTag != null) {
			setButton(
				footerLeftInnerSlot,
				MenuItems.menuItem(
					item = Items.NAME_TAG,
					name = Component.literal("Rename").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Rename the selected benchmark tag.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkMenu.openRenameTagInput(player, page, selectedTag)
				}
			}
			setButton(
				footerRightOuterSlot,
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Delete").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal("Delete the selected benchmark tag.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					deleteSelectedTag(player, selectedTag)
				}
			}
		}
	}

	private fun deleteSelectedTag(player: ServerPlayer, entry: BenchmarkTagManager.TagEntry) {
		BenchmarkTagManager.deleteTag(entry)
			.onSuccess {
				selectedTagId = null
				BenchmarkMenu.openTags(player, page)
			}
			.onFailure { error ->
				player.sendSystemMessage(
					Component.literal(error.message ?: "Unable to delete benchmark tag.").withStyle(ChatFormatting.RED)
				)
				BenchmarkMenu.openTags(player, page, entry.id)
			}
	}

	private fun tagItem(tag: BenchmarkTagManager.TagEntry, selected: Boolean) = MenuItems.menuItem(
		item = if (selected) Items.ORANGE_CANDLE else Items.WHITE_CANDLE,
		name = Component.literal(tag.name).withStyle(ChatFormatting.YELLOW),
	)
}
