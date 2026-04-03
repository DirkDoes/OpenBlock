package me.wanttobee.openblock.interfaces.menu.base

import me.wanttobee.openblock.interfaces.menu.MenuItems
import net.minecraft.world.entity.player.Inventory
import java.util.UUID

abstract class BaseListMenu(
	playerId: UUID,
	containerId: Int,
	playerInventory: Inventory,
	rows: Int,
	private val contentRows: Int = rows - 1,
	initialPage: Int = 0,
) : BaseMenu(playerId, containerId, playerInventory, rows) {
	protected var page: Int = initialPage.coerceAtLeast(0)
		private set

	protected val entriesPerPage: Int = contentRows * 9
	protected val previousPageSlot: Int = contentRows * 9
	protected val backSlot: Int = previousPageSlot + 4
	protected val nextPageSlot: Int = previousPageSlot + 8

	init {
		require(rows >= 2) { "List menus need at least 2 rows." }
		require(contentRows in 1 until rows) { "Content rows must leave space for navigation." }
	}

	protected fun <T> pageEntries(entries: List<T>): List<T> {
		val pageCount = pageCount(entries.size)
		page = page.coerceIn(0, pageCount - 1)
		return entries.drop(page * entriesPerPage).take(entriesPerPage)
	}

	protected fun centerContentSlot(): Int = (entriesPerPage - 1) / 2

	protected fun addPageNavigation(totalEntryCount: Int, onBack: (net.minecraft.server.level.ServerPlayer) -> Unit) {
		val pageCount = pageCount(totalEntryCount)
		if (page > 0) {
			setButton(previousPageSlot, MenuItems.pageArrow("Previous Page")) { _, button, input ->
				if (button == 0 && input == net.minecraft.world.inventory.ContainerInput.PICKUP) {
					page -= 1
					refreshMenu()
				}
			}
		}
		setButton(backSlot, MenuItems.backItem()) { player, button, input ->
			if (button == 0 && input == net.minecraft.world.inventory.ContainerInput.PICKUP) {
				onBack(player)
			}
		}
		if (page < pageCount - 1) {
			setButton(nextPageSlot, MenuItems.pageArrow("Next Page")) { _, button, input ->
				if (button == 0 && input == net.minecraft.world.inventory.ContainerInput.PICKUP) {
					page += 1
					refreshMenu()
				}
			}
		}
	}

	protected abstract fun refreshMenu()

	private fun pageCount(totalEntryCount: Int): Int {
		return totalEntryCount.coerceAtLeast(1).let { ((it - 1) / entriesPerPage) + 1 }
	}
}
