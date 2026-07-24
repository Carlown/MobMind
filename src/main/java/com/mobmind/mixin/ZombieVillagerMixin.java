package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ZombieVillager.class)
public abstract class ZombieVillagerMixin {

	private boolean mobmind$wasConverting;

	/** 玩家救治僵尸村民时触发 AI 反应：在 mobInteract 前后记录是否开始转化 */
	@Inject(method = "mobInteract", at = @At("HEAD"))
	private void mobmind$beforeInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		ZombieVillager self = (ZombieVillager) (Object) this;
		this.mobmind$wasConverting = self.isConverting();
	}

	@Inject(method = "mobInteract", at = @At("RETURN"))
	private void mobmind$afterInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		ZombieVillager self = (ZombieVillager) (Object) this;
		if (player.level().isClientSide()) return;
		if (this.mobmind$wasConverting) return;
		if (!self.isConverting()) return;
		if (player instanceof ServerPlayer healer) {
			MobAiService.onZombieVillagerCureStarted(self, healer);
		}
	}
}
