package com.dwinovo.numen.core.permission;

import com.dwinovo.numen.core.entity.NumenPlayer;

import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.UUID;

/**
 * 权限层的唯一入口。全仓凡会改世界的地方——挖掘落点、放置落点、攻击落点、成本模型、
 * 选目标——只问这里;能不能挖/放/打没有第二个出处。
 *
 * <p>两种问法:{@link #judge(NumenPlayer, Action)} 在主线程对活世界问一个动作;
 * {@link #gateFor} 在主线程取一份快照,交给搜索线程逐格问(见 {@link Gate#judge})。
 * 模型不是权限的执行者:它没有任何写入口,看到的只是普通的工具结果和回执。
 *
 * <p>照上游逐字搬(2026-10-11 归位阶段 1),仅改包名。
 */
public final class Permission {

    private Permission() {}

    /**
     * 主线程:取这只同伴此刻的裁决快照——模式、主人层与出厂层规则、所在维度的放置记录、主人答应下来的任务期授权。
     * 快照不可变,任何线程可读。
     */
    public static Gate gateFor(NumenPlayer companion) {
        // 1.16.5:Entity 的取世界方法是 getLevel()(level() 是 1.17+ 的名字)。
        ServerLevel level = (ServerLevel) companion.getLevel();
        return new Gate(companion.getUUID(), modeOf(companion), ownerRules(companion), RuleSet.factory(),
                PlacedBlocks.of(level), ConsentDesk.of(companion).granted());
    }

    /** 主线程:对活世界裁决一个动作。 */
    public static Verdict judge(NumenPlayer companion, Action action) {
        return gateFor(companion).judgeLive(action, (ServerLevel) companion.getLevel());
    }

    /** 这只同伴的模式;无主人(服务器同伴)走服务器档、默认 {@link Mode#AUTO}。 */
    public static Mode modeOf(NumenPlayer companion) {
        UUID owner = companion.getOwnerUuid();
        if (owner == null) {
            return PermissionStore.ofServer(companion.getServer())
                    .modeOrDefault(companion.getUUID(), Mode.AUTO);
        }
        return PermissionStore.of(companion.getServer(), owner).modeOf(companion.getUUID());
    }

    /** 给这只同伴设模式(面板与命令的落点);无主人时存进服务器档。 */
    public static void setMode(NumenPlayer companion, Mode mode) {
        UUID owner = companion.getOwnerUuid();
        if (owner == null) {
            PermissionStore.ofServer(companion.getServer()).setMode(companion.getUUID(), mode);
            return;
        }
        PermissionStore.of(companion.getServer(), owner).setMode(companion.getUUID(), mode);
    }

    /** 这只同伴的上层规则;无主人时用服务器档的规则层。 */
    private static RuleSet ownerRules(NumenPlayer companion) {
        UUID owner = companion.getOwnerUuid();
        if (owner == null) {
            return PermissionStore.ofServer(companion.getServer()).rules();
        }
        return PermissionStore.of(companion.getServer(), owner).rules();
    }
}
