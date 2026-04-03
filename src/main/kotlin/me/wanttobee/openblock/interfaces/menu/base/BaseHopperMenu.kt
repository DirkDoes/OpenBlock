package me.wanttobee.openblock.interfaces.menu.base

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.HopperMenu
import net.minecraft.world.item.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

abstract class BaseHopperMenu(
	override val playerId: UUID,
	containerId: Int,
	playerInventory: Inventory,
	private val menuContainer: SimpleContainer = SimpleContainer(5),
) : HopperMenu(containerId, playerInventory, menuContainer), ManagedMenu {
	private val buttonHandlers = ConcurrentHashMap<Int, (ServerPlayer, Int, ContainerInput) -> Unit>()
	private val storageSlots = ConcurrentHashMap.newKeySet<Int>()

	override val refreshIntervalTicks: Long = Long.MAX_VALUE

	init {
		MenuTracker.register(this)
	}

	final override fun quickMoveStack(player: Player, slotId: Int): ItemStack = ItemStack.EMPTY

	final override fun removed(player: Player) {
		super.removed(player)
		MenuTracker.unregister(this)
	}

	protected fun resetMenu() {
		menuContainer.clearContent()
		buttonHandlers.clear()
		storageSlots.clear()
	}

	protected fun currentMenuStack(slot: Int): ItemStack = menuContainer.getItem(slot)

	protected fun setDisplayItem(slot: Int, stack: ItemStack) {
		menuContainer.setItem(slot, stack)
		buttonHandlers.remove(slot)
		storageSlots.remove(slot)
	}

	protected fun setButton(
		slot: Int,
		stack: ItemStack,
		onClick: (ServerPlayer, Int, ContainerInput) -> Unit,
	) {
		setDisplayItem(slot, stack)
		buttonHandlers[slot] = onClick
	}

	protected fun setStorageSlot(slot: Int, stack: ItemStack = currentMenuStack(slot)) {
		menuContainer.setItem(slot, stack)
		buttonHandlers.remove(slot)
		storageSlots += slot
	}

	override fun clicked(slotId: Int, button: Int, input: ContainerInput, player: Player) {
		if (slotId in 0 until 5) {
			val handler = buttonHandlers[slotId]
			if (handler != null) {
				val serverPlayer = player as? ServerPlayer ?: return
				handler(serverPlayer, button, input)
				return
			}
			if (!storageSlots.contains(slotId)) {
				return
			}
		}

		super.clicked(slotId, button, input, player)
	}
}
