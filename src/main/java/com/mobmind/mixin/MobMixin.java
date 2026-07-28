package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.OrderGoal;
import com.mobmind.behavior.ShieldBlockGoal;
import com.mobmind.behavior.WeaponAttackGoal;
import com.mobmind.behavior.WeaponRangedAttackGoal;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.state.MobMindState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin extends LivingEntity {

	@Shadow
	@Final
	protected GoalSelector goalSelector;

	protected MobMixin(EntityType<? extends LivingEntity> entityType, Level level) {
		super(entityType, level);
	}

	/** 为所有生物注入 MobMind 行为指令 Goal；PathfinderMob 额外注入持武器近战/远程攻击 Goal 和持盾牌格挡 Goal */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void mobmind$injectGoals(EntityType<?> entityType, Level level, CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		this.goalSelector.addGoal(2, new OrderGoal(self));
		// 持武器/盾牌的自定义 Goal 放在优先级 2（与原版近战同级），
		// MeleeAttackGoalMixin 会阻止原版近战 Goal 在玩家给予武器时启动，确保我们的 Goal 接管。
		if (self instanceof PathfinderMob pathfinder) {
			this.goalSelector.addGoal(2, new WeaponAttackGoal(pathfinder));
			this.goalSelector.addGoal(2, new WeaponRangedAttackGoal(pathfinder, 1.0, 30, 16.0f));
			this.goalSelector.addGoal(2, new ShieldBlockGoal(pathfinder));
		}
	}

	/** 高好感度或被安抚的玩家不会被主动设为攻击目标；但被激怒后照打不误。
	 *  持武器/盾牌的生物永远不会攻击玩家（玩家给的武器，不能反过来打玩家）。
	 *  友军（互为好朋友的生物，好感度≥60）之间不能互相攻击。
	 *  玩家的好朋友（≥60）不会主动攻击对玩家友好/不攻击玩家的生物，但被攻击者可以自卫。
	 *  驯服的狼也不会攻击对主人友好/安抚的生物（如友好骷髅）。 */
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void mobmind$preventTarget(LivingEntity target, CallbackInfo ci) {
		if (target == null || this.level().isClientSide()) return;
		Mob self = (Mob) (Object) this;
		long gameTime = this.level().getLevelData().getGameTime();

		// 友军互斥（最高优先级）：两个都是Mob且互为友军（好感度≥60的共同好友），禁止攻击
		// 即使正在攻击同一个目标，也要立刻取消，避免误伤
		if (target instanceof Mob targetMob && MobMindState.areAllies(self, targetMob)) {
			self.setLastHurtByMob(null);
			ci.cancel();
			return;
		}

		if (self.getTarget() == target) return; // 已在攻击同一个目标，不重复处理

		// 玩家被设为攻击目标：
		if (target instanceof Player player) {
			// 激怒状态：允许攻击（无论是否持武器/盾牌）
			if (MobMindState.isProvokedTowards(self, player.getUUID(), gameTime)) return;
			// 友好/安抚状态：不攻击玩家（无论是否持武器/盾牌）
			if (MobMindState.isFriendlyTo(self, player.getUUID())
					|| MobMindState.isCalmedTowards(self, player.getUUID(), gameTime)) {
				ci.cancel();
				return;
			}
			// 持武器/盾牌的生物在非激怒、非友好状态下：
			// WeaponAttackGoal 不会主动选玩家为目标，但原版 AI（如 NearestAttackablePlayerGoal）
			// 仍然可以让敌对生物攻击玩家。保留原版行为，不拦截。
			// 敌对生物开始攻击玩家时，附近对该玩家友好的生物可能出面阻止
			if (self instanceof Monster && player instanceof ServerPlayer sp) {
				MobAiService.onMobTargetsPlayer(self, sp);
			}
			return;
		}

		// 玩家的好朋友（≥60好感）：不会主动攻击对玩家友好的生物（如善良铁傀儡、村民等）
		// 但对方可以自卫反击（不在hurt handler中取消伤害）
		if (target instanceof Mob targetMob && MobMindState.shouldNotAttack(self, targetMob)) {
			// 检查是否是自卫反击（刚刚被对方打过5秒内），自卫时允许攻击
			LivingEntity lastHurt = self.getLastHurtByMob();
			if (lastHurt != targetMob || self.getLastHurtByMobTimestamp() <= gameTime - 100) {
				self.setLastHurtByMob(null);
				ci.cancel();
				return;
			}
		}

		// 驯服狼攻击生物：目标对主人友好/安抚时不打
		if (self instanceof Wolf wolf && wolf.isTame() && wolf.getOwner() instanceof Player owner
				&& target instanceof Mob targetMob && PersonaRegistry.supports(targetMob)) {
			if (MobMindState.isFriendlyTo(targetMob, owner.getUUID())
					|| MobMindState.isCalmedTowards(targetMob, owner.getUUID(), gameTime)) {
				ci.cancel();
			}
		}

		// 治疗中的僵尸村民：大部分不再攻击救助者，小部分仍敌对
		if (self instanceof ZombieVillager zv && MobMindState.isCuringZombieVillager(zv, gameTime)) {
			UUID healerId = MobMindState.curingHealer(zv);
			if (target instanceof Player player && healerId != null && player.getUUID().equals(healerId)) {
				int loyalty = MobMindState.curingLoyalty(zv);
				if (loyalty >= 0) {
					ci.cancel();
				}
			}
		}
	}

	/** 怪物锁定村民时，村民向高好感玩家求救 */
	@Inject(method = "setTarget", at = @At("HEAD"))
	private void mobmind$onTargetVillager(LivingEntity target, CallbackInfo ci) {
		if (target == null || this.level().isClientSide()) return;
		Mob self = (Mob) (Object) this;
		if (!(self instanceof Monster)) return;
		if (!(target instanceof net.minecraft.world.entity.npc.villager.Villager villager)) return;
		MobAiService.onVillagerChased(villager, self);
	}

	/** 每次 tick 时检查攻击目标：
	 *  1. 清除友军（互为朋友的生物）之间的残留目标。
	 *  2. 如果是友好/安抚状态，但目标是玩家，强制清除目标（避免给武器前已锁定玩家）。
	 *  激怒状态下允许攻击玩家，保留原版 AI 行为。
	 *  同时处理残留发光效果：如果没有活动的 GLOW_UNTIL 条目，确保关闭发光。 */
	@Inject(method = "tick", at = @At("HEAD"))
	private void mobmind$clearInvalidTargets(CallbackInfo ci) {
		if (this.level().isClientSide()) return;
		Mob self = (Mob) (Object) this;

		// 清除残留发光效果（玩家死亡/重生/区块重载后发光未关闭的 bug）
		if (self.isCurrentlyGlowing() && !MobMindState.hasActiveGlow(self)) {
			self.setGlowingTag(false);
		}

		LivingEntity target = self.getTarget();

		// 1. 清除真·友军之间的残留目标（双方≥60好感的共同好友）
		if (target instanceof Mob targetMob && MobMindState.areAllies(self, targetMob)) {
			self.setTarget(null);
			self.setLastHurtByMob(null);
			target = null;
		}

		// 1b. 玩家的好友（≥60）不应该锁定对玩家友好的生物为目标（主动攻击已在setTarget阻止，这里清理残留/意外目标）
		//     但如果是刚被对方打了（5秒内自卫），允许保留目标进行反击
		if (target instanceof Mob targetMob && MobMindState.shouldNotAttack(self, targetMob)) {
			long gameTime = this.level().getLevelData().getGameTime();
			LivingEntity lastHurt = self.getLastHurtByMob();
			if (lastHurt != targetMob || self.getLastHurtByMobTimestamp() <= gameTime - 100) {
				self.setTarget(null);
				self.setLastHurtByMob(null);
				target = null;
			}
		}

		// 2. 持武器/盾牌生物：友好/安抚状态下不攻击玩家
		if (target instanceof Player player
				&& (WeaponAttackGoal.isHoldingMeleeWeapon(self)
					|| WeaponRangedAttackGoal.isHoldingRangedWeapon(self)
					|| ShieldBlockGoal.isHoldingShield(self))) {
			long gameTime = this.level().getLevelData().getGameTime();
			if (!MobMindState.isProvokedTowards(self, player.getUUID(), gameTime)
					&& (MobMindState.isFriendlyTo(self, player.getUUID())
							|| MobMindState.isCalmedTowards(self, player.getUUID(), gameTime))) {
				self.setTarget(null);
			}
		}
	}

	/** 限制物品拾取：只有PersonaRegistry支持的生物才能捡玩家丢的物品，且必须贴着蹭在一起才捡；
	 *  刚给玩家的奖励物品（以物易物/承诺回赠）任何生物都不能捡回去，防止女巫把自己扔给玩家的药水捡回来 */
	@Inject(method = "pickUpItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/item/ItemEntity;)V",
			at = @At("HEAD"), cancellable = true)
	private void mobmind$restrictPickup(net.minecraft.server.level.ServerLevel level,
										ItemEntity itemEntity, CallbackInfo ci) {
		if (this.level().isClientSide()) return;
		Mob self = (Mob) (Object) this;

		// 奖励物品：生物刚扔给玩家的回赠，任何生物都不能捡回去（5秒内）
		long gameTime = this.level().getLevelData().getGameTime();
		if (MobMindState.isRewardItemForbiddenForMobs(itemEntity.getUUID(), gameTime)) {
			ci.cancel();
			return;
		}

		Entity thrower = itemEntity.getOwner();

		// 只有玩家丢出的物品才受限制
		if (thrower instanceof Player player) {
			// 不支持AI的生物（牛猪鸡等）完全不能捡玩家丢的东西
			if (!PersonaRegistry.supports(self)) {
				ci.cancel();
				return;
			}
			// 必须紧贴着蹭在一起才捡：物品距离≤0.8格（几乎在它身上/脚边），玩家距离≤1.2格（贴着它）
			double distToItem = self.distanceTo(itemEntity);
			double distToPlayer = self.distanceTo(player);
			if (distToItem > 0.8 || distToPlayer > 1.2) {
				ci.cancel();
			}
		}
	}

	/** 生物捡起掉落物时，立即尝试完成以物易物约定 */
	@Inject(method = "pickUpItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/item/ItemEntity;)V",
			at = @At("RETURN"))
	private void mobmind$onPickUpItem(net.minecraft.server.level.ServerLevel level,
									  net.minecraft.world.entity.item.ItemEntity itemEntity, CallbackInfo ci) {
		if (this.level().isClientSide()) return;
		BarterActions.onMobPickedUp((Mob) (Object) this, itemEntity);
	}
}
