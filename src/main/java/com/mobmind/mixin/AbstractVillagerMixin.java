package com.mobmind.mixin;

import com.mobmind.behavior.VillagerTradePricing;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 处理交易界面打开/关闭时的好感度价格调整。
 * Villager 覆写了 setTradingPlayer，其打开时的定价在 VillagerTradeMixin 中处理。
 * 本 Mixin 覆盖 AbstractVillager，主要处理：
 * - 流浪商人打开交易界面（player != null）→ 应用好感度定价
 * - 所有 AbstractVillager 子类关闭交易界面（player == null）→ 还原价格
 */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {

	@Inject(method = "setTradingPlayer", at = @At("HEAD"))
	private void mobmind$onSetTradingPlayer(Player player, CallbackInfo ci) {
		AbstractVillager self = (AbstractVillager) (Object) this;
		if (self.level().isClientSide()) return;
		if (player == null) {
			// 关闭交易界面：还原价格
			VillagerTradePricing.onCloseTrade(self);
		} else if (!(self instanceof Villager)) {
			// 流浪商人打开交易界面：应用好感度定价（Villager 在 VillagerTradeMixin 中处理）
			VillagerTradePricing.onOpenTrade(self, player);
		}
	}
}
