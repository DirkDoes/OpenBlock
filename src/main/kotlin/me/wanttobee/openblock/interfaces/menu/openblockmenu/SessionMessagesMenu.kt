package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Items
import java.util.UUID

internal class SessionMessagesMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val sessionId: UUID,
	private val returnPage: Int,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()

		val messages = AiService.loadSession(playerId, sessionId)
			.map { session ->
				session.messages().filter { message ->
					message.type == SessionMessage.Type.USER ||
						message.type == SessionMessage.Type.ASSISTANT
				}
			}
			.getOrElse { emptyList() }

		if (messages.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.WRITABLE_BOOK,
					name = Component.literal("No visible messages").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Only user messages and final AI replies are shown here.").withStyle(ChatFormatting.GRAY)),
				)
			)
		} else {
			pageEntries(messages).forEachIndexed { index, message ->
				setDisplayItem(index, messageItem(message))
			}
		}

		addPageNavigation(messages.size) { player ->
			OpenBlockMenu.openSessions(player, returnPage)
		}
		broadcastChanges()
	}

	private fun messageItem(message: SessionMessage) = MenuItems.menuItem(
		item = if (message.type == SessionMessage.Type.USER) {
			Items.LIGHT_GRAY_WOOL
		} else {
			OpenBlockMenuSupport.providerWool(message.providerName)
		},
		name = Component.literal(messageTitle(message)).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
		lore = OpenBlockMenuSupport.messageLore(message.content),
	)

	private fun messageTitle(message: SessionMessage): String {
		return if (message.type == SessionMessage.Type.USER) {
			"User"
		} else {
			OpenBlockMenuSupport.modelDisplayName(message.providerName, message.modelName)
		}
	}
}
