package com.mobmind.behavior;

import com.mobmind.persona.PersonaRegistry;
import com.mobmind.persona.PersonalityGenerator;
import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 持武器的生物的近战攻击 Goal。
 * 只在生物主手持有近战武器（剑、斧、三叉戟、重锤）且该武器由玩家给予时激活。
 * 弓/弩不算近战武器——骷髅等远程生物的原版射击 AI 不会被覆盖。
 * 攻击目标选择规则：
 * 1. 如果生物正在被某玩家激怒（被该玩家攻击），优先反击该玩家
 * 2. 其次攻击正在威胁玩家的怪物（保护玩家）
 * 3. 再其次攻击正在攻击友好生物的怪物
 * 4. 最后兜底：附近不同种类的敌对怪物（帮玩家清怪）
 * 永远不会主动攻击同种类生物，也不会攻击其他持武器/盾牌的盟友。
 */
public class WeaponAttackGoal extends MeleeAttackGoal {
	private static final double SPEED_MODIFIER = 1.25;
	private static final double ACTIVATION_RADIUS = 16.0;

	private final Mob mob;

	public WeaponAttackGoal(PathfinderMob mob) {
		super(mob, SPEED_MODIFIER, true);
		this.mob = mob;
	}

	@Override
	public boolean canUse() {
		// 只有玩家给予过武器的生物才使用自定义近战 Goal（骷髅等自然生成带武器的保留原版 AI）
		if (!MobMindState.hasPlayerGivenWeapon(mob)) return false;
		if (!isHoldingMeleeWeapon(mob)) return false;

		// 苦力怕等特殊生物保留原版 AI（爆炸），不使用自定义武器攻击逻辑
		if (isCreeper(mob)) return false;

		// 优先检查：是否正在被玩家攻击（激怒中），如果是，锁定激怒它的玩家
		LivingEntity revengeTarget = findProvokedPlayerTarget();
		if (revengeTarget != null) {
			mob.setTarget(revengeTarget);
			return super.canUse();
		}

		// 如果原版 AI 已经锁定了玩家（敌对怪物自然锁定玩家），直接使用
		LivingEntity existingTarget = mob.getTarget();
		if (existingTarget instanceof Player && existingTarget.isAlive() && canMeleeTarget(existingTarget)) {
			return super.canUse();
		}

		// 所有生物（不论敌对/中立）：不主动搜索攻击目标，只在保护玩家/友方时才攻击
		LivingEntity protectorTarget = findProtectorTarget();
		if (protectorTarget != null) {
			mob.setTarget(protectorTarget);
			return super.canUse();
		}
		return false;
	}

	@Override
	public boolean canContinueToUse() {
		if (!MobMindState.hasPlayerGivenWeapon(mob)) return false;
		if (!isHoldingMeleeWeapon(mob)) return false;
		// 苦力怕保留原版 AI（爆炸），不使用自定义近战武器攻击逻辑
		if (isCreeper(mob)) return false;
		LivingEntity target = mob.getTarget();
		if (target != null) {
			// 如果目标是激怒它的玩家，继续攻击
			if (target instanceof Player player
					&& MobMindState.isProvokedTowards(mob, player.getUUID(),
							mob.level().getLevelData().getGameTime())) {
				return super.canContinueToUse();
			}
			// 目标是正在威胁玩家/友方的敌人，继续攻击
			if (isValidProtectorTarget(target)) {
				return super.canContinueToUse();
			}
			// 原版AI锁定的玩家目标，允许继续攻击
			if (target instanceof Player && canMeleeTarget(target)) {
				return super.canContinueToUse();
			}
			// 其他目标（附近乱搜的怪物等）：清除，不继续攻击
			mob.setTarget(null);
			return false;
		}
		return super.canContinueToUse();
	}

	@Override
	public void tick() {
		// 每 tick 优先检查：是否有激怒玩家需要反击
		LivingEntity current = mob.getTarget();
		if (!(current instanceof Player) || !MobMindState.isProvokedTowards(mob, current.getUUID(),
				mob.level().getLevelData().getGameTime())) {
			LivingEntity revenge = findProvokedPlayerTarget();
			if (revenge != null && revenge != current) {
				mob.setTarget(revenge);
			}
		}
		super.tick();
	}

