package com.mobmind.mixin;

import com.mobmind.behavior.VillagerTradePricing;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 村民交易界面好感度定价 Mixin（针对 Villager 类）。
 * Villager 覆写了 setTradingPlayer，需单独注入。
 * 流浪商人的定价在 AbstractVillagerMixin 中处理（WT 不覆写该方法）。
 */
@Mixin(Villager.class)
public abstract class VillagerTradeMixin {

	@Inject(method = "setTradingPlayer", at = @At("HEAD"))
	private void mobmind$onSetTradingPlayer(Player player, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		if (self.level().isClientSide()) return;
		if (player != null) {
			VillagerTradePricing.onOpenTrade(self, player);
		}
	}
}
