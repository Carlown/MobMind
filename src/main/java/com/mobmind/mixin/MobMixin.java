package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.OrderGoal;
import com.mobmind.persona.PersonaRegistry;
import com.mobmind.state.MobMindState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

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

	/** 为所有生物注入 MobMind 行为指令 Goal */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void mobmind$injectGoals(EntityType<?> entityType, Level level, CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		this.goalSelector.addGoal(2, new OrderGoal(self));
	}

	/** 高好感度或被安抚的玩家不会被主动设为攻击目标；但被激怒后照打不误。
	 *  驯服的狼也不会攻击对主人友好/安抚的生物（如友好骷髅）。 */
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void mobmind$preventTarget(LivingEntity target, CallbackInfo ci) {
		if (target == null || this.level().isClientSide()) return;
		Mob self = (Mob) (Object) this;
		if (self.getTarget() == target) return; // 已在攻击，不清除（清除走 calm）
		long gameTime = this.level().getLevelData().getGameTime();

		// 玩家被设为攻击目标：友好/安抚状态可豁免
		if (target instanceof Player player) {
			if (MobMindState.isProvokedTowards(self, player.getUUID(), gameTime)) return; // 激怒状态压过一切
			if (MobMindState.isFriendlyTo(self, player.getUUID())
					|| MobMindState.isCalmedTowards(self, player.getUUID(), gameTime)) {
				ci.cancel();
				return;
			}
			// 敌对生物开始攻击玩家时，附近对该玩家友好的生物可能出面阻止
			if (self instanceof Monster && player instanceof ServerPlayer sp) {
				MobAiService.onMobTargetsPlayer(self, sp);
			}
			return;
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

	/** 生物捡起掉落物时，立即尝试完成以物易物约定 */
	@Inject(method = "pickUpItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/item/ItemEntity;)V",
			at = @At("RETURN"))
	private void mobmind$onPickUpItem(net.minecraft.server.level.ServerLevel level,
									  net.minecraft.world.entity.item.ItemEntity itemEntity, CallbackInfo ci) {
		if (this.level().isClientSide()) return;
		BarterActions.onMobPickedUp((Mob) (Object) this, itemEntity);
	}
}
