package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.benchmarking.BenchmarkRunsManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
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

internal class BenchmarkActiveRunsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	initialPage: Int = 0,
	initialSelection: UUID? = null,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 3, contentRows = 2, initialPage = initialPage) {
	override val refreshIntervalTicks: Long = 3L
	private var selectedRunId: UUID? = initialSelection

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) {
		refreshMenu()
	}

	override fun refreshMenu() {
		resetMenu()
		val activeRuns = BenchmarkRunsManager.listActiveRuns().getOrElse { emptyList() }
		if (selectedRunId !in activeRuns.map(BenchmarkRunsManager.ActiveRun::id).toSet()) {
			selectedRunId = null
		}

		if (activeRuns.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.WOOL.gray(),
					name = Component.literal("No active benchmark run").withStyle(ChatFormatting.GRAY),
					lore = listOf(Component.literal("It is not running a session anymore.").withStyle(ChatFormatting.GRAY)),
				),
			)
		} else {
			pageEntries(activeRuns).forEachIndexed { index, run ->
				val selected = selectedRunId == run.id
				setButton(index, runItem(run, selected)) { _, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP) {
						selectedRunId = if (selected) null else run.id
						refreshMenu()
					}
				}
			}
		}

		addPageNavigation(activeRuns.size) { player ->
			BenchmarkMenu.openRuns(player)
		}
		val selectedRun = activeRuns.firstOrNull { run -> run.id == selectedRunId }
		if (selectedRun != null) {
			setButton(
				footerLeftOuterSlot,
				MenuItems.menuItem(
					item = Items.ENDER_PEARL,
					name = Component.literal("Warp").withStyle(ChatFormatting.AQUA),
					lore = listOf(Component.literal("Teleport to the anchored benchmark location.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkRunsManager.warpToActiveRun(player.uuid, selectedRun.id)
						.onFailure { error ->
							player.sendSystemMessage(
								Component.literal(error.message ?: "Unable to warp to that benchmark run.").withStyle(ChatFormatting.RED),
							)
						}
				}
			}
			setButton(
				footerRightInnerSlot,
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Force Stop").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal("Interrupt the current benchmark iteration now.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkRunsManager.requestForceStop(selectedRun.id)
						.onFailure { error ->
							player.sendSystemMessage(
								Component.literal(error.message ?: "Unable to force stop that benchmark run.").withStyle(ChatFormatting.RED),
							)
						}
					refreshMenu()
				}
			}
			setButton(
				footerRightOuterSlot,
				MenuItems.menuItem(
					item = Items.COMPARATOR,
					name = Component.literal("Safe Stop").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Let this iteration finish, then stop before the next one.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkRunsManager.requestSafeStop(selectedRun.id)
						.onFailure { error ->
							player.sendSystemMessage(
								Component.literal(error.message ?: "Unable to mark that benchmark run for a safe stop.").withStyle(ChatFormatting.RED),
							)
						}
					refreshMenu()
				}
			}
		}
		broadcastChanges()
	}

	override fun hasDeselectableSelection(): Boolean = selectedRunId != null

	override fun clearSelection() {
		selectedRunId = null
		refreshMenu()
	}

	private fun runItem(run: BenchmarkRunsManager.ActiveRun, selected: Boolean) = MenuItems.menuItem(
		item = OpenBlockMenuSupport.providerWool(run.providerName),
		name = Component.empty()
			.append(animatedDot(run.providerName))
			.append(Component.literal(" ${modelLabel(run)}").withStyle(ChatFormatting.WHITE)),
		lore = listOf(
			Component.literal(run.benchmarkName).withStyle(ChatFormatting.GRAY),
			Component.literal("progress: ${run.currentIteration}/${run.totalIterations}").withStyle(ChatFormatting.GRAY),
			Component.literal("last action: ${run.lastAction ?: "waiting"}").withStyle(ChatFormatting.GRAY),
		) + listOfNotNull(
			run.safeStopRequested.takeIf { it }?.let {
				Component.literal("safe stop requested").withStyle(ChatFormatting.YELLOW)
			},
		),
		glint = selected,
	)

	private fun modelLabel(run: BenchmarkRunsManager.ActiveRun): String {
		val modelName = Providers.getModel(run.providerName, run.modelName).getOrNull()?.displayName ?: run.modelName
		return "$modelName :: ${run.benchmarkName} (${run.currentIteration}/${run.totalIterations})"
	}

	private fun animatedDot(providerName: String): Component {
		val provider = Providers.getProviderByName(providerName).getOrNull()
		val color = providerProgressColor(provider?.progressColorA ?: 0xFFFFFF, provider?.progressColorB ?: 0xFFFFFF)
		return Component.literal("●").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)))
	}

	private fun providerProgressColor(colorA: Int, colorB: Int): Int {
		val radians = (System.currentTimeMillis() % 1600L).toDouble() / 1600.0 * (Math.PI * 2.0)
		val phase = ((1.0 - cos(radians)) / 2.0).toFloat()
		val red = (colorA shr 16 and 0xFF) + (((colorB shr 16 and 0xFF) - (colorA shr 16 and 0xFF)) * phase).toInt()
		val green = (colorA shr 8 and 0xFF) + (((colorB shr 8 and 0xFF) - (colorA shr 8 and 0xFF)) * phase).toInt()
		val blue = (colorA and 0xFF) + (((colorB and 0xFF) - (colorA and 0xFF)) * phase).toInt()
		return (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
	}
}
