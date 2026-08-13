package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkExecutionManager
import me.wanttobee.openblock.benchmarking.BenchmarkRunsManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
import me.wanttobee.openblock.util.colorize
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkModelRunsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val providerName: String,
	private val modelName: String,
	private val pathSegments: List<String>,
	initialPage: Int = 0,
	initialSelection: String? = null,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5, initialPage = initialPage) {
	private var selectedStoredName: String? = initialSelection

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val entries = BenchmarkRunsManager.entrySummaries(providerName, modelName, pathSegments).getOrElse { error ->
			showLoadError(error.message ?: "Unable to load benchmark run entries.")
			return
		}

		if (selectedStoredName !in entries.map { summary -> summary.entry.storedName }.toSet()) {
			selectedStoredName = null
		}

		if (entries.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.WHITE_SHULKER_BOX,
					name = Component.literal("No benchmark entries").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("This folder does not contain any benchmark presets yet.").withStyle(ChatFormatting.GRAY)),
				),
			)
		} else {
			pageEntries(entries).forEachIndexed { index, summary ->
				val selected = selectedStoredName == summary.entry.storedName
				setButton(index, entryItem(summary, selected)) { player, button, input ->
					if (button != 0 || input != ContainerInput.PICKUP) {
						return@setButton
					}

					if (selected) {
						openSelectedEntry(player, summary.entry)
						return@setButton
					}

					selectedStoredName = summary.entry.storedName
					refreshMenu()
				}
			}
		}

		addFooter(entries)
		broadcastChanges()
	}

	override fun hasDeselectableSelection(): Boolean = selectedStoredName != null

	override fun clearSelection() {
		selectedStoredName = null
		refreshMenu()
	}

	private fun addFooter(entries: List<BenchmarkRunsManager.EntrySummary>) {
		addPageNavigation(entries.size) { player ->
			if (pathSegments.isEmpty()) {
				BenchmarkMenu.openCompletedRuns(player)
			} else {
				BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments.dropLast(1))
			}
		}

		val selectedEntry = entries.firstOrNull { summary -> summary.entry.storedName == selectedStoredName } ?: return
		if (selectedEntry.entry.kind == me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.EntryKind.FOLDER) {
			setButton(
				footerRightInnerSlot,
				MenuItems.menuItem(
					item = Items.COMPARATOR,
					name = Component.literal("Run All Missing").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Queue every missing run inside this folder.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkExecutionManager.startMissingRuns(
						playerId = player.uuid,
						providerName = providerName,
						modelName = modelName,
						pathSegments = pathSegments,
						entry = selectedEntry.entry,
					).onSuccess { queuedRuns ->
						player.sendSystemMessage(
							Component.literal("Queued $queuedRuns missing benchmark run(s).").withStyle(ChatFormatting.YELLOW),
						)
					}.onFailure { error ->
						player.sendSystemMessage(
							Component.literal(error.message ?: "Unable to queue missing benchmark runs.").withStyle(ChatFormatting.RED),
						)
					}
					BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments, page, selectedEntry.entry.storedName)
				}
			}
		}
		setButton(
			footerRightOuterSlot,
			MenuItems.menuItem(
				item = Items.HOPPER,
				name = Component.literal("Run Selected").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Choose how many runs to execute for the selected entry.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openRunSelection(player, providerName, modelName, pathSegments, selectedEntry.entry, page)
			}
		}
	}

	private fun showLoadError(details: String) {
		setDisplayItem(
			centerContentSlot(),
			MenuItems.menuItem(
				item = Items.BARRIER,
				name = Component.literal("Unable to load model runs").withStyle(ChatFormatting.RED),
				lore = listOf(Component.literal(details).withStyle(ChatFormatting.GRAY)),
			),
		)
		addPageNavigation(0) { player ->
			if (pathSegments.isEmpty()) {
				BenchmarkMenu.openCompletedRuns(player)
			} else {
				BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments.dropLast(1))
			}
		}
		broadcastChanges()
	}

	private fun openSelectedEntry(player: ServerPlayer, entry: me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.CatalogEntry) {
		when (entry.kind) {
			me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.EntryKind.FOLDER ->
				BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments + entry.storedName)

			me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.EntryKind.PRESET ->
				BenchmarkMenu.openPresetSessions(player, providerName, modelName, pathSegments, entry, page)
		}
	}

	private fun entryItem(summary: BenchmarkRunsManager.EntrySummary, selected: Boolean) = MenuItems.menuItem(
		item = when (summary.entry.kind) {
			me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.EntryKind.FOLDER ->
				Items.WHITE_SHULKER_BOX.colorize(scoreColor(summary.total))

			me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.EntryKind.PRESET ->
				Items.WHITE_CANDLE.colorize(scoreColor(summary.total))
		},
		name = Component.literal(summary.entry.displayName).withStyle(ChatFormatting.WHITE),
		lore = when (summary.entry.kind) {
			me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.EntryKind.FOLDER ->
				buildList {
					add(Component.literal("total: ${summary.total.successCount}/${summary.total.totalCount}").withStyle(ChatFormatting.GRAY))
					addAll(summary.tagScores.map { tag ->
						Component.literal("${tag.tagName}: ${tag.successCount}/${tag.totalCount}").withStyle(ChatFormatting.GRAY)
					})
					add(durationLore(summary.generationDuration.averageGenerationDurationMillis))
					addAll(tokenLore(summary.tokenUsage))
				}

			me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager.EntryKind.PRESET ->
				buildList {
					add(Component.literal("score: ${summary.total.successCount}/${summary.total.totalCount}").withStyle(ChatFormatting.GRAY))
					add(durationLore(summary.generationDuration.averageGenerationDurationMillis))
					addAll(tokenLore(summary.tokenUsage))
				}
		},
		glint = selected,
	)

	private fun scoreColor(summary: BenchmarkRunsManager.ScoreSummary): ChatFormatting {
		return when {
			!summary.anyRun -> ChatFormatting.WHITE
			!summary.complete || summary.anyUndetermined -> ChatFormatting.DARK_AQUA
			summary.allSuccessful -> ChatFormatting.GREEN
			summary.anySuccess -> ChatFormatting.YELLOW
			else -> ChatFormatting.RED
		}
	}

	private fun tokenLore(tokenUsage: BenchmarkRunsManager.TokenUsageSummary): List<Component> {
		return OpenBlockMenuSupport.standardTokenLore(
			inputTokens = tokenUsage.inputTokens,
			outputTokens = tokenUsage.outputTokens,
			cachedInputTokens = tokenUsage.cachedInputTokens,
			reasoningTokens = tokenUsage.reasoningTokens,
			estimatedCost = tokenUsage.estimatedCost,
		)
	}

	private fun durationLore(durationMillis: Long?): Component {
		return Component.literal("avg time: ").withStyle(ChatFormatting.GRAY)
			.append(
				Component.literal(durationMillis?.let(OpenBlockMenuSupport::formatGenerationDuration) ?: "n/a")
					.withStyle(ChatFormatting.WHITE)
			)
	}
}
