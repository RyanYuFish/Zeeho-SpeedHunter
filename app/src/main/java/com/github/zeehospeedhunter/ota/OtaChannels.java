package com.github.zeehospeedhunter.ota;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 车机侧 OTA 通道的逆向结论 —— 只读知识表 + 探针开关。
 *
 * <p><b>来源</b>：2026-10-05 对 {@code firmware/spapp-root/lib/libapis.so.1.0.0}
 * （AE5i 非 Pro，Thumb-2 文本 + ARM 态 .plt）的静态逆向，以及当日车边实测互相印证。
 * 详细过程见项目文档 {@code docs/42} 与 {@code docs/45}。</p>
 *
 * <p><b>三条通道的实测定性（2026-10-05 车边）</b>：</p>
 * <table border="1">
 *   <caption>通道实测结果</caption>
 *   <tr><th>通道</th><th>机制</th><th>车边实测</th></tr>
 *   <tr><td>TCP 10950</td><td>{@code apis::socket_l::CmdSocketClient}，10 字节头 + 大端命令码</td>
 *       <td>车机只开 53，<b>10950 从不监听</b> ⇒ 非官方真实路径</td></tr>
 *   <tr><td>BLE b356/b357</td><td>App↔仪表常规信令，帧形如 {@code AB CD|CMD|LEN|…|CF}</td>
 *       <td>抓到 3119 条，<b>无文件传输/OTA 命令</b></td></tr>
 *   <tr><td><b>CFCP</b></td><td>{@code /dev/cfmoto_cfcp} + {@code ioctl}</td>
 *       <td>符号齐全，与成功项目日志的 {@code ioctl(/dev/cfmoto_cfcp)} 完全对上</td></tr>
 * </table>
 *
 * <p><b>红线</b>：本表只提供「读」的能力。任何下发（ioctl / 串口 / BLE 写）都不在此模块内，
 * 必须等车边出现可用 ADB 或 P2P 通道后另行评估。</p>
 */
public final class OtaChannels {

    private OtaChannels() {
    }

    // ==================== CFCP 通道（/dev/cfmoto_cfcp）====================

    /** 车机 CFCP 字符设备路径。{@code libapis} 内 {@code cfcpInit()} 用 {@code open(path, O_RDWR)} 打开。 */
    public static final String CFCP_DEVICE = "/dev/cfmoto_cfcp";

    /** 串口设备（{@code BT936Control::bt_serial_open()}）。成功项目 UI 里说的「串口」就是它。 */
    public static final String SERIAL_DEVICE = "/dev/ttyS4";

    /** {@code bt_serial_open} 的 open flags：{@code 0x902}。 */
    public static final int SERIAL_OPEN_FLAGS = 0x902;

    /**
     * {@code CfcpControl::write} 使用的 ioctl 命令（属性写）。
     * <p>反汇编：{@code movw r1, #0x4512; blx ioctl}（{@code CfcpControl::write @0x2e52d}）。</p>
     */
    public static final int IOCTL_PROP_WRITE = 0x4512;

    /**
     * OTA 触发使用的 ioctl 命令。
     * <p>反汇编：{@code OTAControl::upgradeSystem @0x28405} 内
     * {@code movw r1, #0x4544; blx ioctl}。</p>
     */
    public static final int IOCTL_OTA_TRIGGER = 0x4544;

    /** OTA 帧总长（字节）。{@code upgradeSystem} 里 {@code sub sp,#0x18} + 20 字节缓冲。 */
    public static final int OTA_FRAME_LEN = 20;

    /** OTA 帧尾魔数。{@code strb r3,[r6,#0x11]}，{@code r3 = 0xA5}。 */
    public static final int OTA_FRAME_TAIL = 0xA5;

    /** 校验和写入的帧内偏移。{@code strb r0,[r6,#0x10]}。 */
    public static final int OTA_CHECKSUM_OFFSET = 0x10;

    /** 帧尾所在偏移。 */
    public static final int OTA_TAIL_OFFSET = 0x11;

