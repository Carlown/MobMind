package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 追踪原版猪灵以物易物：玩家扔出金锭被猪灵捡起时记录交易发起人。
 */
@Mixin(Piglin.class)
public class PiglinMixin {

	@Inject(method = "pickUpItem", at = @At("HEAD"))
	private void mobmind$onPickUpGold(ServerLevel level, ItemEntity entity, CallbackInfo ci) {
		if (((Piglin) (Object) this).level().isClientSide()) return;
		if (!entity.getItem().is(Items.GOLD_INGOT)) return;
		Entity thrower = entity.getOwner();
		if (!(thrower instanceof ServerPlayer player)) return;
		MobAiService.onPiglinGoldReceived((Piglin) (Object) this, player);
	}
}
