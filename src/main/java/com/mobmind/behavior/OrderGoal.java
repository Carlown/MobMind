package com.mobmind.behavior;

import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 统一行为指令 Goal：根据 MobMindState 中的指令让生物 跟随/待命/逃离 指定玩家。
 * 以较高优先级注入所有 Mob，激活期间压制常规游荡行为。
 */
public class OrderGoal extends Goal {
	private static final double FLEE_SPEED = 2.2;

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
		MobMindState.Order order = MobMindState.orderFor(mob, level.getGameTime());
		if (order == null) return null;
		// FLEE 可以不带玩家（如逃离 TNT），其它指令需要玩家在线
		if (order.type() != MobMindState.OrderType.FLEE || order.playerId() != null) {
			Player player = level.getPlayerByUUID(order.playerId());
			if (player == null || !player.isAlive() || player.distanceTo(mob) > 32) return null;
		}
		return order;
	}

	@Override
	public void tick() {
		Level level = mob.level();
		MobMindState.Order order = MobMindState.orderFor(mob, level.getLevelData().getGameTime());
		if (order == null) return;

		Player player = null;
		if (order.playerId() != null) {
			player = level.getPlayerByUUID(order.playerId());
			if (player == null) return;
		}

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
				Vec3 from = order.fleeFrom() != null ? order.fleeFrom()
						: (player != null ? player.position() : mob.position());
				Vec3 target = null;
				if (mob instanceof PathfinderMob pathfinder) {
					target = DefaultRandomPos.getPosAway(pathfinder, 24, 7, from);
				}
				if (target == null) {
					double dx = mob.getX() - from.x;
					double dz = mob.getZ() - from.z;
					double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
					target = new Vec3(mob.getX() + dx / len * 18, mob.getY(), mob.getZ() + dz / len * 18);
				}
				mob.getNavigation().moveTo(target.x, target.y, target.z, FLEE_SPEED);
			}
		}
	}

	@Override
	public void stop() {
		mob.getNavigation().stop();
	}
}
