package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.interfaces.chat.ChatModeManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class MainMenuView(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
) : BaseMenu(playerId, containerId, playerInventory, 1) {
	override val refreshIntervalTicks: Long = 6L

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) {
		refreshMenu()
	}

	private fun refreshMenu() {
		resetMenu()
		for (slot in 0 until 9) {
			setDisplayItem(slot, MenuItems.blockedPaneItem())
		}
		setDisplayItem(PING_SLOT, pingItem())
		setButton(SESSIONS_SLOT, sessionsItem()) { player, button, input ->
			if (input != ContainerInput.PICKUP) {
				return@setButton
			}
			if (button == 0) {
				OpenBlockMenu.openSessions(player)
			} else if (button == 1 && AiService.clearSession(playerId)) {
				refreshMenu()
			}
		}
		setButton(MODEL_SLOT, modelItem()) { player, button, input ->
			if (input != ContainerInput.PICKUP) {
				return@setButton
			}
			if (button == 0) {
				OpenBlockMenu.openProviderSelection(player)
				return@setButton
			}

			val target = AiService.currentTarget(playerId).getOrNull() ?: return@setButton
			if (button == 1 && target.model.reasoningSupport.supportsReasoning()) {
				OpenBlockMenu.openReasoningSelection(player, target.provider.name, target.model.apiName)
			}
		}
		setButton(TOOLS_SLOT, toolsItem()) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				OpenBlockMenu.openTools(player)
			}
		}
		setButton(CHATMODE_SLOT, chatModeItem()) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				ChatModeManager.toggle(playerId)
				refreshMenu()
			}
		}
		broadcastChanges()
	}

	private fun pingItem() = MenuItems.menuItem(
		item = Items.REDSTONE_TORCH,
		name = Component.literal("Ping").withStyle(ChatFormatting.YELLOW),
		lore = providerPingLore(),
	)

	private fun sessionsItem(): net.minecraft.world.item.ItemStack {
		val session = AiService.currentSessionSummary(playerId)
		val lore = buildList {
			if (session == null) {
				add(Component.literal("Current session: draft").withStyle(ChatFormatting.DARK_GRAY))
			} else {
				add(
					Component.literal("Current session: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(OpenBlockMenuSupport.sessionLabel(session)).withStyle(ChatFormatting.WHITE))
				)
			}
			add(Component.literal("Left click: open sessions").withStyle(ChatFormatting.GRAY))
			add(Component.literal("Right click: reset to draft session").withStyle(ChatFormatting.GRAY))
		}

		return MenuItems.menuItem(
			item = if (session == null) Items.PAPER else Items.WRITABLE_BOOK,
			name = Component.literal("Sessions").withStyle(ChatFormatting.YELLOW),
			lore = lore,
		)
	}

	private fun modelItem() = MenuItems.menuItem(
		item = OpenBlockMenuSupport.providerWool(AiService.currentTarget(playerId).getOrNull()?.provider),
		name = Component.literal(AiService.currentTarget(playerId).getOrNull()?.model?.displayName ?: "Model").withStyle(ChatFormatting.WHITE),
		lore = buildList {
			val target = AiService.currentTarget(playerId).getOrNull()
			add(
				Component.literal("Reasoning: ").withStyle(ChatFormatting.GRAY)
					.append(
						Component.literal(
							OpenBlockMenuSupport.reasoningLabel(target?.provider, target?.model)
						).withStyle(ChatFormatting.WHITE)
					)
			)
			add(
				Component.literal("Provider: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(target?.provider?.displayName ?: "none").withStyle(ChatFormatting.WHITE))
			)
			add(Component.literal("Left click: choose provider and model").withStyle(ChatFormatting.GRAY))
			add(Component.literal("Right click: choose reasoning").withStyle(ChatFormatting.GRAY))
		},
	)

	private fun toolsItem() = MenuItems.menuItem(
		item = Items.COMMAND_BLOCK,
		name = Component.literal("Tools").withStyle(ChatFormatting.YELLOW),
		lore = AiService.allTools().map { tool ->
			val enabled = AiService.isToolEnabled(playerId, tool.name)
			Component.literal("${OpenBlockMenuSupport.toolDisplayName(tool)}: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
		},
	)

	private fun chatModeItem(): net.minecraft.world.item.ItemStack {
		val enabled = ChatModeManager.isEnabled(playerId)
		return MenuItems.menuItem(
			item = Items.BOOK,
			name = Component.literal("Chat Mode").withStyle(ChatFormatting.YELLOW),
			lore = listOf(
				Component.literal("State: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)),
				Component.literal("Capture normal chat as AI prompts").withStyle(ChatFormatting.GRAY),
			),
			glint = enabled,
		)
	}

	private fun providerPingLore(): List<Component> {
		val statuses = OpenBlockMenuSupport.providerStatuses()
		if (statuses.isEmpty()) {
			return listOf(Component.literal("Checking providers...").withStyle(ChatFormatting.GRAY))
		}

		return Providers.all.map { provider ->
			val status = statuses[provider.name]
			if (status == null) {
				Component.literal("? ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.GRAY))
			} else if (status.reachable) {
				Component.literal("● ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(OpenBlockMenuSupport.animatedProviderColor(provider))))
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.WHITE))
			} else {
				Component.literal("X ").withStyle(ChatFormatting.RED)
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.RED))
			}
		}
	}

	private companion object {
		const val PING_SLOT = 1
		const val SESSIONS_SLOT = 3
		const val TOOLS_SLOT = 4
		const val MODEL_SLOT = 6
		const val CHATMODE_SLOT = 7
	}
}
