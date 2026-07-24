package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import com.mobmind.persona.PersonaRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 猪灵相关注入：
 * 1. 完成原版以物易物后触发 AI 对话。
 * 2. 玩家挖金块/开宝箱导致猪灵发怒时触发 AI 对话。
 */
@Mixin(PiglinAi.class)
public class PiglinAiMixin {

	@Inject(
			method = "stopHoldingOffHandItem",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/monster/piglin/PiglinAi;throwItems(Lnet/minecraft/world/entity/monster/piglin/Piglin;Ljava/util/List;)V",
					shift = At.Shift.AFTER
			)
	)
	private static void mobmind$onBarterComplete(ServerLevel level, Piglin body, boolean barteringEnabled, CallbackInfo ci) {
		MobAiService.onPiglinBarterComplete(body);
	}

	@Inject(method = "angerNearbyPiglins", at = @At("TAIL"))
	private static void mobmind$onPiglinAngeredByLooting(ServerLevel level, Player player, boolean onlyIfTheySeeThePlayer, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer sp)) return;
		UUID playerId = player.getUUID();
		List<Piglin> nearby = level.getEntitiesOfClass(Piglin.class, player.getBoundingBox().inflate(16.0));
		for (Piglin piglin : nearby) {
			if (!PersonaRegistry.supports(piglin)) continue;
			Optional<UUID> angryAt = piglin.getBrain().getMemory(MemoryModuleType.ANGRY_AT);
			if (angryAt.isPresent() && angryAt.get().equals(playerId)) {
				MobAiService.onPiglinAngeredByLooting(piglin, sp);
			}
		}
	}
}
