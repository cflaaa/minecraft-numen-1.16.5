package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * 让离线（非正版）账号也能进多人游戏。
 *
 * <h2>为什么 1.16.5 的离线账号会被禁掉多人游戏</h2>
 * 这条链子是从字节码里读出来的，不是猜的：
 * <ol>
 *   <li>{@code Minecraft} 构造时 {@code multiplayerEnabled = !demo}，
 *       并调用 {@code createSocialInteractionsService(accessToken)}；</li>
 *   <li>该方法内部走 authlib 的 {@code YggdrasilSocialInteractionsService}，
 *       构造时 {@code checkPrivileges()} 会去请求
 *       {@code https://api.minecraftservices.com/privileges}
 *       （头里带 {@code Authorization: Bearer <accessToken>}）；</li>
 *   <li>离线启动器给的假令牌换回来的是 <b>HTTP 401</b>，响应体是
 *       {@code {"path":"/privileges"}} —— 注意里面<b>没有 error 字段</b>；</li>
 *   <li>authlib 的 {@code HttpAuthenticationService.performGetRequest} 对非 2xx
 *       会把 <b>错误流当成正常响应</b>读出来返回，并不抛异常。
 *       于是 {@code makeRequest} 里 {@code response.getError()} 是空的，
 *       不会抛 {@code AuthenticationException}，这个响应被当成"正常结果"；</li>
 *   <li>{@code PrivilegesResponse} 的字段是<b>预初始化</b>的
 *       （{@code privileges = new Privileges()}，每个 {@code Privilege.enabled}
 *       默认 {@code false}）。JSON 里没有 {@code privileges} 键，Gson 就保持默认值
 *       → {@code multiplayerServer.enabled = false}；</li>
 *   <li>回到 {@code Minecraft.allowsMultiplayer()}：
 *       {@code return multiplayerEnabled && socialInteractionsService.serversAllowed();}
 *       → {@code true && false} = <b>false</b> → 主菜单「多人游戏」变灰。</li>
 * </ol>
 * 所以这不是"离线模式不支持多人游戏"，而是<b>令牌校验失败时默认判否</b>。
 *
 * <p>唯一的兜底 {@code OfflineSocialInteractions}（三个权限全返回 true）只在
 * {@code createSocialInteractionsService} <b>抛 {@code AuthenticationException}</b>
 * 时才会用到 —— 也就是"连不上认证服务器"（断网启动）那条路。
 * 这就是社区里"断网启动就能联机"的原因，也是本项目实测过的两条分支：
 * <pre>
 *   联网 + 假令牌  → serversAllowed=false   → 按钮变灰
 *   连不上服务端    → AuthenticationUnavailableException → 回退全 true
 * </pre>
 *
 * <h2>这个类做什么</h2>
 * 不重写认证流程，只在 {@code Minecraft.allowsMultiplayer()} / {@code allowsChat()}
 * 返回 false 时把它掰回 true —— 与社区修复 mod
 * （offline-multiplayer-fabric / MultiOfflineFix）同一手法。
 * 正版账号本来返回 true，不受影响；只有"令牌校验失败被默认判否"这一种情况会走到这里。
 *
 * <p>不想要这个行为可以加 JVM 参数 {@code -Dnumen.offlineMultiplayer=false} 关掉。
 */
public final class NumenOfflineMultiplayer {

    /** 关掉开关用的系统属性名。 */
    public static final String PROPERTY = "numen.offlineMultiplayer";

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty(PROPERTY, "true"));

    private static boolean logged;

    private NumenOfflineMultiplayer() {}

    /**
     * @param what 被禁用的是什么（「多人游戏」/「在线聊天」），只用于日志
     * @return true 表示应当放行
     */
    public static boolean rescue(String what) {
        if (!ENABLED) {
            return false;
        }
        if (!logged) {
            logged = true;
            String who = describeUser();
            Constants.info("离线会话：{}原本被禁用，已放行（账户 {}）",
                    what, who == null ? "未知" : who);
            Constants.info("要关掉这个行为，加 JVM 参数 -D{}=false", PROPERTY);
        }
        return true;
    }

    /** 拿不到账户信息时返回 null。 */
    private static String describeUser() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return null;
            }
            User user = mc.getUser();
            if (user == null) {
                return null;
            }
            // 注意：1.16.5 里 User 没有 getType()。
            // 官方映射 client.txt 上写着 getType() -> f，但真实 jar 里的 dkm(User)
            // 只有 a()~e() 五个方法，全 jar 也只有它自己引用 dkm$a(User$Type)。
            // 也就是说这个成员在混淆时被裁掉了 —— 映射文件描述的是源码级 API，
            // 不等于发布 jar 里真实存在的成员。所以这里只用 getName()/getAccessToken()。
            String token = user.getAccessToken();
            return user.getName() + "（令牌长度 " + (token == null ? 0 : token.length()) + "）";
        } catch (Throwable ignored) {
            return null;
        }
    }
}
