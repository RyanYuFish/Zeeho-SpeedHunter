package com.github.zeehospeedhunter.ota;

import android.content.Context;

import com.github.zeehospeedhunter.core.HookLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 「最后一公里」的后半段：把 OTA 包送进车机，并触发刷写。
 *
 * <h3>序列来源</h3>
 * <p>逆向自官方诊断 App（ZeeCare，{@code com.zeehoapp.service}）里的
 * {@code com.cfmoto.zeehoota.internal.OTAService#runStandardOTA}，见
 * {@code capture/ota-tool/strace.txt} 与 {@code docs/34}。车机侧 OEM 原 biting 顺序是：</p>
 * <pre>
 *   0) 检查设备连接
 *   1) rm -rf /datacache/ota && mkdir -p /datacache/ota && echo OK
 *   2) push upgrade_json_file.txt  -> /userdata/upgrade_json_file.txt
 *   3) push &lt;包&gt;.zip              -> /datacache/ota/&lt;name&gt;.zip
 *   4) touch /datacache/ota/local_upgrade_flag.txt && echo OK   ← ★ 触发车机本地升级守护
 *   5) echo 1 > /dev/cfmoto_cfcp / setprop persist.sys.upgrade   ← ★ 真正的刷写扳机
 * </pre>
 *
 * <p>第 5 步来自第三方「极核仪表 OTA 工具」的日志（截图 {@code otamax.png}）：
 * 它自己叫 {@code ioctl1(/dev/cfmoto_cfcp)}，并提示
 * 「VerifyChecksum SUCCESS / ioctl1 success 才真正刷成功」，
 * 且「仪表不回校验结果」—— 所以刷写后<b>只能看仪表屏幕 / 串口</b>，ADB 会断。</p>
 *
 * <h3>为什么拆成「推包」和「触发」两步</h3>
 * <p>第 4 步一落地车机就重启升级进程，ADB 连接随之断开。分开做可以在包推完、车机还没动时
 * 复核一遍 {@code /datacache/ota} 的内容，确认没问题再扣扳机。</p>
 */
public final class OtaFlash {

    private static final String TAG = HookLog.OTA + " Flash";

    /** 推包后车机侧工作目录（车机升级守护读这里）。 */
    private static final String OTA_DIR = "/datacache/ota";

    /** 升级描述文件（车机通用，不在 OTA_DIR 下）。 */
    private static final String JSON_REMOTE = "/userdata/upgrade_json_file.txt";

    private OtaFlash() {
    }

    public interface Progress {
        void onLine(String line);
    }

    // ==================== 阶段 0：本地准备 ====================

    /** 模块自己的工作目录（本进程一定可写）。 */
    public static File workDir(Context ctx) {
        File dir = new File(ctx.getApplicationInfo().dataDir, "files/ota");
        dir.mkdirs();
        return dir;
    }

    /** 从 {@link OtaOptions#SERVE_FILES} 里挑第一个存在的包，拷到模块目录并返回。 */
    public static File stageZip(Context ctx, Progress p) {
        File dir = workDir(ctx);
        for (String src : OtaOptions.SERVE_FILES) {
            File f = new File(src);
            if (!f.isFile()) continue;
            try {
                File dst = new File(dir, f.getName());
                if (f.length() != dst.length() || !dst.exists()) {
                    copy(f, dst);
                }
                p.onLine("本地包 " + f.getAbsolutePath() + " -> " + dst
                        + "  " + f.length() + "B  md5=" + md5(f));
                return dst;
            } catch (Throwable t) {
                p.onLine("拷 " + src + " 失败: " + t);
            }
        }
        p.onLine("✖ 本地找不到 OTA 包（试过 " + OtaOptions.SERVE_FILES.length + " 个路径）");
        return null;
    }

