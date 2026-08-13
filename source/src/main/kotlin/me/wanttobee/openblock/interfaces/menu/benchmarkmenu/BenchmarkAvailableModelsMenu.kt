package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkRunsManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkAvailableModelsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	initialPage: Int = 0,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5, initialPage = initialPage) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val models = BenchmarkRunsManager.availableModels().getOrElse { error ->
			showLoadError(error.message ?: "Unknown model availability error.")
			return
		}

		if (models.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.WHITE_WOOL,
					name = Component.literal("No models available").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Every known model is already tracked.").withStyle(ChatFormatting.GRAY)),
				),
			)
		} else {
			pageEntries(models).forEachIndexed { index, model ->
				setButton(
					index,
					MenuItems.menuItem(
						item = OpenBlockMenuSupport.providerWool(model.providerName),
						name = Component.literal(model.modelDisplayName).withStyle(ChatFormatting.WHITE),
						lore = listOf(Component.literal(model.providerDisplayName).withStyle(ChatFormatting.GRAY)),
					),
				) { player, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP) {
						BenchmarkRunsManager.addTrackedModel(model.providerName, model.modelName)
							.onSuccess {
								BenchmarkRunsManager.listTrackedModels()
									.map { trackedModels ->
										trackedModels.indexOfFirst { tracked ->
											tracked.providerName.equals(model.providerName, ignoreCase = true) &&
												tracked.modelName.equals(model.modelName, ignoreCase = true)
										}
									}
									.onSuccess { trackedIndex ->
										val targetPage = if (trackedIndex >= 0) trackedIndex / 45 else 0
										BenchmarkMenu.openCompletedRuns(
											player = player,
											page = targetPage,
											initialSelectionKey = model.key(),
										)
									}
									.onFailure {
										BenchmarkMenu.openCompletedRuns(player, initialSelectionKey = model.key())
									}
							}
							.onFailure { error ->
								player.sendSystemMessage(
									Component.literal(error.message ?: "Unable to add model.").withStyle(ChatFormatting.RED),
								)
								BenchmarkMenu.openAvailableModels(player, page)
							}
					}
				}
			}
		}

		addPageNavigation(models.size) { player ->
			BenchmarkMenu.openCompletedRuns(player)
		}
		broadcastChanges()
	}

	private fun showLoadError(details: String) {
		setDisplayItem(
			centerContentSlot(),
			MenuItems.menuItem(
				item = Items.BARRIER,
				name = Component.literal("Unable to load available models").withStyle(ChatFormatting.RED),
				lore = listOf(Component.literal(details).withStyle(ChatFormatting.GRAY)),
			),
		)
		addPageNavigation(0) { player ->
			BenchmarkMenu.openCompletedRuns(player)
		}
		broadcastChanges()
	}
}
