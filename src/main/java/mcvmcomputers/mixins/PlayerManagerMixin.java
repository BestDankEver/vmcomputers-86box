package mcvmcomputers.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcvmcomputers.MainMod;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
	/** A player leaving switches off their PC for everyone watching it. */
	@Inject(at = @At("HEAD"), method = "remove")
	public void remove(ServerPlayerEntity player, CallbackInfo ci) {
		MainMod.stopScreen(player.getUuid());
	}
}
