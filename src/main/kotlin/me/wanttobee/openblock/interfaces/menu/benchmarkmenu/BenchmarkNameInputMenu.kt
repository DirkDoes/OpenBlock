package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.UUID

internal class BenchmarkNameInputMenu(
	containerId: Int,
	playerInventory: Inventory,
	private val icon: Item,
	initialName: String,
	private val onSubmit: (String) -> Unit,
) : AnvilMenu(containerId, playerInventory, ContainerLevelAccess.NULL) {
	private val nonItalicStyle = Style.EMPTY.withItalic(false)
	private var currentName: String = initialName

	init {
		inputSlots.setItem(INPUT_SLOT, sourceStack(initialName))
		createResult()
		if (initialName.isNotBlank()) {
			setItemName(initialName)
		}
	}

	override fun createResult() {
		val baseItem = inputSlots.getItem(INPUT_SLOT)
		if (baseItem.isEmpty || currentName.isBlank()) {
			resultSlots.setItem(RESULT_SLOT, ItemStack.EMPTY)
			broadcastChanges()
			return
		}

		val result = sourceStack(currentName)
		resultSlots.setItem(RESULT_SLOT, result)
		broadcastChanges()
	}

	override fun setItemName(name: String): Boolean {
		currentName = name
		createResult()
		return super.setItemName(name)
	}

	override fun mayPickup(player: Player, hasStack: Boolean): Boolean {
		return !resultSlots.getItem(RESULT_SLOT).isEmpty
	}

	override fun onTake(player: Player, stack: ItemStack) {
		if (currentName.isBlank()) {
			return
		}

		onSubmit(currentName)
	}

	private fun sourceStack(name: String): ItemStack {
		return ItemStack(icon).apply {
			set(
				DataComponents.CUSTOM_NAME,
				if (name.isBlank()) {
					Component.empty().copy().withStyle(nonItalicStyle)
				} else {
					Component.literal(name).withStyle(nonItalicStyle)
				},
			)
		}
	}
}
