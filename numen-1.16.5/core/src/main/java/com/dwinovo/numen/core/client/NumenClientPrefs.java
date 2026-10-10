package com.dwinovo.numen.core.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 面板自己的客户端偏好——目前只有「深色还是浅色」。
 *
 * <p>落在 {@code config/numen_client.txt}，纯文本一行，不跟服务端那份
 * {@code numen.json} 混在一起：那份是服务端全局配置（里面有 API key），
 * 这份是这台机器的显示偏好，两者生命周期完全不同。
 */
public final class NumenClientPrefs {

    private static Boolean dark;
    private static Path file;

    private NumenClientPrefs() {}

    public static boolean dark() {
        if (dark == null) {
            dark = read();
        }
        return dark;
    }

    public static void toggle() {
        dark = !dark();
        write(dark);
    }

    private static Path file() {
        if (file == null) {
            file = FabricLoader.getInstance().getConfigDir().resolve("numen_client.txt");
        }
        return file;
    }

    private static boolean read() {
        try {
            Path p = file();
            if (Files.isRegularFile(p)) {
                String text = Files.readString(p, StandardCharsets.UTF_8).trim();
                if (text.startsWith("dark=")) {
                    return Boolean.parseBoolean(text.substring(5).trim());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 读不出来就用默认值：一个显示偏好不值得拦住面板打开。
        }
        return true;
    }

    private static void write(boolean value) {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, "dark=" + value + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // 写不出去就算了，这次会话里照样生效。
        }
    }
}
