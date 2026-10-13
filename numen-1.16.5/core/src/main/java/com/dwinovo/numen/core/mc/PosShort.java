package com.dwinovo.numen.core.mc;

import net.minecraft.core.BlockPos;

/**
 * 坐标写成一行的几个字（{@code x,y,z}）。
 *
 * <p>为什么不用 {@code BlockPos.toShortString()}：它只在 **Loom 编译用的「客户端+服务端」合并 jar** 里，
 * 而**专用服务端 jar 经过 ProGuard 裁剪后没有这个方法**（那是客户端调试输出的路）。
 * 照它写就是「编译过、一跑就 NoSuchMethodError」——本项目已经栽过好几次同一类坑。
 */
public final class PosShort {

    private PosShort() {}

    /** {@code 123,64,-35}。 */
    public static String of(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
