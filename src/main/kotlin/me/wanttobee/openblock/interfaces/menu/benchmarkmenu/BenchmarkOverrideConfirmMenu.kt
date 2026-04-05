package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseHopperMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class BenchmarkOverrideConfirmMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val pathSegments: List<String>,
	private val returnPage: Int,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
	private val summary: BenchmarkPresetManager.CaptureSummary,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()
		setDisplayItem(0, currentConfigurationItem())
		setDisplayItem(1, MenuItems.placeholderPaneItem())
		setButton(
			2,
			MenuItems.menuItem(
				item = Items.SNOWBALL,
				name = Component.literal("Cancel").withStyle(ChatFormatting.YELLOW),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openCatalog(player, pathSegments, returnPage, entry.storedName)
			}
		}
		setButton(
			3,
			MenuItems.menuItem(
				item = Items.SLIME_BALL,
				name = Component.literal("Yes").withStyle(ChatFormatting.GREEN),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkPresetManager.overwritePreset(playerId, pathSegments, entry)
					.onSuccess {
						BenchmarkMenu.openCatalog(player, pathSegments, returnPage, entry.storedName)
					}
					.onFailure { error ->
						player.sendSystemMessage(
							Component.literal(error.message ?: "Unable to override benchmark preset.").withStyle(ChatFormatting.RED),
						)
						BenchmarkMenu.openCatalog(player, pathSegments, returnPage, entry.storedName)
					}
			}
		}
		setDisplayItem(4, MenuItems.blockedPaneItem())
		broadcastChanges()
	}

	private fun currentConfigurationItem() = MenuItems.menuItem(
		item = Items.IRON_INGOT,
		name = Component.literal("Current Configurations").withStyle(ChatFormatting.YELLOW),
		lore = listOf(
			Component.literal("Accepted tool calls: ${summary.acceptedToolCallCount}").withStyle(ChatFormatting.GRAY),
			Component.literal("Relative sandbox: ${summary.sandboxDescription}").withStyle(ChatFormatting.GRAY),
			Component.literal("Exclusions: ${summary.exclusionCount}").withStyle(ChatFormatting.GRAY),
			Component.literal("Targets: ${summary.targetCount}").withStyle(ChatFormatting.GRAY),
			Component.literal("Saved blocks: ${summary.buildBlockCount}").withStyle(ChatFormatting.GRAY),
		),
	)
}
