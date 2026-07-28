package com.mobmind.behavior;

import com.mobmind.persona.PersonaRegistry;
import com.mobmind.persona.PersonalityGenerator;
import com.mobmind.state.MobMindState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * 让持有弓/弩的生物主动射击威胁玩家或友好生物的敌对怪物。
 * 只对玩家给予过弓/弩的生物生效——自然生成带弓的骷髅等保留原版无限箭 AI。
 * 射击需要 MobMind 弹药（箭），玩家给生物箭后自动存入弹药库。
 */
public class WeaponRangedAttackGoal extends Goal {
	private final PathfinderMob mob;
	private final double speedModifier;
	private final int attackIntervalMin;
	private final float attackRadiusSqr;

	private int attackTime = -1;
	private int seeTime = 0;
	private boolean strafingClockwise = false;
	private boolean strafingBackwards = false;
	private int strafingTime = -1;

	public WeaponRangedAttackGoal(PathfinderMob mob, double speedModifier, int attackInterval, float attackRadius) {
		this.mob = mob;
		this.speedModifier = speedModifier;
		this.attackIntervalMin = attackInterval;
		this.attackRadiusSqr = attackRadius * attackRadius;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	public static boolean isHoldingRangedWeapon(Mob mob) {
		ItemStack main = mob.getMainHandItem();
		return main.getItem() instanceof BowItem || main.getItem() instanceof CrossbowItem;
	}

	public static boolean isHoldingBow(Mob mob) {
		return mob.getMainHandItem().getItem() instanceof BowItem;
	}

	public static boolean isHoldingCrossbow(Mob mob) {
		return mob.getMainHandItem().getItem() instanceof CrossbowItem;
	}

	@Override
	public boolean canUse() {
		// 只有玩家给予过武器的生物才使用自定义远程 Goal
		if (!MobMindState.hasPlayerGivenWeapon(mob)) return false;
		if (!isHoldingRangedWeapon(mob)) return false;
		// 注意：没有 MobMind 特殊箭时，生物也能像原版骷髅一样发射无限普通箭

		// 苦力怕保留原版 AI（爆炸），不使用自定义远程武器攻击逻辑
		if (WeaponAttackGoal.isCreeper(mob)) return false;

		// 优先检查：是否正在被玩家攻击（激怒中）
		LivingEntity revenge = findProvokedPlayerTarget();
		if (revenge != null) {
			mob.setTarget(revenge);
			return true;
		}

		// 如果原版 AI 已经锁定了一个有效目标（例如敌对怪物自然锁定玩家），直接使用
		LivingEntity existingTarget = mob.getTarget();
		if (existingTarget != null && existingTarget.isAlive() && canShootTarget(existingTarget)) {
			return true;
		}

		// 中立生物（末影人、僵尸猪灵等）：不主动搜索攻击目标，除非被激怒或保护玩家
		PersonalityGenerator.Category cat = MobMindState.categoryOf(mob);
		if (cat != PersonalityGenerator.Category.HOSTILE) {
			// 只在有威胁玩家/友方的怪物时才出手
			LivingEntity protectorTarget = findProtectorTarget();
			if (protectorTarget != null) {
				mob.setTarget(protectorTarget);
				return true;
			}
			return false;
		}

		// 敌对型生物：主动搜索附近敌人
		LivingEntity target = findNearbyEnemy();
		if (target == null || !isValidTarget(target)) return false;
		mob.setTarget(target);
		return true;
	}

	@Override
	public boolean canContinueToUse() {
		if (!MobMindState.hasPlayerGivenWeapon(mob)) return false;
		if (!isHoldingRangedWeapon(mob)) return false;
		// 苦力怕保留原版 AI（爆炸），不使用自定义远程武器攻击逻辑
		if (WeaponAttackGoal.isCreeper(mob)) return false;
		// 注意：没有 MobMind 特殊箭时，生物也能像原版骷髅一样发射无限普通箭
		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive()) return false;
		// 如果目标是激怒它的玩家，继续攻击
		if (target instanceof Player player
				&& MobMindState.isProvokedTowards(mob, player.getUUID(),
						mob.level().getLevelData().getGameTime())) {
			return true;
		}
		// 目标是正在威胁玩家/友方的敌人，继续攻击
		if (isValidProtectorTarget(target)) {
			return true;
		}
		// 敌对生物通过原版AI自然锁定的玩家目标，允许继续射击
		if (canShootTarget(target)) {
			PersonalityGenerator.Category cat = MobMindState.categoryOf(mob);
			if (cat == PersonalityGenerator.Category.HOSTILE) return true;
		}
		// 中立生物：不继续主动攻击
		if (!isValidTarget(target)) {
			if (!(target instanceof Player)) mob.setTarget(null);
			return false;
		}
		PersonalityGenerator.Category cat = MobMindState.categoryOf(mob);
		return cat == PersonalityGenerator.Category.HOSTILE;
	}

