package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.interfaces.menu.base.MenuOpener
import me.wanttobee.openblock.interfaces.menu.base.MenuTracker
import me.wanttobee.openblock.interfaces.menu.openblockmenu.modelselection.ModelSelectionMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.modelselection.ProviderSelectionMenu
import me.wanttobee.openblock.interfaces.menu.openblockmenu.modelselection.ReasoningSelectionMenu
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu

object OpenBlockMenu {
	fun bind() {
		MenuTracker.bind()
	}

	fun open(player: ServerPlayer) {
		openMain(player)
	}

	internal fun openMain(player: ServerPlayer) {
		OpenBlockMenuSupport.requestProviderPingRefresh()
		open(player, Component.literal("OpenBlock")) { containerId, inventory ->
			MainMenuView(containerId, inventory, player.uuid)
		}
	}

	internal fun openSessions(player: ServerPlayer) {
		openSessions(player, 0)
	}

	internal fun openSessions(player: ServerPlayer, page: Int) {
		open(player, Component.literal("Sessions")) { containerId, inventory ->
			SessionsListMenu(containerId, inventory, player.uuid, page)
		}
	}

	internal fun openSessionActions(player: ServerPlayer, sessionId: java.util.UUID, returnPage: Int) {
		open(player, Component.literal("Session Actions")) { containerId, inventory ->
			SessionActionMenu(containerId, inventory, player.uuid, sessionId, returnPage)
		}
	}

	internal fun openSessionMessages(player: ServerPlayer, sessionId: java.util.UUID, returnPage: Int) {
		open(player, Component.literal("Session Messages")) { containerId, inventory ->
			SessionMessagesMenu(containerId, inventory, player.uuid, sessionId, returnPage)
		}
	}

	internal fun openProviderSelection(player: ServerPlayer) {
		open(player, Component.literal("Select Provider")) { containerId, inventory ->
			ProviderSelectionMenu(containerId, inventory, player.uuid)
		}
	}

	internal fun openModelSelection(player: ServerPlayer, providerName: String) {
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: return
		open(player, Component.literal("${provider.displayName} Models")) { containerId, inventory ->
			ModelSelectionMenu(containerId, inventory, player.uuid, provider.name)
		}
	}

	internal fun openReasoningSelection(player: ServerPlayer, providerName: String, modelName: String) {
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: return
		val model = Providers.resolveModel(providerName, modelName).getOrNull() ?: return
		if (!model.reasoningSupport.supportsReasoning()) {
			return
		}

		open(player, Component.literal("Reasoning")) { containerId, inventory ->
			ReasoningSelectionMenu(containerId, inventory, player.uuid, provider.name, model.apiName)
		}
	}

	internal fun openTools(player: ServerPlayer, page: Int = 0) {
		open(player, Component.literal("Tools")) { containerId, inventory ->
			ToolsListMenu(containerId, inventory, player.uuid, page)
		}
	}

	internal fun openCommands(player: ServerPlayer, page: Int = 0, returnPage: Int = 0) {
		open(player, Component.literal("Allowed Commands")) { containerId, inventory ->
			CommandsListMenu(containerId, inventory, player.uuid, page, returnPage)
		}
	}

	private fun open(
		player: ServerPlayer,
		title: Component,
		factory: (containerId: Int, inventory: Inventory) -> AbstractContainerMenu,
	) {
		MenuOpener.open(player, title, factory)
	}
}
