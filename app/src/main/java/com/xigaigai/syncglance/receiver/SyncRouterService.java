package com.xigaigai.syncglance.receiver;

/**
 * SDL 的 Router Service。
 *
 * <h3>它是什么</h3>
 * SDL 从 4.4 开始把蓝牙传输拆成两层：
 * <ul>
 *   <li><b>RouterService</b>（这个类）—— 常驻进程，负责监听 RFCOMM 通道、
 *       注册 SDP 记录、让车机发现「这台手机上有 SDL 应用」，
 *       并把同一个传输连接多路复用给多个 SDL 应用。</li>
 *   <li><b>SdlService</b>（我们的前台服务）—— 真正跟车机跑 RAI/Show 的那层，
 *       通过 RouterService 拿到的连接跟车机对话。</li>
 * </ul>
 *
 * <h3>为什么必须自己继承一个空类</h3>
 * SDL 库里的 {@code com.smartdevicelink.transport.SdlRouterService} 是通用实现，
 * 每个集成方必须提供一个自己的子类并在 Manifest 里声明
 * {@code com.smartdevicelink.router.service} 这个 action。
 * 库内部会枚举设备上所有声明了这个 action 的 service 来挑一个跑，
 * 没有声明的话车机侧根本发现不了本应用。
 *
 * <h3>连接时序（官方文档 Establishing a Transport）</h3>
 * 蓝牙 A2DP + HFP 连上之后，车机只有很短的窗口去连 SDL 的 RFCOMM 通道：
 * <ul>
 *   <li>从没见过这台车机 → RouterService 前台保持约 10 秒</li>
 *   <li>见过但没成功连过 → 立即退到后台</li>
 *   <li>成功连过 → 前台保持约 20 秒</li>
 * </ul>
 * 窗口内没连上，RouterService 退到后台等系统回收，而车机不会再回头连 ——
 * 这正是「App 没启动时车机卡在连接中」的机制来源。
 *
 * 这个空类不需要写任何逻辑，所有行为都在父类里。
 */
public class SyncRouterService extends com.smartdevicelink.transport.SdlRouterService {
}
