package com.dwinovo.numen.agent.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.Map;

/**
 * gson 2.8.0 兼容层（1.16.5 移植新增）。
 *
 * <p>上游跑在 1.20.1 上，那边的 gson 是 2.10.x；Minecraft 1.16.5 自带的却是
 * <b>2.8.0</b>。引擎源码用到了三处「2.8.0 之后才公开」的 API，本类用 2.8.0 也支持的
 * 写法把它们补回来，调用点的语义一字不改：
 *
 * <ul>
 *   <li>{@code JsonParser.parseString(String)} / {@code parseReader(Reader)}
 *       —— 2.8.6 才加的<b>静态</b>方法。2.8.0 只有实例方法，故这里包一层。</li>
 *   <li>{@code JsonElement.deepCopy()} —— 2.8.2 起才是 public，2.8.0 里是包级私有。
 *       这里按 gson 自身的实现语义重写一份：primitive 不可变直接复用，
 *       array / object 逐层复制。</li>
 * </ul>
 *
 * <p>{@code JsonArray.isEmpty()} / {@code JsonObject.isEmpty()}（2.8.2 新增）不需要在这里补，
 * 调用点直接写 {@code size() == 0} 即可 —— {@code size()} 在 2.8.0 与 2.10.x 上都存在。
 *
 * <p>注意本类只做「补齐」，不改变任何行为：这里的每个方法在 2.8.0 与 2.10.x 上
 * 得到的结果完全一致，因此上游一旦更新，合并这个文件之外的分歧是机械的。
 */
public final class JsonCompat {

    private JsonCompat() {}

    /** 等价于 gson 2.8.6+ 的 {@code JsonParser.parseString(String)}。 */
    public static JsonElement parse(String json) {
        return new JsonParser().parse(json);
    }

    /** 等价于 gson 2.8.6+ 的 {@code JsonParser.parseReader(Reader)}。 */
    public static JsonElement parse(Reader reader) {
        return new JsonParser().parse(reader);
    }

    /** 等价于 gson 2.8.2+ 公开的 {@code JsonElement.deepCopy()}。 */
    public static JsonElement copy(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        // primitive 不可变，直接复用 —— gson 自己的 deepCopy() 也是这么做的。
        if (element.isJsonPrimitive()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                out.add(copy(child));
            }
            return out;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            out.add(entry.getKey(), copy(entry.getValue()));
        }
        return out;
    }

    /** 等价于 gson 2.8.2+ 公开的 {@code JsonObject.deepCopy()}。 */
    public static JsonObject copy(JsonObject object) {
        return (JsonObject) copy((JsonElement) object);
    }

    /** 等价于 gson 2.8.2+ 公开的 {@code JsonArray.deepCopy()}。 */
    public static JsonArray copy(JsonArray array) {
        return (JsonArray) copy((JsonElement) array);
    }
}
