package com.mobmind.behavior;

import com.mobmind.persona.PersonalityGenerator;
import com.mobmind.state.MobMindState;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 将 AI 输出的动作指令应用到生物行为上。
 */
public final class BehaviorActions {
	private BehaviorActions() {}

	public static final String[] VALID_ACTIONS = {"none", "calm", "follow", "stay", "flee", "gift", "attack"};

	public static boolean isValid(String action) {
		for (String a : VALID_ACTIONS) if (a.equals(action)) return true;
		return false;
	}

	/** 应用动作，返回实际生效的动作名 */
	public static String apply(Mob mob, Player player, String action) {
		if (!isValid(action)) action = "none";
		Level level = mob.level();
		long now = level.getLevelData().getGameTime();

		switch (action) {
			case "calm" -> {
				mob.setTarget(null);
				if (mob instanceof NeutralMob neutral) neutral.stopBeingAngry();
				MobMindState.calm(mob, player.getUUID(), now + 12000); // 10分钟
				MobMindState.clearOrder(mob);
			}
			case "follow" -> MobMindState.setOrder(mob, MobMindState.OrderType.FOLLOW, player.getUUID(), now + 6000);
			case "stay" -> MobMindState.setOrder(mob, MobMindState.OrderType.STAY, player.getUUID(), now + 6000);
			case "flee" -> MobMindState.setOrder(mob, MobMindState.OrderType.FLEE, player.getUUID(), now + 1200);
			case "gift" -> {
				// 如果当前有未完成的以物易物约定，不要直接送礼，等玩家交付后再按约定交换
				if (MobMindState.hasActiveBarterDeal(mob, player.getUUID())) {
					return "none";
				}
				ItemStack gift = giftFor(mob);
				ItemEntity drop = new ItemEntity(level, mob.getX(), mob.getY() + 0.5, mob.getZ(), gift);
				level.addFreshEntity(drop);
			}
			case "attack" -> {
				if (mob instanceof Monster || mob instanceof NeutralMob) {
					MobMindState.clearCalm(mob, player.getUUID()); // 翻脸：安抚作废
					MobMindState.provoke(mob, player.getUUID(), now + 6000); // 5分钟激怒，压过好感
					mob.setTarget(player);
				} else {
					return "none"; // 被动生物不会攻击，忽略
				}
			}
			default -> { }
		}
		return action;
	}

	private static ItemStack giftFor(Mob mob) {
		PersonalityGenerator.Category cat = MobMindState.categoryOf(mob);
		return switch (cat) {
			case PASSIVE -> new ItemStack(Items.APPLE);
			case NEUTRAL -> new ItemStack(Items.EMERALD);
			case HOSTILE -> new ItemStack(Items.BONE);
		};
	}
}
