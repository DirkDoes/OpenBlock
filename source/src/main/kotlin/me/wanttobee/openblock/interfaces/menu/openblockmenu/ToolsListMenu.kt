package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import java.util.UUID

internal class ToolsListMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	initialPage: Int = 0,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 3, contentRows = 2, initialPage = initialPage) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val tools = AiService.allTools()
		pageEntries(tools).forEachIndexed { index, tool ->
			setButton(index, toolItem(tool)) { player, button, input ->
				if (input != ContainerInput.PICKUP) {
					return@setButton
				}
				if (button == 1 && tool.hasConfigurationMenu) {
					OpenBlockMenu.openCommands(player, returnPage = page)
					return@setButton
				}
				if (button == 0 && AiService.setToolEnabled(playerId, tool.name, !AiService.isToolEnabled(playerId, tool.name))) {
					refreshMenu()
				}
			}
		}
		addPageNavigation(tools.size) { player ->
			OpenBlockMenu.openMain(player)
		}
		broadcastChanges()
	}

	private fun toolItem(tool: AiTool) = MenuItems.menuItem(
		item = tool.menuIcon,
		name = Component.literal(OpenBlockMenuSupport.toolDisplayName(tool)).withStyle(ChatFormatting.WHITE),
		lore = buildList {
			val enabled = AiService.isToolEnabled(playerId, tool.name)
			add(Component.literal(tool.description).withStyle(ChatFormatting.GRAY))
			add(
				Component.literal("State: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
			)
			if (tool.hasConfigurationMenu) {
				add(Component.literal("Right click: allowed commands").withStyle(ChatFormatting.DARK_GRAY))
			}
		},
		glint = AiService.isToolEnabled(playerId, tool.name),
	)
}
