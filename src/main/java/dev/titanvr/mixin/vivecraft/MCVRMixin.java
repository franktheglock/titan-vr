package dev.titanvr.mixin.vivecraft;

import dev.titanvr.client.GiantClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vivecraft binds the grips to hotbar scrolling; for giants the grips grab instead. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.provider.MCVR", remap = false)
public abstract class MCVRMixin {
	@Inject(method = "changeHotbar", at = @At("HEAD"), cancellable = true)
	private void titanvr$gripsGrabForGiants(int dir, CallbackInfo ci) {
		if (GiantClient.isLocalGiant()) {
			ci.cancel();
		}
	}
}
