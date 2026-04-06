package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkExecutionManager
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

internal class BenchmarkCompletedRunsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	initialPage: Int = 0,
	initialSelectionKey: String? = null,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5, initialPage = initialPage) {
	private var selectedModelKey: String? = initialSelectionKey

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val models = BenchmarkRunsManager.listTrackedModels().getOrElse { error ->
			showLoadError(error.message ?: "Unknown benchmark results error.")
			return
		}

		if (models.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.CHEST,
					name = Component.literal("No models").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Use Add Model to track benchmark runs for a model.").withStyle(ChatFormatting.GRAY)),
				),
			)
		} else {
			if (selectedModelKey !in models.map { model -> model.key() }.toSet()) {
				selectedModelKey = null
			}

			pageEntries(models).forEachIndexed { index, model ->
				val summary = BenchmarkRunsManager.modelSummary(model.providerName, model.modelName).getOrElse { error ->
					setDisplayItem(
						index,
						MenuItems.menuItem(
							item = Items.BARRIER,
							name = Component.literal(model.modelDisplayName).withStyle(ChatFormatting.RED),
							lore = listOf(Component.literal(error.message ?: "Unable to load benchmark summary.").withStyle(ChatFormatting.GRAY)),
						),
						)
						return@forEachIndexed
				}
				val selected = selectedModelKey == model.key()

				setButton(index, modelItem(summary)) { player, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP) {
						if (selected) {
							BenchmarkMenu.openModelRuns(player, model.providerName, model.modelName)
						} else {
							selectedModelKey = model.key()
							refreshMenu()
						}
					}
				}
			}
		}

		addPageNavigation(models.size) { player ->
			BenchmarkMenu.openRuns(player)
		}
		setButton(
			footerLeftOuterSlot,
			MenuItems.menuItem(
				item = Items.LIME_WOOL,
				name = Component.literal("Add Model").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Add another model to the tracked benchmark results.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openAvailableModels(player)
			}
		}
		val selectedModel = models.firstOrNull { model -> model.key() == selectedModelKey }
		if (selectedModel != null) {
			setButton(
				footerRightInnerSlot,
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Remove Model").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal("Stop tracking benchmark runs for this model.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					removeSelectedModel(player, selectedModel)
				}
			}
			setButton(
				footerRightOuterSlot,
				MenuItems.menuItem(
					item = Items.COMPARATOR,
					name = Component.literal("Run All Missing").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Queue every missing benchmark run for this model.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkExecutionManager.startMissingRuns(
						playerId = player.uuid,
						providerName = selectedModel.providerName,
						modelName = selectedModel.modelName,
					).onSuccess { queuedRuns ->
						player.sendSystemMessage(
							Component.literal("Queued $queuedRuns missing benchmark run(s).").withStyle(ChatFormatting.YELLOW),
						)
					}.onFailure { error ->
						player.sendSystemMessage(
							Component.literal(error.message ?: "Unable to queue missing benchmark runs.").withStyle(ChatFormatting.RED),
						)
					}
					BenchmarkMenu.openCompletedRuns(player, page, selectedModel.key())
				}
			}
		}
		broadcastChanges()
	}

	override fun hasDeselectableSelection(): Boolean = selectedModelKey != null

	override fun clearSelection() {
		selectedModelKey = null
		refreshMenu()
	}

	private fun showLoadError(details: String) {
		setDisplayItem(
			centerContentSlot(),
			MenuItems.menuItem(
				item = Items.BARRIER,
				name = Component.literal("Unable to load benchmark models").withStyle(ChatFormatting.RED),
				lore = listOf(Component.literal(details).withStyle(ChatFormatting.GRAY)),
			),
		)
		addPageNavigation(0) { player ->
			BenchmarkMenu.openRuns(player)
		}
		broadcastChanges()
	}

	private fun modelItem(summary: BenchmarkRunsManager.ModelSummary) = MenuItems.menuItem(
		item = OpenBlockMenuSupport.providerWool(summary.model.providerName),
		name = Component.empty()
			.append(completionPrefix(summary.total.complete))
			.append(Component.literal(summary.model.modelDisplayName).withStyle(ChatFormatting.WHITE)),
		lore = listOf(
			Component.literal("total: ${summary.total.successCount}/${summary.total.totalCount}").withStyle(ChatFormatting.GRAY),
			Component.literal("input tokens: ${summary.tokenUsage.inputTokens}").withStyle(ChatFormatting.GRAY),
			Component.literal("output tokens: ${summary.tokenUsage.outputTokens}").withStyle(ChatFormatting.GRAY),
			Component.literal("cached tokens: ${summary.tokenUsage.cachedTokens}").withStyle(ChatFormatting.GRAY),
		) + summary.tagScores.map { tag ->
			Component.literal("${tag.tagName}: ${tag.successCount}/${tag.totalCount}").withStyle(ChatFormatting.GRAY)
		},
		glint = selectedModelKey == summary.model.key(),
	)

	private fun completionPrefix(complete: Boolean): Component {
		return if (complete) {
			Component.literal("✓ ").withStyle(ChatFormatting.GREEN)
		} else {
			Component.literal("X ").withStyle(ChatFormatting.RED)
		}
	}

	private fun removeSelectedModel(player: ServerPlayer, model: BenchmarkRunsManager.ModelReference) {
		BenchmarkRunsManager.removeTrackedModel(model.providerName, model.modelName)
			.onSuccess {
				selectedModelKey = null
				BenchmarkMenu.openCompletedRuns(player, page)
			}
			.onFailure { error ->
				player.sendSystemMessage(
					Component.literal(error.message ?: "Unable to remove tracked benchmark model.").withStyle(ChatFormatting.RED),
				)
				BenchmarkMenu.openCompletedRuns(player, page, model.key())
			}
	}
}
