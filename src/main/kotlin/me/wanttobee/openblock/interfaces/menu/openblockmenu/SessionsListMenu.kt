package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class SessionsListMenu(
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
		val sessions = AiService.allSessions(playerId)
		val selectedSessionId = AiService.currentSessionId(playerId).getOrNull()

		if (sessions.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.WRITABLE_BOOK,
					name = Component.literal("No sessions yet").withStyle(ChatFormatting.YELLOW),
				)
			)
		} else {
			pageEntries(sessions).forEachIndexed { index, session ->
				setButton(index, sessionItem(index, session, selectedSessionId == session.id)) { player, button, input ->
					if (input != ContainerInput.PICKUP) {
						return@setButton
					}

					if (button == 0) {
						OpenBlockMenu.openSessionActions(player, session.id, page)
						return@setButton
					}

					if (button == 1) {
						OpenBlockMenu.openSessionMessages(player, session.id, page)
					}
				}
			}
		}

		addPageNavigation(sessions.size) { player ->
			OpenBlockMenu.openMain(player)
		}
		broadcastChanges()
	}

	private fun sessionItem(index: Int, session: SessionSummary, selected: Boolean) = MenuItems.menuItem(
		item = OpenBlockMenuSupport.providerWool(session.lastResponseProviderName),
		name = Component.literal("Session ${index + 1 + page * entriesPerPage}").withStyle(ChatFormatting.YELLOW),
		lore = buildList {
			add(Component.literal("Left click: actions").withStyle(ChatFormatting.GRAY))
			add(Component.literal("Right click: view messages").withStyle(ChatFormatting.GRAY))
			add(
				Component.literal("User messages: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(session.userMessageCount.toString()).withStyle(ChatFormatting.WHITE))
			)
			addAll(
				OpenBlockMenuSupport.standardTokenLore(
					inputTokens = session.inputTokens,
					outputTokens = session.outputTokens,
					cachedInputTokens = session.cachedInputTokens,
					reasoningTokens = session.reasoningTokens,
				)
			)
		},
		count = session.userMessageCount.coerceIn(1, 64),
		glint = selected,
	)
}
