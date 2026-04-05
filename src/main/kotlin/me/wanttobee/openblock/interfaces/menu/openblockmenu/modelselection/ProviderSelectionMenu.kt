package me.wanttobee.openblock.interfaces.menu.openblockmenu.modelselection

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import java.util.UUID

internal class ProviderSelectionMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
) : BaseMenu(playerId, containerId, playerInventory, 2) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()
		val selectedProvider = AiService.currentTarget(playerId).getOrNull()?.provider?.name
		setProviderButton(2, "openai", selectedProvider == "openai")
		setProviderButton(4, "claude", selectedProvider == "claude")
		setProviderButton(6, "google", selectedProvider == "google")
		for (slot in 9..17) {
			setDisplayItem(slot, MenuItems.blockedPaneItem())
		}
		setButton(13, MenuItems.backItem()) { player, button, input ->
			if (button == 0 && input == net.minecraft.world.inventory.ContainerInput.PICKUP) {
				OpenBlockMenu.openMain(player)
			}
		}
		broadcastChanges()
	}

	private fun setProviderButton(slot: Int, providerName: String, selected: Boolean) {
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: return
		setButton(
			slot,
			MenuItems.menuItem(
				item = OpenBlockMenuSupport.providerWool(provider),
				name = Component.literal(provider.displayName).withStyle(ChatFormatting.WHITE),
				lore = listOf(Component.literal("Select ${provider.displayName}").withStyle(ChatFormatting.GRAY)),
				glint = selected,
			)
		) { player, button, input ->
			if (button == 0 && input == net.minecraft.world.inventory.ContainerInput.PICKUP) {
				OpenBlockMenu.openModelSelection(player, provider.name)
			}
		}
	}
}