	@Override
	public void start() {
		super.start();
		mob.setAggressive(true);
		attackTime = -1;
	}

	@Override
	public void stop() {
		super.stop();
		mob.setAggressive(false);
		seeTime = 0;
		attackTime = -1;
		mob.stopUsingItem();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		LivingEntity target = mob.getTarget();
		if (target == null || !target.isAlive()) return;

		// 每 tick 优先检查：是否有激怒玩家需要反击
		if (!(target instanceof Player) || !MobMindState.isProvokedTowards(mob, target.getUUID(),
				mob.level().getLevelData().getGameTime())) {
			LivingEntity revenge = findProvokedPlayerTarget();
			if (revenge != null && revenge != target) {
				mob.setTarget(revenge);
				target = revenge;
			}
		}

		double distSqr = mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
		boolean canSee = mob.getSensing().hasLineOfSight(target);
		boolean saw = seeTime > 0;
		if (canSee != saw) seeTime = 0;
		if (canSee) seeTime++;
		else seeTime--;

		// 距离太远时靠近，距离合适时侧移
		if (distSqr > attackRadiusSqr || seeTime < 20) {
			mob.getNavigation().moveTo(target, speedModifier);
			strafingTime = -1;
		} else {
			mob.getNavigation().stop();
			strafingTime++;
		}

		if (strafingTime >= 20) {
			if (mob.getRandom().nextFloat() < 0.3f) strafingClockwise = !strafingClockwise;
			if (mob.getRandom().nextFloat() < 0.3f) strafingBackwards = !strafingBackwards;
			strafingTime = 0;
		}

		if (strafingTime > -1) {
			if (distSqr > attackRadiusSqr * 0.75f) strafingBackwards = false;
			else if (distSqr < attackRadiusSqr * 0.25f) strafingBackwards = true;
			float forward = strafingBackwards ? -0.5f : 0.5f;
			float right = strafingClockwise ? 0.5f : -0.5f;
			mob.getMoveControl().strafe(forward, right);
		}

		mob.lookAt(target, 30f, 30f);

		if (isHoldingCrossbow(mob)) {
			tickCrossbow(target);
		} else {
			tickBow(target);
		}
	}

	private void tickBow(LivingEntity target) {
		if (mob.isUsingItem()) {
			if (!canSeeTargetWhileCharging()) {
				if (seeTime < -60) mob.stopUsingItem();
			} else if (mob.getTicksUsingItem() >= 20) {
				mob.stopUsingItem();
				shootBow(target);
				attackTime = attackIntervalMin;
			}
		} else if (--attackTime <= 0 && seeTime >= -60) {
			InteractionHand hand = ProjectileUtil.getWeaponHoldingHand(mob, Items.BOW);
			mob.startUsingItem(hand);
		}
	}

	private boolean canSeeTargetWhileCharging() {
		LivingEntity target = mob.getTarget();
		return target != null && mob.getSensing().hasLineOfSight(target);
	}

