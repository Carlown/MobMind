package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import com.mobmind.behavior.BarterActions;
import com.mobmind.behavior.OrderGoal;
import com.mobmind.state.MobMindState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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

	/** 高好感度或被安抚的玩家不会被主动设为攻击目标；但被激怒后照打不误 */
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void mobmind$preventTarget(LivingEntity target, CallbackInfo ci) {
		if (target == null || this.level().isClientSide()) return;
		if (!(target instanceof Player player)) return;
		Mob self = (Mob) (Object) this;
		if (self.getTarget() == target) return; // 已在攻击，不清除（清除走 calm）
		long gameTime = this.level().getLevelData().getGameTime();
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
