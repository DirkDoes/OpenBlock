package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.benchmarking.BenchmarkTaskInputManager
import me.wanttobee.openblock.benchmarking.BenchmarkTagManager
import me.wanttobee.openblock.interfaces.menu.base.MenuOpener
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

internal object BenchmarkMenu {
	fun open(player: ServerPlayer) {
		openMain(player)
	}

	fun openMain(player: ServerPlayer) {
		MenuOpener.open(player, Component.literal("Benchmark")) { containerId, inventory ->
			BenchmarkMainMenu(containerId, inventory, player.uuid)
		}
	}

	fun openCatalog(
		player: ServerPlayer,
		pathSegments: List<String> = emptyList(),
		page: Int = 0,
		initialSelection: String? = null,
	) {
		MenuOpener.open(player, Component.literal(catalogTitle(pathSegments))) { containerId, inventory ->
			BenchmarkCatalogMenu(containerId, inventory, player.uuid, pathSegments, page, initialSelection)
		}
	}

	fun openTags(
		player: ServerPlayer,
		page: Int = 0,
		initialSelection: String? = null,
	) {
		MenuOpener.open(player, Component.literal("Benchmark Tags")) { containerId, inventory ->
			BenchmarkTagMenu(containerId, inventory, player.uuid, page, initialSelection)
		}
	}

