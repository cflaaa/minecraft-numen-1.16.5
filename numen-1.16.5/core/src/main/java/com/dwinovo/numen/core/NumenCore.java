package com.dwinovo.numen.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组的常量与日志。
 *
 * <p>对应上游 {@code core/Constants.java}。上游那份里还挂着「平台层」的引用
 * （{@code NumenPlatform}、{@code CompanionRegistry} 之类），移植时把那些一并砍掉了：
 * 1.16.5 这一侧还没有平台抽象，所有 Minecraft 适配都直接写在 core 里。
 */
public final class NumenCore {

    public static final String MOD_ID = "numen";
    public static final Logger LOG = LoggerFactory.getLogger("Numen");

    private NumenCore() {}

    /** 带模组前缀的日志，省得每次手写。 */
    public static void info(String format, Object... args) {
        LOG.info("[numen] " + format, args);
    }

    public static void warn(String format, Object... args) {
        LOG.warn("[numen] " + format, args);
    }

    public static void error(String format, Object... args) {
        LOG.error("[numen] " + format, args);
    }
}