    /**
     * CFCP 校验和算法（{@code OTAControl::CFCP_Frame_Checksum @0x2388f}
     * 与 {@code Upgrade::CFCP_Frame_Checksum @0x3fc21} 完全同构）。
     *
     * <pre>
     * uint8_t checksum(const uint8_t *p, uint16_t len) {
     *     if (!p) return 0xFF;      // 指针为空 → ~0
     *     uint16_t sum = 0;
     *     for (uint16_t i = 0; i &lt; len; i++) sum = (uint16_t)(sum + p[i]);
     *     return (uint8_t)(~sum);
     * }
     * </pre>
     *
     * <p>即「逐字节累加后按位取反」，8 位回绕。<b>不是</b> CRC，也与 BLE 帧的
     * 校验字节是两套独立算法，不要混用。</p>
     */
    public static int frameChecksum(byte[] data, int offset, int len) {
        if (data == null || offset < 0 || len <= 0) {
            return 0xFF;
        }
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum = (sum + (data[offset + i] & 0xFF)) & 0xFF;
        }
        return (~sum) & 0xFF;
    }

    /** {@code upgradeSystem} 里的静默等待（毫秒）。成功项目日志的「静默 3037 ms」= 两次。 */
    public static final long SILENCE_MS = 3000L;

    // ==================== 落盘路径 ====================

    /** 车机升级包落盘目录（多处字符串实证）。 */
    public static final String USERDATA_DIR = "/media/flash/userdata/";

    /** 我们改动的目标包（{@code docs/30~34} 产出，patched bin MD5 {@code f9f9c03d…}）。 */
    public static final String TARGET_BIN = "GEMINI_PACK.BIN";

    /** 阿里云 SDK 默认落地名。 */
    public static final String UPDATE_ZIP = "update.zip";

    /** 升级结果文件。{@code upgradeSystem} 里第一个 {@code access()} 检查的就是它。 */
    public static final String RESULT_JSON = "result.json";

    /** 升级属性。{@code upgradeSystem} 内 {@code blx property_set} 写入。 */
    public static final String PROP_UPGRADE = "persist.sys.upgrade";

    // ==================== 校验链（成功项目日志 ①~④ 的代码对应）====================

    /**
     * 成功项目日志与 {@code libapis} 符号的逐条对应：
     * <pre>
     * ① 'recv fileName:'          → PlatformControlPrivate::startVerifyChecksum(QString) @0x2f8a4
     * ② 'totalFileSize:N'         → ChecksumVerifyThread::run()          @0x2f999
     * ③ 'start OTA'               → OTAControl::start_upgrade()
     * ④ 'Verifying Checksum is 0' → OTAControl::verifyUpdateFile()       @0x27918
     * </pre>
     * 这四步全部由 {@code startVerifyChecksum(文件名)} 串起来。
     */
    public static final Map<String, String> SUCCESS_LOG_TO_SYMBOL;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("recv fileName:", "PlatformControlPrivate::startVerifyChecksum(QString)");
        m.put("totalFileSize:N", "ChecksumVerifyThread::run()");
        m.put("start OTA", "OTAControl::start_upgrade()");
        m.put("Verifying Checksum is 0", "OTAControl::verifyUpdateFile()");
        SUCCESS_LOG_TO_SYMBOL = Collections.unmodifiableMap(m);
    }

    /**
     * 触发校验的完整调用链（逆 {@code ChecksumVerifyThread::run()} 所得）：
     * <pre>
     * startVerifyChecksum(文件名)
     *   → new ChecksumVerifyThread + QThread::start()
     *   → run(): QString::toLatin1_helper()        // QString → C 字符串
     *           vtable+0x44 → bt_serial_write(const char*)
     *   → bt_serial_write: strstr() 过滤 → write(fd, buf, len) → /dev/ttyS4
     *   → BT936 模组自己读 /media/flash/userdata/GEMINI_PACK.BIN
     *   → 模组回码 −9 ⇒ ChecksumVerified(2) ⇒ 打印 "verifyChecksum SUCCESS"
     * </pre>
     * 成功判据是 {@code cmn r0,#9}（即 vtable 调用返回 {@code -9}）。
     */
    public static final int BT936_VERIFY_OK = -9;

    /** 校验失败时 {@code ChecksumVerified(1)}，并会 {@code fork} 执行 {@code /bin/rm} 删 {@code userdata/*.zip}。 */
    public static final int BT936_VERIFY_FAIL = 1;

    /** 校验成功时 {@code ChecksumVerified(2)}。 */
    public static final int BT936_VERIFY_PASS = 2;

    // ==================== TCP 10950（socket_l）—— 已实测不可用 ====================

    /**
     * socket_l 通道端口，静态确认（{@code libotamodule.so @0x805c movw r1,#0x2ac6}）。
     * <p>⚠️ 2026-10-05 车边实测：{@code 10950/tcp} 连续多次 CLOSED，
     * 邻近端口（10949/10951/8080/8888/18080/5555/4444/5037）与 32 个常用端口全扫，
     * 车机<b>只有 53 开放</b>。该通道在当前固件状态下不可用。</p>
     */
    public static final int SOCKET_L_PORT = 10950;

    /** 服务端实际绑定地址：{@code sin_addr = 0} ⇒ {@code 0.0.0.0}。 */
    public static final String SOCKET_L_BIND = "0.0.0.0";

    /** 客户端路径目标（{@code initClient} 用 {@code inet_pton} 写入 {@code sin_addr}）。 */
    public static final String SOCKET_L_CLIENT_TARGET = "192.168.0.1";

    // ==================== 探针（只读）====================

    /**
     * 是否记录已知的 OTA 关键类被加载（仅日志，不改变行为）。
     * <p>用于确认 App 进程内是否真的存在 OTA 通道代码 —— 之前就是因为
     * 「官方任务单是伪造的」而误判了整条路线。</p>
     */
    public static final boolean PROBE_CLASSES = true;

    /** 已知 OTA / 通道相关类名（加固后仍可能保留，用于 Class.forName 探测）。 */
    public static final String[] PROBE_CLASSES_HINTS = {
            "com.cfmoto.ui.mine.ota.OTAService",
            "com.cfmoto.ui.mine.ota.OTADownloadService",
            "com.cfmoto.ui.mine.ota.OTAActivity",
    };

    /** 便于日志输出的只读摘要。 */
    public static String summary() {
        return "CFCP dev=" + CFCP_DEVICE
                + " ioctl(ota=0x" + Integer.toHexString(IOCTL_OTA_TRIGGER) + ")"
                + " ioctl(prop=0x" + Integer.toHexString(IOCTL_PROP_WRITE) + ")"
                + " frame=" + OTA_FRAME_LEN + "B tail=0x" + Integer.toHexString(OTA_FRAME_TAIL)
                + " serial=" + SERIAL_DEVICE
                + " bin=" + USERDATA_DIR + TARGET_BIN
                + " | socket_l=" + SOCKET_L_PORT + " (实测不监听)"
                + " | verifyOK=" + BT936_VERIFY_OK;
    }
}
