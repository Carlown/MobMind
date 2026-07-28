package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 末影人被箭射中后触发 AI 嘲讽台词。
 * 原版末影人被箭击中会瞬移躲避，此时末影人会用诡异的声音嘲讽射箭的玩家。
 */
@Mixin(EnderMan.class)
public abstract class EndermanMixin {

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void mobmind$onEndermanHitByArrow(net.minecraft.server.level.ServerLevel level,
											  DamageSource source, float amount,
											  CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) return; // 未造成伤害，忽略
		EnderMan enderman = (EnderMan) (Object) this;
		if (enderman.level().isClientSide()) return;
		// 检测伤害来源是否是箭（末影人被箭射中会瞬移）
		if (!(source.getDirectEntity() instanceof AbstractArrow arrow)) return;
		// 箭的发射者是玩家
		if (!(arrow.getOwner() instanceof ServerPlayer archer)) return;
		// 触发嘲讽（有冷却时间防刷屏）
		MobAiService.onEndermanHitByArrowTeleport(enderman, archer);
	}
}
