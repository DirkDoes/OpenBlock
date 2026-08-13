package me.wanttobee.openblock.interfaces.menu.base

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AnvilMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack

object AnvilInputMenu {
	fun awaitResult(
		player: ServerPlayer,
		title: Component,
		itemStack: ItemStack,
		initialValue: String,
		onResult: (String?) -> Unit,
	) {
		MenuOpener.open(player, title) { containerId, inventory ->
			InputMenu(
				containerId = containerId,
				playerInventory = inventory,
				baseStack = itemStack,
				initialValue = initialValue,
				onResult = onResult,
			)
		}
	}

	private class InputMenu(
		containerId: Int,
		playerInventory: Inventory,
		private val baseStack: ItemStack,
		initialValue: String,
		private val onResult: (String?) -> Unit,
	) : AnvilMenu(containerId, playerInventory, ContainerLevelAccess.NULL) {
		private val nonItalicStyle = Style.EMPTY.withItalic(false)
		private var currentValue: String = initialValue
		private var submitted = false

		init {
			inputSlots.setItem(INPUT_SLOT, sourceStack(initialValue))
			createResult()
			if (initialValue.isNotBlank()) {
				setItemName(initialValue)
			}
		}

		override fun createResult() {
			val sourceItem = inputSlots.getItem(INPUT_SLOT)
			if (sourceItem.isEmpty || currentValue.isBlank()) {
				resultSlots.setItem(RESULT_SLOT, ItemStack.EMPTY)
				broadcastChanges()
				return
			}

			resultSlots.setItem(RESULT_SLOT, sourceStack(currentValue))
			broadcastChanges()
		}

		override fun setItemName(name: String): Boolean {
			currentValue = name
			createResult()
			return super.setItemName(name)
		}

		override fun mayPickup(player: Player, hasStack: Boolean): Boolean {
			return !resultSlots.getItem(RESULT_SLOT).isEmpty
		}

		override fun onTake(player: Player, stack: ItemStack) {
			if (currentValue.isBlank()) {
				return
			}

			submitted = true
			onResult(currentValue)
		}

		override fun removed(player: Player) {
			super.removed(player)
			if (!submitted) {
				onResult(null)
			}
		}

		private fun sourceStack(name: String): ItemStack {
			return baseStack.copy().apply {
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
}
