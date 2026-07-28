package com.mobmind.mixin;

import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * 当生物持有玩家给予的弓时，阻止原版 RangedBowAttackGoal 启动，
 * 让我们自定义的 WeaponRangedAttackGoal 接管，以使用玩家给予的特殊箭（药水箭、光灵箭等）。
 * 自然生成的骷髅不受影响（未标记 PLAYER_GIVEN_WEAPON）。
 */
@Mixin(RangedBowAttackGoal.class)
public abstract class RangedBowAttackGoalMixin {

	private static Field MOB_FIELD_CACHE = null;
	private static boolean FIELD_RESOLVED = false;

	/** 反射获取 RangedBowAttackGoal 中保存生物实体的字段（兼容不同MC版本字段名/可见性变化） */
	private Mob getMobFromGoal() {
		if (!FIELD_RESOLVED) {
			FIELD_RESOLVED = true;
			for (Class<?> c = RangedBowAttackGoal.class; c != null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (PathfinderMob.class.isAssignableFrom(f.getType())
							|| Mob.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						MOB_FIELD_CACHE = f;
						break;
					}
				}
				if (MOB_FIELD_CACHE != null) break;
			}
		}
		if (MOB_FIELD_CACHE == null) return null;
		try {
			Object value = MOB_FIELD_CACHE.get(this);
			return value instanceof Mob m ? m : null;
		} catch (Throwable t) {
			return null;
		}
	}

	@Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
	private void mobmind$suppressVanillaBowForPlayerGivenWeapon(CallbackInfoReturnable<Boolean> cir) {
		Mob mob = getMobFromGoal();
		if (mob != null && MobMindState.hasPlayerGivenWeapon(mob)) {
			// 苦力怕保留原版 AI（爆炸），不阻止原版远程
			if (com.mobmind.behavior.WeaponAttackGoal.isCreeper(mob)) return;
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
	private void mobmind$stopVanillaBowForPlayerGivenWeapon(CallbackInfoReturnable<Boolean> cir) {
		Mob mob = getMobFromGoal();
		if (mob != null && MobMindState.hasPlayerGivenWeapon(mob)) {
			// 苦力怕保留原版 AI（爆炸），不阻止原版远程
			if (com.mobmind.behavior.WeaponAttackGoal.isCreeper(mob)) return;
			cir.setReturnValue(false);
		}
	}
}
