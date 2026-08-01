package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 钓鱼竿勾住生物时触发AI反应。
 * 注入 onHitEntity：当浮标击中实体时，若被勾住的是支持AI的Mob，则触发反应。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {

	@Inject(method = "onHitEntity", at = @At("HEAD"))
	private void mobmind$onHookEntity(EntityHitResult hitResult, CallbackInfo ci) {
		FishingHook self = (FishingHook) (Object) this;
		if (self.level().isClientSide()) return;

		Entity target = hitResult.getEntity();
		if (!(target instanceof Mob mob)) return;

		Player owner = self.getPlayerOwner();
		if (!(owner instanceof ServerPlayer sp)) return;

		// 避免自己勾自己触发
		if (owner != null && owner.getUUID().equals(mob.getUUID())) return;

		MobAiService.onFishingRodHooked(mob, sp);
	}
}
