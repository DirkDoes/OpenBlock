package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import java.util.UUID

internal class BenchmarkPresetToolsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val pathSegments: List<String>,
	private val returnPage: Int,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
	initialPage: Int = 0,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 3, contentRows = 2, initialPage = initialPage) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val tools = AiService.allTools()
		val enabledTools = BenchmarkPresetManager.acceptedToolNames(pathSegments, entry).getOrElse { error ->
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = net.minecraft.world.item.Items.BARRIER,
					name = Component.literal("Unable to load preset tools").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal(error.message ?: "Unknown preset tool error.").withStyle(ChatFormatting.GRAY)),
				),
			)
			addPageNavigation(0) { player ->
				BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
			}
			broadcastChanges()
			return
		}

		pageEntries(tools).forEachIndexed { index, tool ->
			setButton(index, toolItem(tool, tool.name in enabledTools)) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkPresetManager.setToolEnabled(pathSegments, entry, tool.name, tool.name !in enabledTools)
						.onSuccess {
							refreshMenu()
						}
				}
			}
		}
		addPageNavigation(tools.size) { player ->
			BenchmarkMenu.openPresetEditMenu(player, pathSegments, returnPage, entry)
		}
		broadcastChanges()
	}

	private fun toolItem(tool: AiTool, enabled: Boolean) = MenuItems.menuItem(
		item = tool.menuIcon,
		name = Component.literal(OpenBlockMenuSupport.toolDisplayName(tool)).withStyle(ChatFormatting.WHITE),
		lore = listOf(
			Component.literal(tool.description).withStyle(ChatFormatting.GRAY),
			Component.literal("State: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)),
		),
		glint = enabled,
	)
}
