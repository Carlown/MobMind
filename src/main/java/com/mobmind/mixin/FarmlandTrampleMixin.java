package com.mobmind.mixin;

import com.mobmind.ai.MobAiService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 监听农田被踩踏：玩家跳跃踩坏农田时触发附近农民反应。
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandTrampleMixin {

	@Inject(method = "turnToDirt", at = @At("HEAD"))
	private static void mobmind$onFarmlandTrampled(Entity entity, BlockState state, Level level, BlockPos pos,
												   CallbackInfo ci) {
		if (level.isClientSide()) return;
		if (!(entity instanceof ServerPlayer player)) return;
		if (!(level instanceof ServerLevel sl)) return;
		// 检查上方是否有作物被踩坏
		BlockPos cropPos = pos.above();
		BlockState cropState = sl.getBlockState(cropPos);
		boolean hasCrop = cropState.getBlock() instanceof net.minecraft.world.level.block.CropBlock;
		MobAiService.onFarmlandTrampled(player, sl, pos, hasCrop);
	}
}
