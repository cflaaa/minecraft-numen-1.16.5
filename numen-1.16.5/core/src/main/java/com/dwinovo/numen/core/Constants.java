package com.dwinovo.numen.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity constants for the {@code numen-core} tool pack. The engine
 * ({@code numen-api}) has its own {@code com.dwinovo.numen.Constants}; this is
 * deliberately a separate type in a separate package so the two mods never put
 * the same fully-qualified class on the runtime classpath.
 *
 * <p>本仓额外挂了 info/warn/error 三个便利方法（原 {@code NumenCore} 常量类的那几个）。
 */
public final class Constants {

    public static final String MOD_ID = "numen";
    public static final String MOD_NAME = "Numen";
    public static final Logger LOG = LoggerFactory.getLogger("NumenCore");

    private Constants() {}

    public static void info(String format, Object... args) { LOG.info("[numen] " + format, args); }
    public static void warn(String format, Object... args) { LOG.warn("[numen] " + format, args); }
    public static void error(String format, Object... args) { LOG.error("[numen] " + format, args); }
}
