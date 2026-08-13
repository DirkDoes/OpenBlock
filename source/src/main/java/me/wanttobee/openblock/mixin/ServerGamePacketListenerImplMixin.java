package me.wanttobee.openblock.mixin;

import me.wanttobee.openblock.benchmarking.BenchmarkBookInputManager;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow public ServerPlayer player;

	@Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
	private void openblock$handleEditBook(ServerboundEditBookPacket packet, CallbackInfo callbackInfo) {
		if (BenchmarkBookInputManager.handleBookEdit(player, packet)) {
			callbackInfo.cancel();
		}
	}
}
