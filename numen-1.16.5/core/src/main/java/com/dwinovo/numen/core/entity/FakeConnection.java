package com.dwinovo.numen.core.entity;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 同伴假玩家用的「没有网线的」{@link Connection}。
 *
 * <p>移植自上游 {@code api/entity/FakeConnection.java}。整体骨架几乎一行不用改
 * （构造签名、{@code PacketFlow}、{@code EmbeddedChannel} 这一套从 1.16 到 1.20 都没动过），
 * <b>只有出站那一处必须改</b>，见下。
 *
 * <h2>为什么不能直接 new 一个 Connection</h2>
 * 服务端会对着任何「在玩家列表里的玩家」持续推包（位置、区块、实体更新、心跳）。
 * 真连接把这些包写进 netty 通道；同伴对面没有客户端。而裸的 {@code new Connection(...)}
 * <b>不安全</b>：没有挂通道时它的 {@code send} 会把每一个包排进一个无上限的
 * {@code pendingActions} 列表——慢性内存泄漏。所以要继承下来，把出站 I/O 全部丢掉。
 *
 * <p>{@code Connection} 不是 final、构造器是 public，所以这是纯 common 代码：
 * 不需要反射、不需要 access widener、不需要 mixin。
 *
 * <p>有两个细节让 {@code PlayerList.placeNewPlayer} 收下它：
 * <ul>
 *   <li><b>SERVERBOUND</b> 接收方向——服务端的玩家连接收到的是客户端发来的包，
 *       所以它的监听器是 serverbound 的；{@code validateListener} 会拒绝方向不匹配的。</li>
 *   <li>一个真的（内存里的）{@link EmbeddedChannel}——{@code placeNewPlayer -> setupInboundProtocol}
 *       会去配置通道的 pipeline，没有通道就 NPE。把这个 Connection 注册成内存通道的 handler，
 *       {@code channelActive} 就会触发、把 channel 填上。</li>
 * </ul>
 */
public final class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // 把 this（一个 netty 入站 handler）注册成内存通道的 handler，
        // 触发 channelActive -> 填上 this.channel，
        // 于是 placeNewPlayer 里那段 pipeline 配置有通道可用。
        new EmbeddedChannel(this);
    }

    /** 同伴连接对外报的地址。见 {@link #getRemoteAddress}。 */
    private static final InetSocketAddress LOOPBACK =
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    /**
     * 回环地址。<b>不是装饰，是兼容性要求。</b>
     *
     * <p>内存通道的远端地址是 netty 自己的类型，而生态里大量代码把 {@code SocketAddress}
     * <b>裸强转</b>成 {@link InetSocketAddress}（IP 白名单、登录风控、聊天桥接都这么写）。
     * 转失败抛在玩家加入事件里、打断整个登录流程，表现是<b>真玩家也进不去服务器</b>。
     * 这类崩溃只在装了别的模组时才出现，本仓自己怎么跑都测不到。
     *
     * <p>回环是<b>诚实</b>的答案：这具身体确实就在本机，没有远端。
     * 真玩家报的是公网 IP，服主看日志一眼分得开。
     */
    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    /**
     * 出站包全部丢掉——对面没有客户端。
     *
     * <p><b>这一处是 1.16.5 与上游唯一的实质差别。</b>上游覆写的是
     * {@code send(Packet, PacketSendListener)}；而 {@code PacketSendListener} 这个类型
     * 是 1.17 才引入的，1.16.5 上根本不存在。这一代 {@code Connection} 只有两个出站口：
     *
     * <pre>
     *   void send(Packet&lt;?&gt; packet)
     *   void send(Packet&lt;?&gt; packet, GenericFutureListener&lt;? extends Future&lt;? super Void&gt;&gt; listener)
     * </pre>
     *
     * <p>两个都要覆写：只堵一个的话，另一个仍会把包排进那个无上限的
     * {@code pendingActions}，泄漏照旧。
     */
    @Override
    public void send(Packet<?> packet) {
        // 故意空实现：没有通道，也就不会有 pendingActions 增长。
    }

    @Override
    public void send(Packet<?> packet, GenericFutureListener<? extends Future<? super Void>> listener) {
        // 故意空实现：同上。
    }

    /**
     * 报告「连着」。让玩家的 tick 与区块跟踪照真玩家走。
     *
     * <p>安全的前提是：所有会解引用 channel 的路径（{@code send}、{@code tick}、{@code flushChannel}）
     * 都被下面覆写成空实现，null 通道永远不会被碰到。
     */
    @Override
    public boolean isConnected() {
        return true;
    }

    /** 不要驱动包监听器的 tick（心跳就跑在那里）。 */
    @Override
    public void tick() {
        // 故意空实现
    }

    /** 把心跳超时（以及其它任何原因）的断开也中和掉。 */
    @Override
    public void disconnect(Component message) {
        // 故意空实现：同伴由 CompanionManager 遣散，永远不会被网线断开。
    }

    @Override
    public void handleDisconnection() {
        // 故意空实现
    }

    @Override
    public void setReadOnly() {
        // 故意空实现
    }
}
