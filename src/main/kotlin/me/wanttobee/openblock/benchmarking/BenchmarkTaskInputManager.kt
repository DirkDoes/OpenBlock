package me.wanttobee.openblock.benchmarking

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BenchmarkTaskInputManager {
	private val pendingInputs = ConcurrentHashMap<UUID, PendingInput>()

	fun bind() {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
			val pendingInput = pendingInputs.remove(sender.uuid) ?: return@register true
			val content = message.signedContent().trim()
			if (content.equals("cancel", ignoreCase = true)) {
				pendingInput.onCancel(sender)
			} else {
				pendingInput.onSubmit(sender, content)
			}
			false
		}
	}

	fun start(
		player: ServerPlayer,
		onSubmit: (ServerPlayer, String) -> Unit,
		onCancel: (ServerPlayer) -> Unit,
	) {
		pendingInputs[player.uuid] = PendingInput(
			onSubmit = onSubmit,
			onCancel = onCancel,
		)
	}

	private data class PendingInput(
		val onSubmit: (ServerPlayer, String) -> Unit,
		val onCancel: (ServerPlayer) -> Unit,
	)
}
