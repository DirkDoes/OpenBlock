package me.wanttobee.openblock.benchmarking

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.Filterable
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.WritableBookContent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BenchmarkBookInputManager {
	private const val PAGE_SEPARATOR = "\n\n"
	private const val OFFHAND_INVENTORY_SLOT = 40
	private val pendingInputs = ConcurrentHashMap<UUID, PendingInput>()

	fun bind() {
		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			pendingInputs.remove(handler.player.uuid)?.restoreInventory(handler.player)
		}
	}

	fun open(
		player: ServerPlayer,
		initialText: String,
		onSubmit: (ServerPlayer, String) -> Unit,
	) {
		pendingInputs.remove(player.uuid)?.restoreInventory(player)

		val originalOffhandItem = player.offhandItem.copy()
		val book = ItemStack(Items.WRITABLE_BOOK).apply {
			set(
				DataComponents.WRITABLE_BOOK_CONTENT,
				WritableBookContent(initialPages(initialText)),
			)
		}

		pendingInputs[player.uuid] = PendingInput(
			originalOffhandItem = originalOffhandItem,
			onSubmit = onSubmit,
		)
		player.closeContainer()
		player.setItemInHand(InteractionHand.OFF_HAND, book)
		player.inventoryMenu.broadcastChanges()
		player.connection.send(ClientboundOpenBookPacket(InteractionHand.OFF_HAND))
	}

	@JvmStatic
	fun handleBookEdit(player: ServerPlayer, packet: ServerboundEditBookPacket): Boolean {
		val pendingInput = pendingInputs[player.uuid] ?: return false
		if (packet.slot() != OFFHAND_INVENTORY_SLOT) {
			return false
		}

		pendingInputs.remove(player.uuid)
		pendingInput.restoreInventory(player)
		pendingInput.onSubmit(player, packet.pages().joinToString(PAGE_SEPARATOR).trim())
		return true
	}

	private fun initialPages(initialText: String): List<Filterable<String>> {
		if (initialText.isBlank()) {
			return listOf(Filterable.passThrough(""))
		}

		return initialText.chunked(WritableBookContent.PAGE_EDIT_LENGTH)
			.take(WritableBookContent.MAX_PAGES)
			.map { page -> Filterable.passThrough(page) }
			.ifEmpty { listOf(Filterable.passThrough("")) }
	}

	private data class PendingInput(
		val originalOffhandItem: ItemStack,
		val onSubmit: (ServerPlayer, String) -> Unit,
	) {
		fun restoreInventory(player: ServerPlayer) {
			player.setItemInHand(InteractionHand.OFF_HAND, originalOffhandItem.copy())
			player.inventoryMenu.broadcastChanges()
		}
	}
}
