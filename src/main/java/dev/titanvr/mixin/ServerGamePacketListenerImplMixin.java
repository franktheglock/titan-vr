package dev.titanvr.mixin;

import dev.titanvr.giant.GiantManager;
import dev.titanvr.physics.PhysicsWorld;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	/** While ragdolled the server drives the player's position; ignore what the client says. */
	@Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
	private void titanvr$ignoreRagdollMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		if (PhysicsWorld.RAGDOLLED.contains(this.player.getId())) {
			ci.cancel();
		}
	}

	/** Neither giants (always hovering) nor airborne ragdolls should get kicked for "flying". */
	@Inject(method = "getMaximumFlyingTicks", at = @At("HEAD"), cancellable = true)
	private void titanvr$noFlyKick(Entity entity, CallbackInfoReturnable<Integer> cir) {
		if (PhysicsWorld.RAGDOLLED.contains(entity.getId()) || entity instanceof ServerPlayer sp && GiantManager.isGiant(sp)) {
			cir.setReturnValue(Integer.MAX_VALUE);
		}
	}
}