	fun openCreateFolderInput(player: ServerPlayer, pathSegments: List<String>, returnPage: Int) {
		openNameInput(player, "Create Folder", Items.WHITE_SHULKER_BOX, "") { submittedName ->
			BenchmarkCatalogManager.createFolder(pathSegments, submittedName)
				.onSuccess { entry ->
					openCatalog(player, pathSegments, returnPage, entry.storedName)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to create benchmark folder.").withStyle(ChatFormatting.RED)
					)
					openCatalog(player, pathSegments, returnPage)
				}
		}
	}

	fun openCreatePresetInput(player: ServerPlayer, pathSegments: List<String>, returnPage: Int) {
		openNameInput(player, "Create Benchmark", Items.IRON_INGOT, "") { submittedName ->
			BenchmarkPresetManager.createPreset(player.uuid, pathSegments, submittedName)
				.onSuccess { entry ->
					openCatalog(player, pathSegments, returnPage, entry.storedName)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to create benchmark preset.").withStyle(ChatFormatting.RED)
					)
					openCatalog(player, pathSegments, returnPage)
				}
		}
	}

	fun openCreateTagInput(player: ServerPlayer, returnPage: Int) {
		openNameInput(player, "Create Tag", Items.LIME_CANDLE, "") { submittedName ->
			BenchmarkTagManager.createTag(submittedName)
				.onSuccess { entry ->
					openTags(player, returnPage, entry.id)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to create benchmark tag.").withStyle(ChatFormatting.RED)
					)
					openTags(player, returnPage)
				}
		}
	}

	fun openOverrideConfirm(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		val summary = BenchmarkPresetManager.currentCaptureSummary(player.uuid).getOrElse { error ->
			player.sendSystemMessage(
				Component.literal(error.message ?: "Unable to capture the current benchmark preset state.")
					.withStyle(ChatFormatting.RED),
			)
			openCatalog(player, pathSegments, returnPage, entry.storedName)
			return
		}

		MenuOpener.open(player, Component.literal("are you sure you want to override")) { containerId, inventory ->
			BenchmarkOverrideConfirmMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry, summary)
		}
	}

	fun openPresetEditMenu(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		MenuOpener.open(player, Component.literal("Preset Actions")) { containerId, inventory ->
			BenchmarkPresetEditMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry)
		}
	}

	fun openSummaryInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		val metadata = BenchmarkPresetManager.metadata(pathSegments, entry).getOrElse { error ->
			player.sendSystemMessage(
				Component.literal(error.message ?: "Unable to load benchmark preset summary.").withStyle(ChatFormatting.RED)
			)
			openPresetEditMenu(player, pathSegments, returnPage, entry)
			return
		}

		openNameInput(player, "Summary", Items.PAPER, metadata.summary) { submittedSummary ->
			BenchmarkPresetManager.updateSummary(pathSegments, entry, submittedSummary)
				.onSuccess {
					openPresetEditMenu(player, pathSegments, returnPage, entry)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to update benchmark preset summary.").withStyle(ChatFormatting.RED)
					)
					openPresetEditMenu(player, pathSegments, returnPage, entry)
				}
		}
	}

	fun openTaskInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		val metadata = BenchmarkPresetManager.metadata(pathSegments, entry).getOrElse { error ->
			player.sendSystemMessage(
				Component.literal(error.message ?: "Unable to load benchmark preset task.").withStyle(ChatFormatting.RED)
			)
			openPresetEditMenu(player, pathSegments, returnPage, entry)
			return
		}

		player.sendSystemMessage(
			Component.literal("Type the benchmark task in chat. Send 'cancel' to abort.").withStyle(ChatFormatting.YELLOW)
		)
		if (metadata.task.isNotBlank()) {
			player.sendSystemMessage(
				Component.literal("Current task: ${metadata.task}").withStyle(ChatFormatting.GRAY)
			)
		}
		BenchmarkTaskInputManager.start(
			player = player,
			onSubmit = { sender, submittedTask ->
				BenchmarkPresetManager.updateTask(pathSegments, entry, submittedTask)
					.onSuccess {
						openPresetEditMenu(sender, pathSegments, returnPage, entry)
					}
					.onFailure { error ->
						sender.sendSystemMessage(
							Component.literal(error.message ?: "Unable to update benchmark preset task.").withStyle(ChatFormatting.RED)
						)
						openPresetEditMenu(sender, pathSegments, returnPage, entry)
					}
			},
			onCancel = { sender ->
				sender.sendSystemMessage(Component.literal("Benchmark task edit cancelled.").withStyle(ChatFormatting.GRAY))
				openPresetEditMenu(sender, pathSegments, returnPage, entry)
			},
		)
	}

	fun openPresetTagMenu(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int = 0,
	) {
		MenuOpener.open(player, Component.literal("Preset Tags")) { containerId, inventory ->
			BenchmarkPresetTagMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry, page)
		}
	}

	fun openRenameInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		openNameInput(player, "Rename", entry.iconItem(), entry.displayName) { submittedName ->
			BenchmarkCatalogManager.renameEntry(pathSegments, entry, submittedName)
				.onSuccess { renamedEntry ->
					openCatalog(player, pathSegments, returnPage, renamedEntry.storedName)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to rename benchmark item.").withStyle(ChatFormatting.RED)
					)
					openCatalog(player, pathSegments, returnPage, entry.storedName)
				}
		}
	}

	fun openRenameTagInput(
		player: ServerPlayer,
		returnPage: Int,
		entry: BenchmarkTagManager.TagEntry,
	) {
		openNameInput(player, "Rename Tag", Items.WHITE_CANDLE, entry.name) { submittedName ->
			BenchmarkTagManager.renameTag(entry, submittedName)
				.onSuccess { renamedEntry ->
					openTags(player, returnPage, renamedEntry.id)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to rename benchmark tag.").withStyle(ChatFormatting.RED)
					)
					openTags(player, returnPage, entry.id)
				}
		}
	}

	private fun openNameInput(
		player: ServerPlayer,
		title: String,
		icon: Item,
		initialName: String,
		onSubmit: (String) -> Unit,
	) {
		MenuOpener.open(player, Component.literal(title)) { containerId, inventory ->
			BenchmarkNameInputMenu(containerId, inventory, icon, initialName, onSubmit)
		}
	}

	private fun BenchmarkCatalogManager.CatalogEntry.iconItem(): Item {
		return when (kind) {
			BenchmarkCatalogManager.EntryKind.FOLDER -> Items.WHITE_SHULKER_BOX
			BenchmarkCatalogManager.EntryKind.PRESET -> Items.IRON_INGOT
		}
	}

	private fun catalogTitle(pathSegments: List<String>): String {
		return if (pathSegments.isEmpty()) {
			"Root - Benchmarks"
		} else {
			"${BenchmarkCatalogManager.displayName(pathSegments.last())} - Benchmarks"
		}
	}
}
