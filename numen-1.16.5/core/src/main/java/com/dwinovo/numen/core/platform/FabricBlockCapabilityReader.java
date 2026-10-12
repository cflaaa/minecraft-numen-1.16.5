package com.dwinovo.numen.core.platform;

import com.dwinovo.numen.core.platform.services.IBlockCapabilityReader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Fabric 这一侧的 {@link IBlockCapabilityReader}。
 *
 * <p>照上游：**先是桩**——Fabric Transfer API（{@code Storage<ItemVariant>} /
 * {@code Storage<FluidVariant>}）与 Team Reborn Energy 那一套还没接，
 * 等服务在 Fabric 上能解析、模组能加载就算数；能力读回来是空的（工具据实报
 * 「不暴露存储」，对一个没实现的加载器这是诚实答案）。读取器在 NeoForge 那侧
 * 开发验证（工业模组生态在那边）。
 */
public final class FabricBlockCapabilityReader implements IBlockCapabilityReader {

    @Override
    public List<String> describe(Level level, BlockPos pos) {
        return List.of();
    }
}
