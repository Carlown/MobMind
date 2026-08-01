package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 监听生物死亡事件：玩家杀害村庄牲畜时触发附近村民反应。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathMixin {

	@Inject(method = "die", at = @At("HEAD"))
	private void mobmind$onEntityDie(DamageSource damageSource, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) return;
		if (!(self instanceof Animal animal)) return;
		if (!(self.level() instanceof ServerLevel sl)) return;
		var killer = damageSource.getEntity();
		if (killer instanceof ServerPlayer sp) {
			MobAiService.onLivestockKilledByPlayer(animal, sp, sl);
		}
	}
}
