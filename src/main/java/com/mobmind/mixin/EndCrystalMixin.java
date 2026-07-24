package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家破坏末地水晶时通知末影龙，让末影龙发怒/骂人。
 */
@Mixin(EndCrystal.class)
public class EndCrystalMixin {

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void mobmind$onCrystalHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) return;
		if (level.isClientSide()) return;
		if (!(source.getEntity() instanceof ServerPlayer player)) return;
		if (level.getDragons().isEmpty()) return;
		EnderDragon dragon = level.getDragons().get(0);
		MobAiService.onEndCrystalAttacked(dragon, player);
	}
}
