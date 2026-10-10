package com.dwinovo.numen.core.llm;

import com.dwinovo.numen.core.net.NumenPanelNet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「获取模型」这件事的状态：一次只有一个请求在飞，快照里永远带着最近一次的结果。
 *
 * <p>为什么要有这个类而不是把结果直接塞进某次回包：面板是**轮询刷新的**
 * （每 20 tick 要一次快照），而拉模型可能要好几百毫秒。
 * 如果结果只跟着「发起请求那次」的回包走，那一次回包早就被后面的轮询覆盖掉了，
 * 面板永远看不到结果。所以把状态存在这里，让**每一份快照**都带上它，直到下一次拉取覆盖它。
 *
 * <p>三个状态，都在同一个对象里表达：
 * <ul>
 *   <li>没有 {@code pending} 也没有 {@code list}/{@code error} —— 从没拉过；
 *   <li>{@code pending: true} —— 正在拉，面板显示「获取中…」；
 *   <li>{@code error} 非空，或 {@code list} 非空 —— 上一次的结果。
 * </ul>
 *
 * <p>只在服务端主线程上写（写入方是 {@code ModelCatalog} 的回调，它保证回到主线程），
 * 读在组快照时也在主线程，所以用一个 {@link AtomicReference} 换整体可见性就够了。
 */
public final class ModelCatalogState {

    private ModelCatalogState() {}

    private static final AtomicReference<JsonObject> STATE = new AtomicReference<>();

    /** 标记「开始拉了」。在发起请求前、服务端主线程上调用。 */
    public static void begin() {
        JsonObject o = new JsonObject();
        o.addProperty("pending", true);
        STATE.set(o);
    }

    /** 记录成功结果。 */
    public static void succeeded(List<String> models) {
        JsonObject o = new JsonObject();
        o.addProperty("pending", false);
        JsonArray arr = new JsonArray();
        for (String m : models) {
            arr.add(m);
        }
        o.add("list", arr);
        STATE.set(o);
    }

    /** 记录失败原因（这句话会用中文原样显示在面板上）。 */
    public static void failed(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("pending", false);
        o.addProperty("error", reason == null ? "未知错误" : reason);
        STATE.set(o);
    }

    /**
     * 给快照用的一份拷贝。
     *
     * <p>返回**深拷贝**：面板那一侧会把快照整个替换掉，共享同一个对象的话，
     * 下一次 {@link #begin()} 会把手里的旧对象也改掉（明明拿到了列表，
     * 界面却突然显示「获取中」）。gson 2.8.0 没有 deepCopy，所以手抄一遍。
     */
    public static JsonObject snapshot() {
        JsonObject cur = STATE.get();
        if (cur == null) {
            JsonObject empty = new JsonObject();
            empty.addProperty("pending", false);
            return empty;
        }
        JsonObject copy = new JsonObject();
        copy.addProperty("pending", NumenPanelNet.integer(cur, "pending", 0) != 0);
        if (cur.has("error")) {
            copy.addProperty("error", NumenPanelNet.str(cur, "error", ""));
        }
        if (cur.has("list") && cur.get("list").isJsonArray()) {
            JsonArray src = cur.getAsJsonArray("list");
            JsonArray dst = new JsonArray();
            for (int i = 0; i < src.size(); i++) {
                dst.add(src.get(i).getAsString());
            }
            copy.add("list", dst);
        }
        return copy;
    }
}
