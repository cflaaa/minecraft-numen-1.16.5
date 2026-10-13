package com.dwinovo.numen.core.config;

import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.core.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置：模型、密钥、端点，以及同伴的主动性档位。
 *
 * <p>对应上游的「设置面板 + 全局设置」那两处（{@code api/.../config}）。1.16.5 这一侧先做最小可用：
 * 一个 {@code config/numen.json}，由 {@code /numen config} 命令读写，客户端面板是后续阶段的事。
 *
 * <p><b>密钥只落在本地这个文件里</b>，与上游口径一致：推理在本机发起，key 只发给玩家自己选的服务商。
 *
 * <p>用 Gson 而不是自己拼 JSON：Minecraft 1.16.5 的类路径上就有 com.google.gson，
 * 引擎层（{@code ai} 模块）也已经在用它，这里保持一致。
 */
public final class NumenConfig {

    /**
     * 默认端点。**默认选 NVIDIA NIM**：它是 OpenAI 兼容的，注册就有免费额度，
     * 端点在国内一般也能直连（实测 200），对第一次上手的人最省事。
     * 想换服务商，在面板设置页改「服务商 / 端点」两项即可，或者点「获取模型」拉一份该站点的模型列表。
     */
    public static final String DEFAULT_OPENAI_BASE = "https://integrate.api.nvidia.com/v1";
    public static final String DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com";

    /** 服务商方言名，取值与 {@code ai} 模块的 ProviderRegistry 对齐。 */
    public String provider = "nvidia";
    public String model = "deepseek-ai/deepseek-v4.1-flash";
    public String apiKey = "";
    public String baseUrl = DEFAULT_OPENAI_BASE;
    /** 需要走代理时填，例如 {@code http://127.0.0.1:7890}；空 = 直连。 */
    public String proxy = "";

    /** 主动性档位 1~10，语义见 {@code agent} 模块的 HostPort#initiativeLevel。 */
    public int initiative = 6;

    /** 人设，进系统提示。 */
    public String persona = "你是一只生活在 Minecraft 世界里的同伴。说话简短、直接，用主人的语言。";

    /** 单次模型调用的超时（秒）。 */
    public int requestTimeoutSeconds = 120;

    // ---- 单例 ----

    private static NumenConfig instance;
    private static Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private NumenConfig() {}

    /** 已加载的配置；没加载过先加载。 */
    public static synchronized NumenConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** 从 {@code config/numen.json} 读；文件不存在或坏了就写一份默认的出去。 */
    public static synchronized void load() {
        file = configDir().resolve("numen.json");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                NumenConfig parsed = GSON.fromJson(reader, NumenConfig.class);
                if (parsed != null) {
                    instance = parsed;
                    instance.clamp();
                    Constants.info("配置已从 {} 读取", file);
                    return;
                }
            } catch (IOException | JsonSyntaxException ex) {
                // 配置坏了不该拦住启动：留一份现场，用默认值继续跑。
                Constants.warn("配置 {} 读不出来（{}），改用默认值", file, ex.toString());
            }
        }
        instance = new NumenConfig();
        save();
    }

    /** 落盘。命令改完配置就调它。 */
    public static synchronized void save() {
        if (instance == null) {
            return;
        }
        Path target = file != null ? file : configDir().resolve("numen.json");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException ex) {
            Constants.error("配置写不出去 {}：{}", target, ex.toString());
        }
    }

    /**
     * 把配置解析成 {@code ai} 模块要的那份「已解析、不可变」的端点参数。
     *
     * <p>上游的 {@code LlmEndpoint} 注释写得很清楚：它只认这个 record，值从哪来（设置面板、
     * 服务商库、命令）它一概不管。这里就是「值从哪来」的那一侧。
     */
    public LlmEndpoint endpoint() {
        return new LlmEndpoint(provider, model, apiKey, baseUrl,
                proxy == null || proxy.isBlank() ? null : proxy,
                null);
    }

    /** 配置齐不齐。不齐的话 {@code /numen` 会告诉玩家缺哪一项，而不是发出去等一个 401。 */
    public String missingReason() {
        if (apiKey == null || apiKey.isBlank()) {
            return "还没填 API key（/numen config apiKey <key>）";
        }
        if (model == null || model.isBlank()) {
            return "还没填模型名（/numen config model <name>）";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return "还没填端点地址（/numen config baseUrl <url>）";
        }
        return null;
    }

    private void clamp() {
        if (initiative < 1) {
            initiative = 1;
        }
        if (initiative > 10) {
            initiative = 10;
        }
        if (requestTimeoutSeconds < 5) {
            requestTimeoutSeconds = 5;
        }
        if (provider == null || provider.isBlank()) {
            provider = "nvidia";
        }
    }

    /**
     * {@code config/} 目录。
     *
     * <p>1.16.5 与 1.20.1 在这一处正好一样：Fabric Loader 提供的 {@code FabricLoader.getInstance()
     * .getConfigDir()} 两代都在。走它比自己拼 {@code gameDir} 稳。
     */
    private static Path configDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
    }
}
