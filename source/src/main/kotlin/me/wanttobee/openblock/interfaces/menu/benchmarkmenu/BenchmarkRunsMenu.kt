package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.benchmarking.BenchmarkRunsManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseHopperMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID
import kotlin.math.cos

internal class BenchmarkRunsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	override val refreshIntervalTicks: Long = 3L

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) {
		refreshMenu()
	}

	private fun refreshMenu() {
		resetMenu()
		val activeRuns = BenchmarkRunsManager.listActiveRuns().getOrElse { emptyList() }
		val activeLore = if (activeRuns.isEmpty()) {
			listOf(Component.literal("None").withStyle(ChatFormatting.GRAY))
		} else {
			activeRuns.flatMap { run ->
				val provider = Providers.getProviderByName(run.providerName).getOrNull()
				listOf(
					Component.empty()
						.append(
							Component.literal("● ").withStyle(
								Style.EMPTY.withColor(
									TextColor.fromRgb(providerProgressColor(provider?.progressColorA ?: 0xFFFFFF, provider?.progressColorB ?: 0xFFFFFF))
								)
							)
						)
						.append(Component.literal(modelLabel(run)).withStyle(ChatFormatting.WHITE)),
					Component.literal("last action: ${run.lastAction ?: "waiting"}").withStyle(ChatFormatting.GRAY),
				)
			}
		}
		setButton(
			0,
			MenuItems.menuItem(
				item = Items.REDSTONE_TORCH,
				name = Component.literal("Currently Running").withStyle(ChatFormatting.YELLOW),
				lore = activeLore,
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openActiveRuns(player)
			}
		}
		setDisplayItem(
			1,
			MenuItems.menuItem(
				item = Items.CANDLE,
				name = Component.literal("Color Legend").withStyle(ChatFormatting.YELLOW),
				lore = listOf(
					legendLine("white:", ChatFormatting.WHITE, "no tests run yet"),
					legendLine("gray:", ChatFormatting.GRAY, "ignored, outside max runs"),
					legendLine("cyan:", ChatFormatting.AQUA, "there are unvalidated runs"),
					legendLine("lime:", ChatFormatting.GREEN, "all tests succeeded"),
					legendLine("yellow:", ChatFormatting.YELLOW, "some succeeded, some failed"),
					legendLine("red:", ChatFormatting.RED, "all tests failed"),
				),
			),
		)
		setButton(
			2,
			MenuItems.menuItem(
				item = Items.CHEST,
				name = Component.literal("Show Completed").withStyle(ChatFormatting.YELLOW),
				lore = listOf(Component.literal("Open the benchmark result overview by model.").withStyle(ChatFormatting.GRAY)),
			),
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openCompletedRuns(player)
			}
		}
		setDisplayItem(3, MenuItems.blockedPaneItem())
		setButton(4, MenuItems.backItem()) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				BenchmarkMenu.openMain(player)
			}
		}

		broadcastChanges()
	}

	private fun modelLabel(run: BenchmarkRunsManager.ActiveRun): String {
		val modelName = Providers.getModel(run.providerName, run.modelName).getOrNull()?.displayName ?: run.modelName
		return "$modelName :: ${run.benchmarkName} (${run.currentIteration}/${run.totalIterations})"
	}

	private fun providerProgressColor(colorA: Int, colorB: Int): Int {
		val radians = (System.currentTimeMillis() % 1600L).toDouble() / 1600.0 * (Math.PI * 2.0)
		val phase = ((1.0 - cos(radians)) / 2.0).toFloat()
		val red = (colorA shr 16 and 0xFF) + (((colorB shr 16 and 0xFF) - (colorA shr 16 and 0xFF)) * phase).toInt()
		val green = (colorA shr 8 and 0xFF) + (((colorB shr 8 and 0xFF) - (colorA shr 8 and 0xFF)) * phase).toInt()
		val blue = (colorA and 0xFF) + (((colorB and 0xFF) - (colorA and 0xFF)) * phase).toInt()
		return (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
	}

	private fun legendLine(colorName: String, color: ChatFormatting, description: String): Component {
		return Component.empty()
			.append(Component.literal(colorName).withStyle(color))
			.append(Component.literal(" $description").withStyle(ChatFormatting.WHITE))
	}
}
