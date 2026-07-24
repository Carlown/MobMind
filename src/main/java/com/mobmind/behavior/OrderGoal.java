package com.mobmind.behavior;

import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * 统一行为指令 Goal：根据 MobMindState 中的指令让生物 跟随/待命/逃离 指定玩家。
 * 以较高优先级注入所有 Mob，激活期间压制常规游荡行为。
 */
public class OrderGoal extends Goal {
	private final Mob mob;

	public OrderGoal(Mob mob) {
		this.mob = mob;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE));
	}

	@Override
	public boolean canUse() {
		return activeOrder() != null;
	}

	@Override
	public boolean canContinueToUse() {
		return activeOrder() != null;
	}

	private MobMindState.Order activeOrder() {
		Level level = mob.level();
		MobMindState.Order order = MobMindState.orderFor(mob, level.getLevelData().getGameTime());
		if (order == null) return null;
		Player player = level.getPlayerByUUID(order.playerId());
		if (player == null || !player.isAlive() || player.distanceTo(mob) > 32) return null;
		return order;
	}

	@Override
	public void tick() {
		Level level = mob.level();
		MobMindState.Order order = MobMindState.orderFor(mob, level.getLevelData().getGameTime());
		if (order == null) return;
		Player player = level.getPlayerByUUID(order.playerId());
		if (player == null) return;

		switch (order.type()) {
			case FOLLOW -> {
				if (mob.distanceTo(player) > 2.5) {
					mob.getNavigation().moveTo(player, 1.25);
				} else {
					mob.getNavigation().stop();
				}
				mob.getLookControl().setLookAt(player, 10.0F, mob.getMaxHeadXRot());
			}
			case STAY -> {
				mob.getNavigation().stop();
				mob.getLookControl().setLookAt(player, 10.0F, mob.getMaxHeadXRot());
			}
			case FLEE -> {
				double dx = mob.getX() - player.getX();
				double dz = mob.getZ() - player.getZ();
				double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
				double tx = mob.getX() + dx / len * 12;
				double tz = mob.getZ() + dz / len * 12;
				mob.getNavigation().moveTo(tx, mob.getY(), tz, 1.4);
			}
		}
	}

	@Override
	public void stop() {
		mob.getNavigation().stop();
	}
}
