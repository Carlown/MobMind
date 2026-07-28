package com.mobmind.mixin;

import com.mobmind.behavior.WeaponAttackGoal;
import com.mobmind.behavior.WeaponRangedAttackGoal;
import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 当生物持有玩家给予的武器时，阻止原版近战攻击 Goal 启动，
 * 让我们自定义的 WeaponAttackGoal/WeaponRangedAttackGoal 接管攻击行为。
 * - 持近战武器时：我们的 WeaponAttackGoal（MeleeAttackGoal 子类）通过 super 调用原版逻辑，
 *   此时 this 是 WeaponAttackGoal 实例，不阻止。
 * - 持远程武器且有弹药时：阻止近战，让远程射击 Goal 获得 MOVE+LOOK 控制权。
 */
@Mixin(MeleeAttackGoal.class)
public abstract class MeleeAttackGoalMixin {

	@Shadow
	@Final
	protected PathfinderMob mob;

	@Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
	private void mobmind$suppressVanillaMeleeForCustomWeapons(CallbackInfoReturnable<Boolean> cir) {
		// 我们自己的 WeaponAttackGoal 子类直接放行，让它通过 super.canUse() 使用原版近战逻辑
		if ((Object) this instanceof WeaponAttackGoal) return;

		if (!MobMindState.hasPlayerGivenWeapon(mob)) return;

		// 苦力怕保留原版 AI（爆炸），不阻止原版近战
		if (WeaponAttackGoal.isCreeper(mob)) return;

		// 持远程武器：阻止近战，让远程射击接管（没有特殊箭时也能发射无限普通箭）
		if (WeaponRangedAttackGoal.isHoldingRangedWeapon(mob)) {
			cir.setReturnValue(false);
			return;
		}

		// 持近战武器：阻止原版近战 Goal，我们的 WeaponAttackGoal 会在同一优先级接管
		// （WeaponAttackGoal 是 MeleeAttackGoal 的子类，上面的 instanceof 检查不会拦截它）
		if (WeaponAttackGoal.isHoldingMeleeWeapon(mob)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
	private void mobmind$stopVanillaMeleeForCustomWeapons(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof WeaponAttackGoal) return;

		if (!MobMindState.hasPlayerGivenWeapon(mob)) return;

		// 苦力怕保留原版 AI（爆炸），不阻止原版近战
		if (WeaponAttackGoal.isCreeper(mob)) return;

		// 持远程武器：阻止近战，让远程射击接管（没有特殊箭时也能发射无限普通箭）
		if (WeaponRangedAttackGoal.isHoldingRangedWeapon(mob)) {
			cir.setReturnValue(false);
			return;
		}

		if (WeaponAttackGoal.isHoldingMeleeWeapon(mob)) {
			cir.setReturnValue(false);
		}
	}
}