	private void shootBow(LivingEntity target) {
		if (!(mob.level() instanceof ServerLevel level)) return;
		// 优先消耗玩家给的特殊箭；如果没有特殊箭，则像原版骷髅一样发射无限普通箭
		String ammoKey = MobMindState.consumeAmmoArrow(mob);
		ItemStack arrowStack;
		if (ammoKey != null) {
			arrowStack = MobMindState.createArrowFor(ammoKey);
		} else {
			// 无特殊箭：默认普通箭（无限）
			arrowStack = new ItemStack(Items.ARROW);
		}
		ItemStack weaponStack = mob.getMainHandItem();

		// 使用原版工具方法创建箭矢实体，它会根据 arrowStack 自动识别普通箭/光灵箭/药水箭
		// 并正确复制药水效果和光灵属性
		float charge = BowItem.getPowerForTime(20);
		AbstractArrow fired = ProjectileUtil.getMobArrow(mob, arrowStack, charge, weaponStack);
		fired.setPos(mob.getX(), mob.getEyeY() - 0.1, mob.getZ());

		double dx = target.getX() - mob.getX();
		double dz = target.getZ() - mob.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double dy = target.getY(0.3333333333333333D) - fired.getY() + horizontal * 0.20000000298023224D;
		float velocity = 1.6f;
		int deviation = 14 - level.getDifficulty().getId() * 4;
		fired.shoot(dx, dy, dz, velocity, deviation);

		fired.pickup = AbstractArrow.Pickup.DISALLOWED;

		level.addFreshEntity(fired);
		mob.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1f, 1f / (mob.getRandom().nextFloat() * 0.4f + 0.8f));
	}

	private void tickCrossbow(LivingEntity target) {
		InteractionHand hand = ProjectileUtil.getWeaponHoldingHand(mob, Items.CROSSBOW);
		ItemStack weapon = mob.getItemInHand(hand);

		// 先装填：装填时就决定箭类型
		// 优先消耗玩家给的特殊箭；如果没有特殊箭，则像原版骷髅一样发射无限普通箭
		if (!CrossbowItem.isCharged(weapon)) {
			if (!mob.isUsingItem() && attackTime <= 0) {
				mob.startUsingItem(hand);
			}
			if (mob.isUsingItem() && mob.getTicksUsingItem() >= CrossbowItem.getChargeDuration(weapon, mob)) {
				String nextKey = MobMindState.consumeAmmoArrow(mob); // 装填时消耗
				ItemStack arrow;
				if (nextKey != null) {
					arrow = MobMindState.createArrowFor(nextKey);
				} else {
					// 无特殊箭：默认普通箭（无限）
					arrow = new ItemStack(Items.ARROW);
				}
				weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(arrow)));
				mob.releaseUsingItem();
			}
			return;
		}

		// 已装填则射击（弹药已在装填时消耗，这里直接射）
		if (attackTime > 0) {
			attackTime--;
			return;
		}
		if (canSeeTargetWhileCharging()) {
			if (weapon.getItem() instanceof CrossbowItem crossbow) {
				crossbow.performShooting(mob.level(), mob, hand, weapon, 1.6f, 1f, target);
			}
			attackTime = attackIntervalMin;
		}
	}

	/** 查找最近激怒该生物的玩家 */
	private LivingEntity findProvokedPlayerTarget() {
		long gameTime = mob.level().getLevelData().getGameTime();
		AABB box = mob.getBoundingBox().inflate(24.0);
		List<Player> players = mob.level().getEntitiesOfClass(Player.class, box, p -> p.isAlive());
		Player best = null;
		double bestDist = Double.MAX_VALUE;
		for (Player p : players) {
			if (MobMindState.isProvokedTowards(mob, p.getUUID(), gameTime)) {
				double d = mob.distanceToSqr(p);
				if (d < bestDist) {
					bestDist = d;
					best = p;
				}
			}
		}
		return best;
	}

	private LivingEntity findNearbyEnemy() {
		AABB box = mob.getBoundingBox().inflate(24.0);
		List<Monster> candidates = mob.level().getEntitiesOfClass(Monster.class, box, this::isValidTarget);
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Monster m : candidates) {
			// 优先攻击正在威胁玩家或友好生物的怪物
			if (isThreatToPlayer(m) || isThreatToFriendlyMob(m)) {
				double d = m.distanceToSqr(mob);
				if (d < bestDist) {
					bestDist = d;
					best = m;
				}
			}
		}
		// 兜底：附近任意可攻击的不同种类敌对怪物（玩家给的远程武器，帮玩家主动清怪）
		if (best == null) {
			for (Monster m : candidates) {
				double d = m.distanceToSqr(mob);
				if (d < bestDist) {
					bestDist = d;
					best = m;
				}
			}
		}
		return best;
	}

	/**
	 * 判断是否可以射击某个目标（比 isValidTarget 更宽松：
	 * 允许射击原版 AI 自然锁定的玩家目标，只要该生物对玩家不友好/未被安抚且不是被友军防护覆盖）。
	 */
	private boolean canShootTarget(LivingEntity target) {
		if (target == mob) return false;
		if (!target.isAlive()) return false;
		if (target.getType() == mob.getType()) return false;
		if (target instanceof Mob t && (WeaponAttackGoal.isHoldingMeleeWeapon(t) || isHoldingRangedWeapon(t)
				|| ShieldBlockGoal.isHoldingShield(t) || MobMindState.areAllies(mob, t))) return false;
		if (target instanceof Player player) {
			long gameTime = mob.level().getLevelData().getGameTime();
			UUID pid = player.getUUID();
			// 激怒状态：可以射击
			if (MobMindState.isProvokedTowards(mob, pid, gameTime)) return true;
			// 友好/安抚状态：不能射击
			if (MobMindState.isFriendlyTo(mob, pid) || MobMindState.isCalmedTowards(mob, pid, gameTime)) return false;
			// 非友好非激怒：只有纯HOSTILE型生物可以射击原版AI锁定的玩家
			// NEUTRAL（末影人等）和PASSIVE不主动射击玩家
			return MobMindState.categoryOf(mob) == PersonalityGenerator.Category.HOSTILE;
		}
		return true;
	}

	private boolean isValidTarget(LivingEntity target) {
		if (target == mob) return false;
		if (target instanceof Player) return false; // 非激怒状态不主动攻击玩家（主动搜索只找怪物）
		if (target.getType() == mob.getType()) return false;
		if (!target.isAlive()) return false;
		if (target instanceof Mob t && (WeaponAttackGoal.isHoldingMeleeWeapon(t) || isHoldingRangedWeapon(t)
				|| ShieldBlockGoal.isHoldingShield(t) || MobMindState.areAllies(mob, t))) return false;
		return true;
	}

	/** 中立生物/友好生物只攻击正在威胁玩家或友方的怪物（保护者模式） */
	private LivingEntity findProtectorTarget() {
		AABB box = mob.getBoundingBox().inflate(16.0);
		List<Monster> candidates = mob.level().getEntitiesOfClass(Monster.class, box,
				m -> m != mob && m.isAlive() && isValidTarget(m) && (isThreatToPlayer(m) || isThreatToFriendlyMob(m)));
		if (candidates.isEmpty()) return null;
		candidates.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(mob)));
		return candidates.get(0);
	}

	/** 判断目标是否是正在威胁玩家/友方的有效目标 */
	private boolean isValidProtectorTarget(LivingEntity target) {
		if (!(target instanceof Monster m)) return false;
		if (!m.isAlive()) return false;
		if (m == mob) return false;
		if (m.getType() == mob.getType()) return false;
		if (WeaponAttackGoal.isHoldingMeleeWeapon(m) || isHoldingRangedWeapon(m)
				|| ShieldBlockGoal.isHoldingShield(m) || MobMindState.areAllies(mob, m)) return false;
		return isThreatToPlayer(m) || isThreatToFriendlyMob(m);
	}

	private boolean isThreatToPlayer(Monster m) {
		LivingEntity target = m.getTarget();
		if (!(target instanceof Player p)) return false;
		if (!p.isAlive()) return false;
		// 如果怪物对该玩家是友好/安抚状态，则不算威胁
		long gameTime = m.level().getLevelData().getGameTime();
		if (PersonaRegistry.supports(m)
				&& (MobMindState.isFriendlyTo(m, p.getUUID())
				|| MobMindState.isCalmedTowards(m, p.getUUID(), gameTime))) {
			return false;
		}
		return true;
	}

	private boolean isThreatToFriendlyMob(Monster m) {
		LivingEntity target = m.getTarget();
		if (!(target instanceof Mob t)) return false;
		if (!t.isAlive()) return false;
		if (t == mob) return false;
		// 目标是持武器/盾牌的盟友，不算威胁（盟友自己会处理）
		if (WeaponAttackGoal.isHoldingMeleeWeapon(t) || isHoldingRangedWeapon(t) || ShieldBlockGoal.isHoldingShield(t)) return false;
		// 目标必须是对 mob 友好/安抚/同阵营的生物（我们要保护友方）
		if (!PersonaRegistry.supports(t)) return false;
		long gameTime = m.level().getLevelData().getGameTime();
		return MobMindState.isFriendlyTo(mob, t.getUUID())
				|| MobMindState.isFriendlyTo(t, mob.getUUID())
				|| MobMindState.isCalmedTowards(mob, t.getUUID(), gameTime);
	}
}