    /**
     * 生成 {@code upgrade_json_file.txt} 内容。
     *
     * <p>字段逐条照抄 {@code UpgradeJsonGenerator#generateUpgradeJson}，
     * 但 {@code sign_code} / {@code file_size} 用<b>我们自己这个包</b>的真实值 ——
     * 抓包里那两个是硬编码常量（{@code 24f9f897…} / {@code 93026730}），
     * 对应的是官方的另一版包，照抄只会让车机对不上。</p>
     */
    public static String buildUpgradeJson(File zip, String md5) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "123");
        json.put("code", "");
        json.put("task_id", "");
        json.put("sign_method", "md5");
        json.put("VehSOC", "1");                 // 升级前置位：电量
        json.put("VehSpd", "0");                 // 升级前置位：车速
        json.put("CheckKl15", "0");              // 钥匙电（0 = 允许）
        json.put("CheckCharge", "0");            // 充电中（0 = 允许）
        json.put("CheckBMS", "0");
        json.put("VehGear", "0");                // 挡位
        json.put("precondition", "0");
        json.put("forced_upgrade_flg", "0");
        json.put("module", "DASH");
        json.put("single_task_id", "");
        json.put("sign_code", md5);
        json.put("update_url", "file://" + OTA_DIR + "/" + zip.getName());
        json.put("version", OtaOptions.PUSH_VERSION);
        json.put("retry_count", "0");
        json.put("rollback_config", "");
        json.put("rollback_url", "");
        json.put("is_diff", "0");
        json.put("sort", "0");
        json.put("file_size", String.valueOf(zip.length()));
        json.put("upgrade_content", "车辆仪表升级");
        json.put("flashing_type", "local");
        json.put("upgrade_minutes", "15");
        json.put("upgrade_type", "local");
        json.put("key1", "");
        json.put("value1", "");
        json.put("key2", "");
        json.put("value2", "");
        json.put("_package_udi", "{\"ota_notice\":\"\"}");
        json.put("ext_data", "");
        json.put("total_upgrade_minutes", "15");
        json.put("data", "");

        JSONArray files = new JSONArray();
        JSONObject one = new JSONObject();
        one.put("fileName", zip.getName());
        one.put("fileSize", String.valueOf(zip.length()));
        one.put("fileMd5", md5);
        one.put("filePath", OTA_DIR + "/" + zip.getName());
        files.put(one);
        json.put("files", files);
        return json.toString();
    }

    // ==================== 阶段 1：推包 ====================

    /**
     * 推包 + 落标志位（不触发刷写）。
     *
     * @return true 表示三步都跑完且没有断连
     */
    public static boolean push(Context ctx, String host, Progress p) {
        return push(ctx, host, 5555, p);
    }

    public static boolean push(Context ctx, String host, int port, Progress p) {
        AdbPush adb = connect(host, port, p);
        if (adb == null) return false;
        boolean ok = false;
        try {
            File zip = stageZip(ctx, p);
            if (zip == null) return false;

            File dir = workDir(ctx);
            File json = new File(dir, "upgrade_json_file.txt");
            String md5 = md5(zip);
            String text = buildUpgradeJson(zip, md5);
            writeText(json, text);
            p.onLine("JSON " + json.length() + "B  sign_code=" + md5);

            p.onLine("[1/4] " + adb.shell("rm -rf " + OTA_DIR
                    + " && mkdir -p " + OTA_DIR + " && echo OK"));
            p.onLine("[2/4] push -> " + JSON_REMOTE);
            long n1 = adb.push(json, JSON_REMOTE);
            p.onLine("       wrote " + n1 + "B");
            p.onLine("[3/4] push -> " + OTA_DIR + "/" + zip.getName());
            long n2 = adb.push(zip, OTA_DIR + "/" + zip.getName());
            p.onLine("       wrote " + n2 + "B / " + zip.length() + "B");

            if (n1 < 0 || n2 < zip.length()) {
                p.onLine("✖ 推包不完整，放弃触发（差异 n1=" + n1 + " n2=" + n2
                        + " size=" + zip.length() + "）");
                return false;
            }

            p.onLine("[4/4] " + adb.shell("touch " + OTA_DIR
                    + "/local_upgrade_flag.txt && echo OK"));
            String ls = adb.shell("ls -l " + OTA_DIR + "; md5sum " + OTA_DIR + "/" + zip.getName()
                    + " " + JSON_REMOTE);
            p.onLine("车机侧: " + ls);
            ok = true;
        } catch (Throwable t) {
            p.onLine("推包异常: " + t);
            HookLog.log(TAG + " push exception " + t);
        } finally {
            adb.close();
        }
        return ok;
    }

    // ==================== 阶段 2：触发刷写 ====================

    /** 扣扳机。返回各条命令的输出拼在一起。 */
    public static String trigger(String host, Progress p) {
        StringBuilder sb = new StringBuilder();
        for (String cmd : OtaOptions.TRIGGER_COMMANDS) {
            p.onLine("$ " + cmd);
            AdbPush adb = connect(host, 5555, p);
            if (adb == null) return sb.toString();
            try {
                sb.append(adb.shell(cmd)).append('\n');
            } catch (Throwable t) {
                sb.append("(fail ").append(t).append(')').append('\n');
            } finally {
                adb.close();
            }
        }
        p.onLine("⚠ 刷写已发起：车机会重启，ADB 会断。请盯仪表屏幕或串口看 "
                + "VerifyChecksum SUCCESS / ioctl1 success");
        return sb.toString();
    }

    /** 读车机侧升级日志（验证用，刷写过程中会失败属正常）。 */
    public static String readVehicleLog(String host, Progress p) {
        AdbPush adb = connect(host, 5555, p);
        if (adb == null) return "";
        try {
            return adb.shell("tail -n 400 /log/messages 2>/dev/null | "
                    + "grep -i -E 'upgrade|checksum|ioctl|isp' | tail -n 40");
        } catch (Throwable t) {
            return "(read log fail " + t + ")";
        } finally {
            adb.close();
        }
    }

    // ==================== 工具 ====================

    private static AdbPush connect(String host, int port, Progress p) {
        p.onLine("ADB connect " + host + ":" + port + " …");
        AdbPush adb = AdbPush.connect(host, port, 15000);
        if (adb == null) {
            p.onLine("✖ ADB 连不上 " + host + ":" + port
                    + "（P2P 没通？或车机还没起 adbd）");
            return null;
        }
        p.onLine("✔ ADB 连上 " + host + ":" + port);
        return adb;
    }

    public static String md5(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "md5fail:" + t;
        }
    }

    private static void writeText(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst, false);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            out.close();
            in.close();
        }
    }

    /** 体积可读化（日志用）。 */
    public static String size(long n) {
        return new DecimalFormat("0.00").format(n / (1024.0 * 1024.0)) + " MB";
    }

    /** 本地工作目录里已经备好的文件列表（界面展示用）。 */
    public static List<String> staged(Context ctx) {
        File dir = workDir(ctx);
        String[] names = dir.list();
        List<String> out = new ArrayList<String>();
        if (names == null) return out;
        for (String n : names) {
            out.add(n + "  " + new File(dir, n).length() + "B");
        }
        return out;
    }
}
