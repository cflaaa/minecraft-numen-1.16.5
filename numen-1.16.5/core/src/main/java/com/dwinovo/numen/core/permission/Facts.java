package com.dwinovo.numen.core.permission;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;

import java.util.UUID;

/**
 * 信号函数与规则项读世界的口。
 *
 * <p>照上游逐字搬(2026-10-11 归位阶段 1),仅改包名。
 *
 * @param view      方块视图:主线程是活世界(只读已加载),搜索线程是冻结快照
 * @param placed    这一维度的玩家放置记录(任何线程可读)
 * @param live      只在主线程非空——方块实体内容这类活读只从它读;搜索线程拿到 null,
 *                  信号按"不知道"的保守值回答(见各信号的说明)
 * @param actor     要动手的同伴是谁(放置记号认人只看这个);测试可传 null
 */
public record Facts(BlockGetter view, PlacedBlocks placed, ServerLevel live, UUID actor) {
}
