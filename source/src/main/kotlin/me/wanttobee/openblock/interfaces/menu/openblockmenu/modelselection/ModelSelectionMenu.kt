package me.wanttobee.openblock.interfaces.menu.openblockmenu.modelselection

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import java.util.UUID

internal class ModelSelectionMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val providerName: String,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 3, contentRows = 2) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val models = Providers.modelList(providerName).getOrElse { emptyList() }
		val currentTarget = AiService.currentTarget(playerId).getOrNull()

		pageEntries(models).forEachIndexed { index, model ->
			val selected = currentTarget?.provider?.name == providerName &&
				currentTarget.model.apiName.equals(model.apiName, ignoreCase = true)
			setButton(index, modelItem(model, selected)) { player, button, input ->
				if (button != 0 || input != ContainerInput.PICKUP) {
					return@setButton
				}

				val target = AiService.selectTarget(playerId, providerName, model.apiName).getOrElse { return@setButton }
				if (target.model.reasoningSupport.supportsReasoning()) {
					OpenBlockMenu.openReasoningSelection(player, providerName, target.model.apiName)
				} else {
					OpenBlockMenu.openMain(player)
				}
			}
		}

		addPageNavigation(models.size) { player ->
			OpenBlockMenu.openProviderSelection(player)
		}
		broadcastChanges()
	}

	private fun modelItem(model: AiModel, selected: Boolean): net.minecraft.world.item.ItemStack {
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: return net.minecraft.world.item.ItemStack.EMPTY
		return MenuItems.menuItem(
			item = OpenBlockMenuSupport.providerWool(provider),
			name = Component.literal(model.displayName).withStyle(ChatFormatting.WHITE),
			lore = listOf(
				Component.literal("Provider: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.WHITE)),
				Component.literal("Reasoning: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(OpenBlockMenuSupport.reasoningSupportLabel(model)).withStyle(ChatFormatting.WHITE)),
			),
			glint = selected,
		)
	}
}
