package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 车机 OTA 通道的<b>只读</b>探针。
 *
 * <p>存在的意义：2026-10-05 车边实测推翻了「官方任务单 ⇒ 车机会自己升级」的假设 ——
 * 任务单是本模块注入伪造的，车机侧本来就没有升级任务。因此在拿到车机 ADB 之前，
 * 手机侧唯一能做的就是把「通道到底存不存在、参数是什么」钉死，避免再次凭猜测改方向。</p>
 *
 * <p><b>红线</b>：本类只做三件事 —— 打印知识表、探测类是否存在、读本机串口设备节点是否可见。
 * <b>不</b>打开 CFCP 设备、<b>不</b>发 ioctl、<b>不</b>写串口、<b>不</b>碰 BLE。</p>
 */
public final class OtaChannelProbe {

    private static final String TAG = HookLog.OTA;

    private OtaChannelProbe() {
    }

    /** 安装探针。必须在 {@code HookKit.setClassLoader} 之后调用。 */
    public static void install() {
        dumpKnowledge();
        probeClasses();
        probeSerialNode();
    }

    /** 打印逆向得到的通道知识表（一行，便于 grep）。 */
    private static void dumpKnowledge() {
        try {
            HookLog.log(TAG + " channels | " + OtaChannels.summary());
            HookLog.log(TAG + " channels | verify-log-map " + OtaChannels.SUCCESS_LOG_TO_SYMBOL);
        } catch (Throwable t) {
            HookLog.log(TAG + " channels dump failed (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 探测 OTA 相关类是否真的存在于 App 进程中。
     *
     * <p>意义：如果这些类被重定位或不存在，说明 App 侧根本没有 OTA 通道代码，
     * 任何「等 App 唤醒车机服务」的方案都不成立 —— 这正是 2026-10-05 得到的教训。</p>
     */
    private static void probeClasses() {
        if (!OtaChannels.PROBE_CLASSES) {
            return;
        }
        for (String name : OtaChannels.PROBE_CLASSES_HINTS) {
            try {
                Class<?> c = HookKit.findClassIfExists(name);
                HookLog.log(TAG + " probe class " + name + " → " + (c == null ? "absent" : "present"));
            } catch (Throwable t) {
                HookLog.log(TAG + " probe class " + name + " → error ("
                        + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * 只读检查本机（手机）是否存在同名设备节点。
     *
     * <p>手机不是车机，这里几乎必然为 {@code false}；但它能提前发现「目标其实在手机上」
     * 这类误判 —— 2026-10-05 就因此把手机的 {@code /proc/net/tcp} 当成车机的读过一轮。</p>
     */
    private static void probeSerialNode() {
        for (String path : new String[]{OtaChannels.CFCP_DEVICE, OtaChannels.SERIAL_DEVICE}) {
            try {
                File f = new File(path);
                HookLog.log(TAG + " probe node " + path + " → exists=" + f.exists()
                        + (f.exists() ? " len=" + f.length() : ""));
            } catch (Throwable t) {
                HookLog.log(TAG + " probe node " + path + " → error ("
                        + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * 校验和自测 —— 用来确认本机实现与固件里的 {@code CFCP_Frame_Checksum} 一致。
     *
     * <p>期望值（按固件算法手算）：</p>
     * <pre>
     *   checksum([0x00], 1)              = 0xFF
     *   checksum([0x01, 0x02], 2)       = 0xFC
     *   checksum([0xFF], 1)             = 0x00
     * </pre>
     */
    public static void selfTest() {
        try {
            check("empty", frameChecksum(new byte[0]) == 0xFF);
            check("zero", frameChecksum(new byte[]{0x00}) == 0xFF);
            check("sum-wrap", frameChecksum(new byte[]{(byte) 0xFF}) == 0x00);
            check("two", frameChecksum(new byte[]{0x01, 0x02}) == 0xFC);
        } catch (Throwable t) {
            HookLog.log(TAG + " checksum self-test error (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static int frameChecksum(byte[] data) {
        return OtaChannels.frameChecksum(data, 0, data.length);
    }

    private static void check(String name, boolean ok) {
        HookLog.log(TAG + " checksum self-test " + name + " → " + (ok ? "ok" : "MISMATCH"));
    }

    /** 反射调用示例入口（预留：将来需要在 App 进程内触发某个方法时使用）。 */
    static Object callIfExists(String className, String methodName, Object... args) {
        try {
            Class<?> c = HookKit.findClassIfExists(className);
            if (c == null) {
                return null;
            }
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? Object.class : args[i].getClass();
            }
            Method m = c.getDeclaredMethod(methodName, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
