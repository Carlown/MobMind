package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 末影龙被末地水晶复活时触发 AI 对话。
 */
@Mixin(EnderDragonFight.class)
public class EnderDragonFightMixin {

	@Shadow
	private ServerLevel level;

	@Inject(
			method = "setRespawnStage",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/dimension/end/EnderDragonFight;createNewDragon()Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;",
					shift = At.Shift.AFTER
			)
	)
	private void mobmind$onDragonRespawn(DragonRespawnStage stage, CallbackInfo ci) {
		if (stage != DragonRespawnStage.END) return;
		if (level.getDragons().isEmpty()) return;
		EnderDragon dragon = level.getDragons().get(0);
		MobAiService.onDragonRespawned(dragon);
	}
}
