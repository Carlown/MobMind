package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	/** 生物受击后触发 AI 反应：被玩家打/被第三方打（呼救） */
	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void mobmind$onHurt(ServerLevel level, DamageSource source, float amount,
								CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) return;
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Mob mob) || !mob.isAlive()) return;
		Entity attacker = source.getEntity();
		if (attacker instanceof ServerPlayer player) {
			MobAiService.onHurtByPlayer(mob, player);
		} else if (attacker != null && !(attacker instanceof Monster)) {
			// 被玩家以外的实体攻击：向熟悉的玩家求救（怪物互殴除外）
			MobAiService.onHurtByOther(mob, attacker);
		}
	}

	/** 玩家对生物施加药水效果时触发 AI 反应 */
	@Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
			at = @At("RETURN"))
	private void mobmind$onPotion(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) return;
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Mob mob) || !mob.isAlive()) return;
		if (!(source instanceof ServerPlayer player)) return;
		MobAiService.onPotionAffected(mob, player, effect);
	}

	/** 玩家救治僵尸村民时触发 AI 反应 */
	// MC 26.2 中 LivingEntity.eat 已不存在，改在 ZombieVillagerMixin 中监听 mobInteract
}
