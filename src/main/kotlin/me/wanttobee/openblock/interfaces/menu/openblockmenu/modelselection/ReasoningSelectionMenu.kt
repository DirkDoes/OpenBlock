package me.wanttobee.openblock.interfaces.menu.openblockmenu.modelselection

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseHopperMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import java.util.UUID

internal class ReasoningSelectionMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val providerName: String,
	private val modelName: String,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: return
		val model = Providers.resolveModel(providerName, modelName).getOrNull() ?: return
		val options = OpenBlockMenuSupport.reasoningOptions(provider, model)
		val currentReasoningValue = OpenBlockMenuSupport.currentReasoningValue(playerId, providerName, model.apiName)

		options.take(5).forEachIndexed { index, option ->
			setButton(
				index,
				MenuItems.menuItem(
					item = option.item,
					name = Component.literal(option.label).withStyle(ChatFormatting.WHITE),
					lore = option.description?.let { listOf(Component.literal(it).withStyle(ChatFormatting.GRAY)) } ?: emptyList(),
					glint = currentReasoningValue.equals(option.value, ignoreCase = true),
				)
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					AiService.selectTarget(playerId, providerName, modelName, option.value)
						.onSuccess { OpenBlockMenu.openMain(player) }
				}
			}
		}
		broadcastChanges()
	}
}
