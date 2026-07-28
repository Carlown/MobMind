package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.ShieldBlockGoal;
import com.mobmind.state.MobMindState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	/** 生物受击后立刻触发 AI 反应：被玩家打→激怒+锁定+还手；被第三方打→呼救。
	 *  持盾牌的生物受到攻击时立刻举起盾牌格挡，并转向攻击者。
	 *  在 HEAD 触发（而非 RETURN），确保盾牌格挡伤害为 0 时仍能激怒并还手。 */
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void mobmind$onHurt(ServerLevel level, DamageSource source, float amount,
								CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Mob mob) || !mob.isAlive()) return;
		if (amount <= 0.0f) return; // 0 伤害事件（如环境/自伤）不触发

		Entity attacker = source.getEntity();

		// 友军保护：如果攻击者和受害者都是Mob且互为友军（对同一个玩家友好），取消伤害并清除目标
		if (attacker instanceof Mob attackerMob && MobMindState.areAllies(mob, attackerMob)) {
			attackerMob.setTarget(null);
			attackerMob.setLastHurtByMob(null);
			mob.setLastHurtByMob(null);
			cir.setReturnValue(false);
			return;
		}

		// 持盾牌的生物受到攻击时：立刻举盾格挡，转向攻击者，持续防御
		if (ShieldBlockGoal.isHoldingShield(mob)) {
			// 无论从哪个方向被打，都立刻举盾（然后转向攻击者）
			ShieldBlockGoal goal = ShieldBlockGoal.getFor(mob);
			if (goal != null) {
				goal.triggerBlockFrom(attacker);
			} else {
				mob.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
			}
			// 立刻转向攻击者
			if (attacker != null) {
				mob.getLookControl().setLookAt(attacker, 30.0f, 30.0f);
			}
		}

		// 立刻触发被打反应（激怒、还手、呼救），不等待 RETURN，避免盾牌格挡伤害为 0 时错过
		if (attacker instanceof ServerPlayer player) {
			MobAiService.onHurtByPlayer(mob, player);
		} else if (attacker != null && !(attacker instanceof net.minecraft.world.entity.monster.Monster)) {
			MobAiService.onHurtByOther(mob, attacker);
		}
	}

	/**
	 * 不死图腾保护：当生物持有玩家赠送的不死图腾时，阻止死亡并触发复活效果。
	 * 注入 die 方法的 HEAD，在死亡逻辑执行前拦截。
	 */
	@Inject(method = "die", at = @At("HEAD"), cancellable = true)
	private void mobmind$totemProtection(DamageSource source, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof Mob mob)) return;
		if (!(mob.level() instanceof ServerLevel)) return;

		if (!MobMindState.hasTotem(mob)) return;
		if (mob.getHealth() > 0.0f) return; // 还没死就不触发（冗余保护）

		// 消耗图腾并触发复活
		MobMindState.consumeTotem(mob);

		// 恢复生命值（1颗心 + 吸收效果给额外血量）
		mob.setHealth(1.0f);
		mob.removeAllEffects();

		// 给予与玩家相同的图腾效果
		mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
		mob.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
		mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));

		// 清除死亡目标
		mob.setTarget(null);
		mob.setLastHurtByMob(null);
		mob.deathTime = 0;

		// 播放图腾使用音效
		mob.level().broadcastEntityEvent(mob, (byte) 35); // 35 = 图腾激活粒子事件
		mob.playSound(SoundEvents.TOTEM_USE, 1.0f, 1.0f);

		// 阻止原版死亡逻辑
		ci.cancel();
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
