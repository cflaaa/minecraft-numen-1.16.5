package com.dwinovo.numen.core.platform;

import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.platform.services.INumenConfig;

/**
 * Fabric 这一侧的 {@link INumenConfig}：本仓的配置就是 {@link NumenConfig}
 * （{@code config/numen.json}），这里做一层适配，把上游那套服务契约接到它上面。
 *
 * <h2>本仓还没有的几项</h2>
 * <ul>
 *   <li>{@code reasoningEffort}（推理努力档）与 {@code stt*}（语音转文字）本仓未携带——
 *       getter 回空串、setter 空操作，注释在此，别当成「有这个值」；</li>
 *   <li>{@code systemPrompt} 对应本仓的 {@code persona}（人设，进系统提示）——
 *       同一个概念在本仓的名字，直接映射。</li>
 * </ul>
 */
public final class FabricNumenConfig implements INumenConfig {

    /** ServiceLoader 用的无参构造。 */
    public FabricNumenConfig() {}

    private NumenConfig cfg() {
        return NumenConfig.get();
    }

    @Override
    public String getApiKey() {
        return cfg().apiKey;
    }

    @Override
    public String getBaseUrl() {
        return cfg().baseUrl;
    }

    @Override
    public String getModel() {
        return cfg().model;
    }

    @Override
    public String getProvider() {
        return cfg().provider;
    }

    @Override
    public String getProxy() {
        return cfg().proxy;
    }

    @Override
    public String getReasoningEffort() {
        return "";   // 本仓未携带
    }

    @Override
    public String getSystemPrompt() {
        return cfg().persona;
    }

    @Override
    public void setApiKey(String value) {
        cfg().apiKey = value;
        NumenConfig.save();
    }

    @Override
    public void setBaseUrl(String value) {
        cfg().baseUrl = value;
        NumenConfig.save();
    }

    @Override
    public void setModel(String value) {
        cfg().model = value;
        NumenConfig.save();
    }

    @Override
    public void setProvider(String value) {
        cfg().provider = value;
        NumenConfig.save();
    }

    @Override
    public void setProxy(String value) {
        cfg().proxy = value;
        NumenConfig.save();
    }

    @Override
    public void setSystemPrompt(String value) {
        cfg().persona = value;
        NumenConfig.save();
    }

    @Override
    public void setReasoningEffort(String value) {
        // 本仓未携带，空操作
    }

    @Override
    public String getSttProvider() {
        return "";   // 语音未移植
    }

    @Override
    public String getSttApiKey() {
        return "";
    }

    @Override
    public String getSttBaseUrl() {
        return "";
    }

    @Override
    public String getSttModel() {
        return "";
    }

    @Override
    public String getSttMicrophone() {
        return "";
    }

    @Override
    public void setSttProvider(String value) {
        // 语音未移植
    }

    @Override
    public void setSttApiKey(String value) {
    }

    @Override
    public void setSttBaseUrl(String value) {
    }

    @Override
    public void setSttModel(String value) {
    }

    @Override
    public void setSttMicrophone(String value) {
    }

    @Override
    public void save() {
        NumenConfig.save();
    }
}
