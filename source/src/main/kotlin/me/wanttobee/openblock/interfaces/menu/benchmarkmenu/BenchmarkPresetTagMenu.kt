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

internal class BenchmarkPresetTagMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val pathSegments: List<String>,
	private val returnPage: Int,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
	initialPage: Int = 0,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 4, contentRows = 3, initialPage = initialPage) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val tags = BenchmarkTagManager.listTags().getOrElse { error ->
			showLoadError("Unable to load tags", error.message ?: "Unknown benchmark tag error.")
			return
		}
		val selectedTagIds = BenchmarkPresetManager.selectedTagIds(pathSegments, entry).getOrElse { error ->
			showLoadError("Unable to load preset tags", error.message ?: "Unknown benchmark preset tag error.")
			return
		}

		if (tags.isEmpty()) {
				setDisplayItem(
					centerContentSlot(),
					MenuItems.menuItem(
						item = Items.DYED_CANDLE.white(),
						name = Component.literal("No tags").withStyle(ChatFormatting.YELLOW),
					),
				)
		} else {
			pageEntries(tags).forEachIndexed { index, tag ->
				val selected = tag.id in selectedTagIds
				setButton(index, tagItem(tag, selected)) { player, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP) {
						toggleTag(player, tags, selectedTagIds, tag)
					}
				}
			}
		}

		addPageNavigation(tags.size) { player ->
			BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
		}
		broadcastChanges()
	}

	private fun toggleTag(
		player: ServerPlayer,
		allTags: List<BenchmarkTagManager.TagEntry>,
		selectedTagIds: Set<String>,
		tag: BenchmarkTagManager.TagEntry,
	) {
		val visibleTagIds = allTags.map(BenchmarkTagManager.TagEntry::id).toSet()
		val nextSelectedTagIds = (selectedTagIds intersect visibleTagIds).toMutableSet().apply {
			if (tag.id in this) {
				remove(tag.id)
			} else {
				add(tag.id)
			}
		}

		BenchmarkPresetManager.updateSelectedTagIds(pathSegments, entry, nextSelectedTagIds)
			.onSuccess {
				refreshMenu()
			}
			.onFailure { error ->
				player.sendSystemMessage(
					Component.literal(error.message ?: "Unable to update benchmark preset tags.").withStyle(ChatFormatting.RED)
				)
				refreshMenu()
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

	private fun tagItem(tag: BenchmarkTagManager.TagEntry, selected: Boolean) = MenuItems.menuItem(
		item = Items.DYED_CANDLE.white(),
		name = Component.literal(tag.name).withStyle(ChatFormatting.YELLOW),
		glint = selected,
	)
}
