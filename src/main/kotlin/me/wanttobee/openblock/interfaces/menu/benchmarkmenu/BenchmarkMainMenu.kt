package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

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

internal class BenchmarkMainMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		val settings = BenchmarkRunsManager.settings().getOrElse { BenchmarkRunsManager.Settings() }
		resetMenu()
		setButton(
			0,
			MenuItems.menuItem(
				item = Items.OXIDIZED_COPPER_CHEST,
				name = Component.literal("Tag").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Open benchmark tags.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openTags(player)
			}
		}
		setButton(
			1,
			MenuItems.menuItem(
				item = Items.CHEST,
				name = Component.literal("Benchmark Catalog").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Open benchmark folders and presets.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openCatalog(player)
			}
		}
		setButton(
			2,
			MenuItems.menuItem(
				item = Items.ENDER_CHEST,
				name = Component.literal("Runs").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Open the benchmark run overview.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openRuns(player)
			}
		}
		setDisplayItem(3, MenuItems.blockedPaneItem())
		setButton(
			4,
			MenuItems.menuItem(
				item = Items.REPEATER,
				name = Component.literal("Max Runs").withStyle(ChatFormatting.YELLOW),
				lore = listOf(
					Component.literal("current: ${settings.maxRuns}").withStyle(ChatFormatting.GRAY),
					Component.literal("Left click to increase.").withStyle(ChatFormatting.GRAY),
					Component.literal("Right click to decrease.").withStyle(ChatFormatting.GRAY),
				),
				count = settings.maxRuns,
			),
		) { player, button, input ->
			if (input != ContainerInput.PICKUP) {
				return@setButton
			}

			val delta = when (button) {
				0 -> 1
				1 -> -1
				else -> return@setButton
			}

			BenchmarkRunsManager.adjustMaxRuns(delta)
				.onSuccess {
					refreshMenu()
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to update benchmark max runs.").withStyle(ChatFormatting.RED),
					)
					refreshMenu()
				}
		}
		broadcastChanges()
	}
}
