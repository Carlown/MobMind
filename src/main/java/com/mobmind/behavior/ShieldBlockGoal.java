package com.mobmind.behavior;

import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持盾牌的生物的格挡 Goal。
 * 只在生物副手持有盾牌（且该盾牌由玩家给予）时激活。
 * 当生物附近有敌人（玩家、怪物、投射物）或受到攻击时，会自动举起盾牌格挡。
 * 配合 LivingEntityMixin 的 hurtServer 拦截器，正面攻击伤害会被格挡。
 * 
 * 格挡时长：被打后持续举盾 5 秒（100 ticks），战斗中持续刷新。
 */
public class ShieldBlockGoal extends Goal {
	private static final double ACTIVATION_RADIUS = 8.0;
	private static final int BLOCK_DURATION_TICKS = 100; // 被打后举盾5秒

	private final PathfinderMob mob;
	private int blockTimer = 0;
	private LivingEntity currentAttacker = null;
	private int attackCooldown = 0;

	// 静态映射：Mob → ShieldBlockGoal 实例，用于受击时快速访问
	private static final Map<UUID, ShieldBlockGoal> INSTANCES = new ConcurrentHashMap<>();

	public ShieldBlockGoal(PathfinderMob mob) {
		this.mob = mob;
		setFlags(EnumSet.of(Flag.LOOK, Flag.MOVE));
		INSTANCES.put(mob.getUUID(), this);
	}

	/** 注销实例（生物死亡或卸载时调用） */
	public static void unregister(UUID mobId) {
		INSTANCES.remove(mobId);
	}

	/** 获取指定生物的 ShieldBlockGoal 实例 */
	public static ShieldBlockGoal getFor(Mob mob) {
		return INSTANCES.get(mob.getUUID());
	}

	@Override
	public boolean canUse() {
		if (!MobMindState.hasPlayerGivenWeapon(mob)) return false;
		if (!isHoldingShield(mob)) {
			if (isUsingItem()) stopUsingItem();
			return false;
		}
		if (blockTimer > 0) return true;
		if (shouldBlock()) return true;
		// 不应该继续格挡但还在举盾，停止举盾
		if (isUsingItem()) stopUsingItem();
		return false;
	}

	@Override
	public boolean canContinueToUse() {
		if (!MobMindState.hasPlayerGivenWeapon(mob)) return false;
		if (!isHoldingShield(mob)) return false;
		if (blockTimer > 0) return true;
		return shouldBlock();
	}

	@Override
	public void start() {
		blockTimer = BLOCK_DURATION_TICKS;
		startUsingItem();
	}

	@Override
	public void stop() {
		stopUsingItem();
		blockTimer = 0;
		currentAttacker = null;
	}

	@Override
	public void tick() {
		if (attackCooldown > 0) attackCooldown--;

		// 只要附近有威胁或正在被打，就持续刷新格挡时间
		if (shouldBlock()) {
			blockTimer = BLOCK_DURATION_TICKS;
		} else if (blockTimer > 0) {
			blockTimer--;
		}

		// 确保盾牌一直举着
		if (blockTimer > 0 && !isUsingItem()) {
			startUsingItem();
		}

		// 没有威胁且计时归零，放下盾牌
		if (blockTimer <= 0 && !shouldBlock() && isUsingItem()) {
			stopUsingItem();
			currentAttacker = null;
		}

		// 格挡时面向当前威胁/攻击者
		LivingEntity threat = getCurrentThreat();
		if (threat != null && isUsingItem()) {
			mob.getLookControl().setLookAt(threat, 30.0f, 30.0f);
			// 格挡时面向威胁，慢慢接近或原地防御（不快速冲向敌人避免放下盾牌）
			if (blockTimer > 60 && mob.distanceToSqr(threat) > 9.0) {
				// 举盾防御时慢慢靠近敌人（3格外）
				mob.getNavigation().moveTo(threat, 0.8);
			} else {
				mob.getNavigation().stop();
			}
		}
	}

	/** 外部调用：生物受到攻击时立刻举盾格挡，记录攻击者 */
	public void triggerBlockFrom(Entity attacker) {
		if (!MobMindState.hasPlayerGivenWeapon(mob)) return;
		if (!isHoldingShield(mob)) return;
		blockTimer = BLOCK_DURATION_TICKS;
		attackCooldown = 20; // 1秒攻击冷却
		if (attacker instanceof LivingEntity living) {
			currentAttacker = living;
		}
		if (!isUsingItem()) {
			startUsingItem();
		}
	}

