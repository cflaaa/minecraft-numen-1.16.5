package com.dwinovo.numen.core.llm;

import com.dwinovo.numen.agent.http.HttpLlmTransport;
import com.dwinovo.numen.agent.provider.LlmProvider;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.config.NumenConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * 「获取模型」：拿当前填的端点与密钥，去服务商问一次它有哪些模型。
 *
 * <p>为什么值得做：服务商的模型清单是**一直在变**的（下线旧型号、上新型号），
 * 而 {@code numen_providers.json} 是我们的内置清单，写死了就会过期。
 * 让玩家填完 key 点一下，直接问服务商要一份真实的清单，比维护一份写死的列表准得多——
 * 也省得玩家去文档里抄模型名再回来粘贴。
 *
 * <p>协议：OpenAI 兼容站的 {@code GET {base}/models}，响应形如
 * {@code {"object":"list","data":[{"id":"...","object":"model"}, ...]}}。
 * NVIDIA NIM、OpenAI、DeepSeek、Moonshot、硅基流动、Ollama 等都是这个形状，
 * 实测 NVIDIA 的 {@code https://integrate.api.nvidia.com/v1/models} 直接返回 80 个模型。
 *
 * <p><b>线程</b>：网络请求走 JDK 的异步 HttpClient（不占服务端主线程），
 * 结果用 {@code executor} 回到调用方指定的线程（服务端主线程）再交出去。
 * 这里绝不能自己碰世界状态——只交回一份字符串列表。
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    /**
     * 去问一次模型清单。**不会阻塞调用线程**，结果（或错误）在 {@code executor} 上交回。
     *
     * @param overrideBaseUrl 面板里此刻填的端点（可能还没保存）。空 = 用配置文件里的。
     * @param overrideApiKey  面板里此刻填的密钥（可能还没保存）。空 = 用配置文件里的。
     * @param done            回调：{@code (模型列表, 错误说明)}。成功时错误为 null，
     *                        失败时列表为 null 或空且错误非 null。**两者都在 {@code executor} 上调用。**
     */
    public static void fetch(String overrideBaseUrl, String overrideApiKey,
                             java.util.concurrent.Executor executor,
                             BiConsumer<List<String>, String> done) {
        NumenConfig cfg = NumenConfig.get();

        String providerId = cfg.provider;
        String baseUrl = firstNonBlank(overrideBaseUrl, cfg.baseUrl,
                ProviderRegistry.baseUrl(providerId));
        String apiKey = firstNonBlank(overrideApiKey, cfg.apiKey);
        String proxy = cfg.proxy;

        if (baseUrl == null || baseUrl.isBlank()) {
            executor.execute(() -> done.accept(null, "还没填端点地址"));
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            // 有些本地服务（Ollama / LM Studio）不校验密钥，所以这里只是提醒，不阻止。
            Constants.info("[numen-models] 没有密钥，直接试一次（本地服务通常不需要）");
        }

        LlmProvider provider = providerFor(providerId);
        String url = modelsUrl(baseUrl, provider);
        Map<String, String> headers = ProviderRegistry.headers(providerId);
        Map<String, String> auth = provider.authHeaders(apiKey);

        Constants.info("[numen-models] 拉取模型列表：{}", url);

        // 传输层与聊天用的是同一套（同一个代理/报头口径），
        // 所以「能聊天但拉不到模型」基本不会出现。
        HttpLlmTransport transport = new HttpLlmTransport(proxy, headers, auth);
        CompletableFuture<JsonObject> future = transport.get(url, apiKey);

        future.whenComplete((json, err) -> executor.execute(() -> {
            if (err != null) {
                done.accept(null, describe(err));
                return;
            }
            List<String> ids = parseIds(json);
            if (ids.isEmpty()) {
                done.accept(ids, "服务商回了内容，但里面一个模型都没有（响应可能不是 OpenAI 格式）");
                return;
            }
            Constants.info("[numen-models] 拿到 {} 个模型", ids.size());
            done.accept(ids, null);
        }));
    }

    /**
     * 组装 {@code GET .../models} 的 URL。
     *
     * <p>基址有个坑：玩家可能填 {@code https://integrate.api.nvidia.com}（不带 {@code /v1}）、
     * 也可能填带 {@code /v1} 的完整地址，甚至可能把聊天路径 {@code /chat/completions} 一起粘进来。
     * 三种都认：剥掉聊天路径，再补 {@code /v1}，最后接 {@code /models}。
     */
    static String modelsUrl(String baseUrl, LlmProvider provider) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 粘错了：把聊天路径一起填进来了。剥掉它，否则会拼出 /chat/completions/models。
        String chatPath = provider == null ? "/chat/completions" : provider.chatPath();
        if (chatPath != null && !chatPath.isBlank() && base.endsWith(chatPath)) {
            base = base.substring(0, base.length() - chatPath.length());
        }
        // OpenAI 兼容站的模型清单在 /v1 下。基址没带版本段就补上。
        if (!base.endsWith("/v1") && !base.matches(".*/v\\d+$")) {
            base = base + "/v1";
        }
        return base + "/models";
    }

    /** 从 {@code {"data":[{"id": "..."}, ...]}} 里取模型名。去重、保序。 */
    static List<String> parseIds(JsonObject json) {
        List<String> out = new ArrayList<>();
        if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray data = json.getAsJsonArray("data");
        for (JsonElement e : data) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonObject m = e.getAsJsonObject();
            if (!m.has("id") || m.get("id").isJsonNull()) {
                continue;
            }
            String id;
            try {
                id = m.get("id").getAsString();
            } catch (RuntimeException ex) {
                continue;
            }
            if (id != null && !id.isBlank()) {
                seen.add(id);
            }
        }
        out.addAll(seen);
        return out;
    }

    private static LlmProvider providerFor(String providerId) {
        try {
            return com.dwinovo.numen.agent.llm.NumenLlmClient.pickProvider(providerId);
        } catch (RuntimeException | Error ex) {
            // 拿不到具体方言也不该让整件事失败：缺省按 OpenAI 兼容处理，
            // 它覆盖绝大多数站点，而且我们只是要个 chatPath() 用来剥路径。
            Constants.warn("[numen-models] 认不出服务商 {}（{}），按 OpenAI 兼容处理",
                    providerId, ex.toString());
            return null;
        }
    }

    /** 把异常翻成给玩家看的一句话。**必须是中文且不能是堆栈**——这句话直接显示在面板上。 */
    private static String describe(Throwable err) {
        Throwable t = err;
        while ((t instanceof java.util.concurrent.CompletionException
                || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (t instanceof com.dwinovo.numen.agent.http.LlmHttpException http) {
            int code = http.statusCode();
            if (code == 401 || code == 403) {
                return "密钥不对或被拒绝（HTTP " + code + "）。检查一下 API key。";
            }
            if (code == 404) {
                return "该站点没有 /models 这个接口（HTTP 404）。"
                        + "有些服务商不提供模型列表，那就手工把模型名填进去。";
            }
            return "服务商返回 HTTP " + code + "：" + shorten(msg);
        }
        if (t instanceof java.net.UnknownHostException) {
            return "域名解析不了：" + fallbackIfBlank(msg, t) + "。检查端点地址和网络。";
        }
        if (t instanceof java.net.ConnectException || t instanceof java.net.SocketException) {
            return "连不上：" + fallbackIfBlank(msg, t) + "。检查网络或代理。";
        }
        if (t instanceof java.net.http.HttpTimeoutException
                || t instanceof java.util.concurrent.TimeoutException) {
            return "超时了。检查网络，或在配置里填代理。";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败：" + fallbackIfBlank(msg, t) + "。可能是代理在中间拦了。";
        }
        return shorten(t.getClass().getSimpleName() + ": " + msg);
    }

    /**
     * 有些网络异常（实测 {@code ConnectException}）{@code getMessage()} 是 null，
     * 直接拼出来就是「连不上：null」，看着像我们自己的 bug。给个兜底说法。
     */
    private static String fallbackIfBlank(String msg, Throwable t) {
        if (msg == null || msg.isBlank() || "null".equals(msg)) {
            return "连接被拒绝或超时（" + t.getClass().getSimpleName() + "）";
        }
        return shorten(msg);
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