	/** 查找最近激怒该生物的玩家（被攻击后锁定反击目标） */
	private LivingEntity findProvokedPlayerTarget() {
		long gameTime = mob.level().getLevelData().getGameTime();
		AABB box = mob.getBoundingBox().inflate(ACTIVATION_RADIUS);
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
		AABB box = mob.getBoundingBox().inflate(ACTIVATION_RADIUS);
		// 最高优先级：正在威胁玩家的怪物（保护玩家）
		List<Mob> targetingPlayer = mob.level().getEntitiesOfClass(Mob.class, box,
				m -> m != mob && m.isAlive() && isThreatToPlayer(m) && isValidTarget(m));
		if (!targetingPlayer.isEmpty()) {
			targetingPlayer.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(mob)));
			return targetingPlayer.get(0);
		}
		// 其次：正在威胁友好生物的怪物
		List<Mob> targetingFriendly = mob.level().getEntitiesOfClass(Mob.class, box,
				m -> m != mob && m.isAlive() && isThreatToFriendlyMob(m) && isValidTarget(m));
		if (!targetingFriendly.isEmpty()) {
			targetingFriendly.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(mob)));
			return targetingFriendly.get(0);
		}
		// 兜底：附近任意可攻击的不同种类敌对怪物（玩家给的武器，帮玩家主动清怪）
		List<Monster> anyMonster = mob.level().getEntitiesOfClass(Monster.class, box,
				m -> m != mob && m.isAlive() && isValidTarget(m));
		if (!anyMonster.isEmpty()) {
			anyMonster.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(mob)));
			return anyMonster.get(0);
		}
		return null;
	}

	/**
	 * 判断是否可以近战攻击某个目标（比 isValidTarget 更宽松：
	 * 允许攻击原版 AI 自然锁定的玩家目标，只要该生物对玩家不友好/未被安抚且不是被友军防护覆盖）。
	 */
	private boolean canMeleeTarget(LivingEntity target) {
		if (target == mob) return false;
		if (!target.isAlive()) return false;
		if (target.getType() == mob.getType()) return false;
		if (target instanceof Mob t && (isHoldingMeleeWeapon(t) || WeaponRangedAttackGoal.isHoldingRangedWeapon(t)
				|| ShieldBlockGoal.isHoldingShield(t) || MobMindState.areAllies(mob, t))) return false;
		if (target instanceof Player player) {
			long gameTime = mob.level().getLevelData().getGameTime();
			java.util.UUID pid = player.getUUID();
			if (MobMindState.isProvokedTowards(mob, pid, gameTime)) return true;
			if (MobMindState.isFriendlyTo(mob, pid) || MobMindState.isCalmedTowards(mob, pid, gameTime)) return false;
			// 只有纯HOSTILE型生物可以攻击原版AI锁定的玩家，NEUTRAL/PASSIVE不主动攻击
			return MobMindState.categoryOf(mob) == PersonalityGenerator.Category.HOSTILE;
		}
		return target instanceof Mob; // 近战目标必须是Mob
	}

	/**
	 * 确认目标是否是有效的主动搜索攻击目标（用于findNearbyEnemy）。
	 * 排除：玩家、同类生物、持武器/盾牌的盟友、互为友军的生物。
	 * 激怒状态下允许攻击玩家（反击），非激怒状态下 WeaponAttackGoal 不会主动选玩家为目标。
	 */
	private boolean isValidTarget(LivingEntity target) {
		if (!(target instanceof Mob targetMob)) return false;
		if (!targetMob.isAlive()) return false;
		if (target instanceof Player) return false; // 主动搜索不选玩家
		// 不打同类生物（相同 EntityType，例如僵尸不打僵尸）
		if (targetMob.getType() == mob.getType()) return false;
		// 不打其他持武器/盾牌的生物（避免盟友互殴）
		if (isHoldingMeleeWeapon(targetMob) || WeaponRangedAttackGoal.isHoldingRangedWeapon(targetMob)
				|| ShieldBlockGoal.isHoldingShield(targetMob)) return false;
		// 不打互为友军的生物（例如跟你交朋友的僵尸和跟你交朋友的村民）
		if (MobMindState.areAllies(mob, targetMob)) return false;
		return true;
	}

	/** 中立生物/友好生物只攻击正在威胁玩家或友方的怪物（保护者模式） */
	private LivingEntity findProtectorTarget() {
		AABB box = mob.getBoundingBox().inflate(ACTIVATION_RADIUS);
		List<Mob> targetingPlayer = mob.level().getEntitiesOfClass(Mob.class, box,
				m -> m != mob && m.isAlive() && isThreatToPlayer(m) && isValidTarget(m));
		if (!targetingPlayer.isEmpty()) {
			targetingPlayer.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(mob)));
			return targetingPlayer.get(0);
		}
		List<Mob> targetingFriendly = mob.level().getEntitiesOfClass(Mob.class, box,
				m -> m != mob && m.isAlive() && isThreatToFriendlyMob(m) && isValidTarget(m));
		if (!targetingFriendly.isEmpty()) {
			targetingFriendly.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(mob)));
			return targetingFriendly.get(0);
		}
		return null;
	}

	/** 判断目标是否是正在威胁玩家/友方的有效目标 */
	private boolean isValidProtectorTarget(LivingEntity target) {
		if (!(target instanceof Mob m)) return false;
		if (!m.isAlive()) return false;
		if (m == mob) return false;
		if (m.getType() == mob.getType()) return false;
		if (isHoldingMeleeWeapon(m) || WeaponRangedAttackGoal.isHoldingRangedWeapon(m)
				|| ShieldBlockGoal.isHoldingShield(m)) return false;
		if (MobMindState.areAllies(mob, m)) return false;
		return isThreatToPlayer(m) || isThreatToFriendlyMob(m);
	}

	private boolean isThreatToPlayer(Mob m) {
		LivingEntity target = m.getTarget();
		if (!(target instanceof Player)) return false;
		if (PersonaRegistry.supports(m)
				&& (MobMindState.isFriendlyTo(m, target.getUUID())
						|| MobMindState.isCalmedTowards(m, target.getUUID(),
								m.level().getLevelData().getGameTime()))) {
			return false;
		}
		return MobMindState.categoryOf(m) == PersonalityGenerator.Category.HOSTILE;
	}

	private boolean isThreatToFriendlyMob(Mob m) {
		LivingEntity target = m.getTarget();
		if (target == null || target == mob) return false;
		if (!(target instanceof Mob targetMob)) return false;
		if (!PersonaRegistry.supports(targetMob)) return false;
		if (isHoldingMeleeWeapon(targetMob) || WeaponRangedAttackGoal.isHoldingRangedWeapon(targetMob)
				|| ShieldBlockGoal.isHoldingShield(targetMob)) return false;
		long gameTime = m.level().getLevelData().getGameTime();
		if (MobMindState.isFriendlyTo(m, target.getUUID())
				|| MobMindState.isCalmedTowards(m, target.getUUID(), gameTime)) {
			return false;
		}
		// 只有敌对怪物攻击友方才保护（同类互殴不管）
		return MobMindState.categoryOf(m) == PersonalityGenerator.Category.HOSTILE
				&& (MobMindState.isFriendlyTo(mob, target.getUUID())
				|| MobMindState.isFriendlyTo(targetMob, mob.getUUID())
				|| MobMindState.isCalmedTowards(mob, target.getUUID(), gameTime));
	}

	/** 判断生物主手是否持有近战武器（不包括弓/弩，避免覆盖骷髅的远程射击 AI） */
	public static boolean isHoldingMeleeWeapon(Mob mob) {
		ItemStack mainHand = mob.getMainHandItem();
		return isMeleeWeapon(mainHand);
	}

	/** 判断生物主手是否持有武器（包括弓/弩，用于右键装备逻辑） */
	public static boolean isHoldingWeapon(Mob mob) {
		return isWeapon(mob.getMainHandItem());
	}

	/** 判断是否是近战武器（不包括弓/弩/三叉戟，避免覆盖远程射击 AI） */
	public static boolean isMeleeWeapon(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		// 三叉戟不算近战武器——它应该远程投掷（像溺尸一样）
		if (stack.getItem() instanceof TridentItem) return false;
		// 近战武器：剑、斧等（有 WEAPON 组件）
		if (stack.get(net.minecraft.core.component.DataComponents.WEAPON) != null) return true;
		// 穿刺武器（三叉戟已被上面排除，这里只会匹配其他穿刺类）
		if (stack.get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON) != null) return true;
		// 动能武器：重锤等
		if (stack.get(net.minecraft.core.component.DataComponents.KINETIC_WEAPON) != null) return true;
		return false;
	}

	/**
	 * 判断物品栈是否是武器（包括弓/弩，用于右键装备逻辑）。
	 * MC 26.2 中武器不再通过 SwordItem 等子类区分，
	 * 而是通过数据组件标记：
	 * - {@code DataComponents.WEAPON}：剑/斧等近战武器
	 * - {@code DataComponents.PIERCING_WEAPON}：三叉戟等穿刺武器
	 * - {@code DataComponents.KINETIC_WEAPON}：重锤等动能武器
	 * 弓/弩仍保留 BowItem/CrossbowItem 类，也一并算作武器。
	 */
	public static boolean isWeapon(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = stack.getItem();
		if (isMeleeWeapon(stack)) return true;
		return item instanceof BowItem      // 弓
				|| item instanceof CrossbowItem // 弩
				|| item instanceof TridentItem; // 三叉戟
	}

	/** 判断生物是否是苦力怕（苦力怕保留原版爆炸 AI，不使用自定义武器攻击） */
	public static boolean isCreeper(Mob mob) {
		return mob instanceof net.minecraft.world.entity.monster.Creeper;
	}
}
