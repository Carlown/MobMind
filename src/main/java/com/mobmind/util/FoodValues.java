package com.mobmind.util;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 食物对应的回血数值：基于饥饿值和饱和度简单换算，只覆盖常见食物。
 */
public final class FoodValues {
	private FoodValues() {}

	public static float healFor(Item item) {
		if (item == Items.GOLDEN_APPLE) return 10.0f;
		if (item == Items.ENCHANTED_GOLDEN_APPLE) return 20.0f;
		if (item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP) return 6.0f;
		if (item == Items.COOKED_CHICKEN || item == Items.COOKED_MUTTON || item == Items.COOKED_RABBIT
				|| item == Items.BAKED_POTATO || item == Items.BEETROOT_SOUP || item == Items.MUSHROOM_STEW
				|| item == Items.RABBIT_STEW) return 4.5f;
		if (item == Items.APPLE || item == Items.BREAD || item == Items.COOKED_COD
				|| item == Items.COOKED_SALMON || item == Items.CARROT || item == Items.MELON_SLICE) return 3.0f;
		if (item == Items.BEEF || item == Items.PORKCHOP || item == Items.CHICKEN || item == Items.MUTTON
				|| item == Items.RABBIT || item == Items.COD || item == Items.SALMON || item == Items.POTATO) return 2.0f;
		if (item == Items.COOKIE || item == Items.BEETROOT || item == Items.CHORUS_FRUIT
				|| item == Items.DRIED_KELP || item == Items.SWEET_BERRIES || item == Items.GLOW_BERRIES) return 1.5f;
		if (item == Items.ROTTEN_FLESH || item == Items.SPIDER_EYE || item == Items.POISONOUS_POTATO
				|| item == Items.CHICKEN || item == Items.PUFFERFISH || item == Items.TROPICAL_FISH) return 1.0f;
		return 2.0f; // 其他能吃的食物默认回2点
	}
}
