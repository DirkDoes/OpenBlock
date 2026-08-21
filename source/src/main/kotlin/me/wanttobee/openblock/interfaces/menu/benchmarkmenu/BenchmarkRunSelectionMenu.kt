package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkExecutionManager
import me.wanttobee.openblock.benchmarking.BenchmarkRunsManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseHopperMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkRunSelectionMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val providerName: String,
	private val modelName: String,
	private val pathSegments: List<String>,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
	private val returnPage: Int,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	private var plannedRuns: Int = 1

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()
		val settings = BenchmarkRunsManager.settings().getOrElse { error ->
			showSettingsError(error.message ?: "Unable to load benchmark run settings.")
			return
		}
		plannedRuns = plannedRuns.coerceIn(1, settings.maxRuns)

		setDisplayItem(
			0,
			MenuItems.menuItem(
				item = entryItem(),
				name = Component.literal(BenchmarkRunsManager.runSelectionLabel(pathSegments, entry)).withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Choose how many runs to schedule.").withStyle(ChatFormatting.GRAY)),
			),
		)
		setButton(
			1,
			MenuItems.menuItem(
				item = Items.REPEATER,
				name = Component.literal("Planned Runs").withStyle(ChatFormatting.YELLOW),
				lore = listOf(
					Component.literal("current: $plannedRuns/${settings.maxRuns}").withStyle(ChatFormatting.GRAY),
					Component.literal("Left click to increase.").withStyle(ChatFormatting.GRAY),
					Component.literal("Right click to decrease.").withStyle(ChatFormatting.GRAY),
				),
				count = plannedRuns,
			),
		) { _, button, input ->
			if (input != ContainerInput.PICKUP) {
				return@setButton
			}

			when (button) {
				0 -> plannedRuns = (plannedRuns + 1).coerceAtMost(settings.maxRuns)
				1 -> plannedRuns = (plannedRuns - 1).coerceAtLeast(1)
				else -> return@setButton
			}
			refreshMenu()
		}
		setDisplayItem(2, MenuItems.blockedPaneItem())
		setButton(
			3,
			MenuItems.menuItem(
				item = Items.SNOWBALL,
				name = Component.literal("Cancel").withStyle(ChatFormatting.YELLOW),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments, returnPage, entry.storedName)
			}
		}
		setButton(
			4,
			MenuItems.menuItem(
				item = Items.SLIME_BALL,
				name = Component.literal("Run").withStyle(ChatFormatting.GREEN),
				lore = listOf(Component.literal("Start $plannedRuns planned run(s) for this benchmark entry.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkExecutionManager.startEntryRuns(player.uuid, providerName, modelName, pathSegments, entry, plannedRuns)
					.onSuccess { queuedRuns ->
						player.sendSystemMessage(
							Component.literal("Queued $queuedRuns benchmark run(s).").withStyle(ChatFormatting.YELLOW),
						)
					}
					.onFailure { error ->
						player.sendSystemMessage(
							Component.literal(error.message ?: "Unable to start benchmark run.").withStyle(ChatFormatting.RED),
						)
					}
				BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments, returnPage, entry.storedName)
			}
		}
		broadcastChanges()
	}

	private fun showSettingsError(details: String) {
		setDisplayItem(
			0,
			MenuItems.menuItem(
				item = Items.BARRIER,
				name = Component.literal("Unable to load run settings").withStyle(ChatFormatting.RED),
				lore = listOf(Component.literal(details).withStyle(ChatFormatting.GRAY)),
			),
		)
		setDisplayItem(1, MenuItems.blockedPaneItem())
		setDisplayItem(2, MenuItems.blockedPaneItem())
		setButton(
			3,
			MenuItems.menuItem(
				item = Items.SNOWBALL,
				name = Component.literal("Back").withStyle(ChatFormatting.YELLOW),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments, returnPage, entry.storedName)
			}
		}
		setDisplayItem(4, MenuItems.blockedPaneItem())
		broadcastChanges()
	}

	private fun entryItem() = when (entry.kind) {
		BenchmarkCatalogManager.EntryKind.FOLDER -> Items.DYED_SHULKER_BOX.white()
		BenchmarkCatalogManager.EntryKind.PRESET -> Items.DYED_CANDLE.white()
	}
}
