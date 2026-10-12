package com.dwinovo.numen.core.platform;

import com.dwinovo.numen.core.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 这一侧的 {@link IPlatformHelper}。
 *
 * <p>照上游 {@code api/fabric/platform/FabricPlatformHelper.java} 逐字搬
 * （只换包名）——刀 1b 接线时发现 {@link Services} 的四个实现一个都没搬过来，
 * 模组构造期直接炸「Failed to load service for IPlatformHelper」。
 */
public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public java.nio.file.Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