	/** 判断是否应该开始格挡：附近有威胁或正在被攻击 */
	private boolean shouldBlock() {
		// 刚刚被打，必须格挡
		if (blockTimer > 0) return true;

		// 有攻击目标且在范围内
		if (mob.getTarget() != null && mob.distanceToSqr(mob.getTarget()) < ACTIVATION_RADIUS * ACTIVATION_RADIUS) {
			return true;
		}

		// 生命值低于一半时保持警惕举盾
		if (mob.getHealth() < mob.getMaxHealth() * 0.5f) return true;

		// 最近被攻击过（lastHurtByMob 存在且在8格内）
		LivingEntity lastHurt = mob.getLastHurtByMob();
		if (lastHurt != null && lastHurt.isAlive() && mob.distanceToSqr(lastHurt) < ACTIVATION_RADIUS * ACTIVATION_RADIUS) {
			return true;
		}

		// 当前有攻击者（triggerBlockFrom 记录的）
		if (currentAttacker != null && currentAttacker.isAlive()
				&& mob.distanceToSqr(currentAttacker) < ACTIVATION_RADIUS * ACTIVATION_RADIUS) {
			return true;
		}

		// 附近有敌对怪物
		AABB box = mob.getBoundingBox().inflate(ACTIVATION_RADIUS);
		List<net.minecraft.world.entity.monster.Monster> nearbyEnemies = mob.level().getEntitiesOfClass(
				net.minecraft.world.entity.monster.Monster.class, box,
				m -> m != mob && m.isAlive() && !isAlly(m));
		if (!nearbyEnemies.isEmpty()) return true;

		// 附近有投射物（箭等）朝自己飞来
		List<Projectile> projectiles = mob.level().getEntitiesOfClass(Projectile.class,
				mob.getBoundingBox().inflate(4.0), p -> p.isAlive() && isProjectileComingAtMob(p));
		if (!projectiles.isEmpty()) return true;

		return false;
	}

	/** 获取当前最应该面对的威胁 */
	private LivingEntity getCurrentThreat() {
		// 优先面对刚打自己的攻击者
		if (currentAttacker != null && currentAttacker.isAlive()
				&& mob.distanceToSqr(currentAttacker) < ACTIVATION_RADIUS * ACTIVATION_RADIUS) {
			return currentAttacker;
		}
		LivingEntity target = mob.getTarget();
		if (target != null && target.isAlive() && mob.distanceToSqr(target) < ACTIVATION_RADIUS * ACTIVATION_RADIUS) {
			return target;
		}
		LivingEntity lastHurt = mob.getLastHurtByMob();
		if (lastHurt != null && lastHurt.isAlive() && mob.distanceToSqr(lastHurt) < ACTIVATION_RADIUS * ACTIVATION_RADIUS) {
			return lastHurt;
		}
		AABB box = mob.getBoundingBox().inflate(ACTIVATION_RADIUS);
		List<net.minecraft.world.entity.monster.Monster> nearbyEnemies = mob.level().getEntitiesOfClass(
				net.minecraft.world.entity.monster.Monster.class, box,
				m -> m != mob && m.isAlive() && !isAlly(m));
		if (!nearbyEnemies.isEmpty()) {
			nearbyEnemies.sort((a, b) -> Double.compare(a.distanceToSqr(mob), b.distanceToSqr(mob)));
			return nearbyEnemies.get(0);
		}
		return null;
	}

	/** 判断另一个生物是否是盟友（持武器/盾牌的友方） */
	private boolean isAlly(Mob other) {
		if (other == mob) return true;
		if (other.getType() == mob.getType()) return true;
		return WeaponAttackGoal.isHoldingMeleeWeapon(other)
				|| WeaponRangedAttackGoal.isHoldingRangedWeapon(other)
				|| isHoldingShield(other);
	}

	/** 判断投射物是否朝生物飞来 */
	private boolean isProjectileComingAtMob(Projectile proj) {
		Vec3 vel = proj.getDeltaMovement();
		if (vel.lengthSqr() < 0.01) return false;
		Vec3 toMob = mob.position().subtract(proj.position()).normalize();
		Vec3 dir = vel.normalize();
		// 投射物方向与到生物方向的点积 > 0.5（角度 < 60度）
		return dir.dot(toMob) > 0.5;
	}

	/** 开始使用副手盾牌格挡 */
	private void startUsingItem() {
		ItemStack offhand = mob.getOffhandItem();
		if (!offhand.isEmpty() && !isUsingItem()) {
			mob.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
		}
	}

	/** 停止使用盾牌格挡 */
	private void stopUsingItem() {
		if (isUsingItem()) {
			mob.stopUsingItem();
		}
	}

	/** 判断生物是否正在使用物品（格挡中） */
	private boolean isUsingItem() {
		return mob.isUsingItem();
	}

	/** 判断生物副手是否持有盾牌 */
	public static boolean isHoldingShield(Mob mob) {
		ItemStack offhand = mob.getOffhandItem();
		return isShield(offhand);
	}

	/** 判断物品栈是否是盾牌 */
	public static boolean isShield(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		// MC 26.2 中盾牌通过 BlocksAttacks 数据组件标记
		return stack.get(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS) != null;
	}
}
