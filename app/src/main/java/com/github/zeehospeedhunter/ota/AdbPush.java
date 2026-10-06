package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookLog;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * ★ 极简 ADB 客户端（手机里没有 {@code adb} 二进制，只能自己实现协议）。
 *
 * <p>逆向来源：ZeeCare（{@code com.zeehoapp.service}）的
 * {@code com.cfmoto.zeehoota.ADBClient} —— 它连 {@code 192.168.49.1:5555}，
 * 发 {@code host::} 握手，然后 push 三个文件 + touch 标志位。
 * 见 {@code capture/ota-tool/strace.txt} 与 {@code docs/34}。</p>
 *
 * <h3>协议要点</h3>
 * <pre>
 *   报文头 24B（全部 little-endian u32）：
 *     cmd  arg0  arg1  dataLength  dataCrc32  magic
 *   magic = cmd ^ 0xFFFFFFFF ；dataCrc32 = payload 的标准 CRC32
 *   握手： → CNXN(0x01000000, maxdata, "host::\0")
 *          ← CNXN(版本, 设备maxdata, "device::...")   ← 若回 AUTH 则设备要求鉴权
 *   开流： → OPEN(localId, 0, "shell:xxx\0" | "exec:xxx\0" | "sync:\0")
 *          ← OKAY(arg0=设备侧id, arg1=我们的localId)
 *   写stdin：→ WRTE(ourId, devId, chunk)  ← OKAY（流控）
 *   关流： → CLSE
 * </pre>
 *
 * <h3>★ 为什么不用 {@code sync:} 推文件</h3>
 * <p>{@code sync:} 的子报文格式在新版 adbd 里改过（8B 头 {@code [id][len]} 到底要不要
 * 再包一层 WRTE 存在版本歧义），手搓容易踩坑。这里改用
 * {@code exec:cat > /path} + WRTE 写 stdin —— 语义明确、只有一种实现，
 * 等价于 {@code adb shell "cat > f" < local}。</p>
 */
public final class AdbPush {

    private static final String TAG = HookLog.OTA + " AdbPush";

    // ADB 命令字（ASCII 小端读成 int）
    private static final int CNXN = 0x4e584e43; // 'CNXN'
    private static final int OPEN = 0x4e45504f; // 'OPEN'
    private static final int OKAY = 0x59414b4f; // 'OKAY'
    private static final int CLSE = 0x45534c43; // 'CLSE'
    private static final int WRTE = 0x45545257; // 'WRTE'
    private static final int AUTH = 0x48545541; // 'AUTH'
    private static final int FAIL = 0x4c494146; // 'FAIL'

    private static final int ADB_VERSION = 0x01000000;
    private static final int CHUNK = 256 * 1024;

    private Socket socket;
    private OutputStream out;
    private DataInputStream in;
    private int maxData = 4096;
    private int nextLocalId = 1;
    private String systemBanner = "";

    private AdbPush() {
    }

    /** 连上 adbd 并完成握手。返回 null 表示失败（原因已记日志）。 */
    public static AdbPush connect(String host, int port, int timeoutMs) {
        AdbPush c = new AdbPush();
        try {
            c.socket = new Socket();
            c.socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            c.socket.setSoTimeout(timeoutMs);
            c.socket.setTcpNoDelay(true);
            c.out = c.socket.getOutputStream();
            c.in = new DataInputStream(c.socket.getInputStream());
        } catch (IOException e) {
            HookLog.log(TAG + " connect " + host + ":" + port + " fail: " + e);
            return null;
        }

        try {
            c.send(CNXN, ADB_VERSION, 4096, "host::".getBytes("UTF-8"));
            Msg m = c.read();
            if (m == null) {
                HookLog.log(TAG + " no CNXN reply");
                c.close();
                return null;
            }
            if (m.cmd == AUTH) {
                HookLog.log(TAG + " ✖ 设备要求 AUDH 鉴权，本实现不支持");
                c.close();
                return null;
            }
            if (m.cmd != CNXN) {
                HookLog.log(TAG + " unexpected reply cmd=0x" + Integer.toHexString(m.cmd));
                c.close();
                return null;
            }
            c.maxData = m.arg1 > 0 ? m.arg1 : 4096;
            c.systemBanner = new String(m.payload, "UTF-8");
            HookLog.log(TAG + " ✔ CNXN ok maxdata=" + c.maxData + " banner=" + c.systemBanner);
            return c;
        } catch (Throwable t) {
            HookLog.log(TAG + " handshake fail: " + t);
            c.close();
            return null;
        }
    }

    /**
     * 执行一条命令并取回 stdout+stderr。
     *
     * @param service {@code "shell:"} 或 {@code "exec:"}
     */
    public String run(String service, String command, int maxBytes) {
        try {
            int localId = open(service + command);
            if (localId < 0) {
                return null;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            boolean closed = false;
            long deadline = System.currentTimeMillis() + 20000;
            while (!closed && System.currentTimeMillis() < deadline) {
                Msg m = read();
                if (m == null) {
                    break;
                }
                if (m.cmd == OKAY) {
                    continue;
                }
                if (m.cmd == WRTE) {
                    if (buf.size() < maxBytes) {
                        buf.write(m.payload, 0, m.payload.length);
                    }
                    // 流控回执
                    send(OKAY, remoteId(), localId, null);
                    continue;
                }
                if (m.cmd == CLSE) {
                    closed = true;
                }
            }
            try {
                send(CLSE, localId, remoteId(), null);
            } catch (Throwable ignored) {
                // 对端可能已经关了
            }
            return new String(buf.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            HookLog.log(TAG + " run('" + command + "') fail: " + t);
            return null;
        }
    }

    /** {@code adb shell} 的等价物。 */
    public String shell(String command) {
        return run("shell:", command, 65536);
    }

    /**
     * 把本地文件写到设备（{@code exec:cat > remotePath}）。
     *
     * @return 实际写入字节数，失败返回 -1
     */
    public long push(File local, String remotePath) {
        if (local == null || !local.isFile()) {
            HookLog.log(TAG + " push: local file missing: " + local);
            return -1;
        }
        long size = local.length();
        try {
            int localId = open("exec:cat > " + remotePath);
            if (localId < 0) {
                return -1;
            }
            int bufSize = Math.min(maxData, CHUNK);
            byte[] buf = new byte[bufSize];
            long sent = 0;
            long t0 = System.currentTimeMillis();
            FileInputStream fis = new FileInputStream(local);
            try {
                InputStream is = fis;
                int n;
                while ((n = is.read(buf)) > 0) {
                    send(WRTE, localId, remoteId(), buf, n);
                    sent += n;
                    // 等一次 OKAY 做流控，顺手收集 stdout/错误
                    Msg ack = readAck(localId);
                    if (ack != null && ack.cmd == CLSE) {
                        HookLog.log(TAG + " push: peer closed at " + sent + "/" + size);
                        break;
                    }
                    if (sent % (32L * 1024 * 1024) < bufSize) {
                        HookLog.log(TAG + " push " + (sent / 1048576) + "MB / "
                                + (size / 1048576) + "MB  " + (System.currentTimeMillis() - t0) / 1000 + "s");
                    }
                }
            } finally {
                try {
                    fis.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
            send(CLSE, localId, remoteId(), null);
            // 收尾：读掉残余输出直到 CLSE
            drain(localId, 5000);
            HookLog.log(TAG + " ✔ push done " + sent + "B -> " + remotePath
                    + " in " + (System.currentTimeMillis() - t0) / 1000 + "s");
            return sent;
        } catch (Throwable t) {
            HookLog.log(TAG + " push fail: " + t);
            return -1;
        }
    }

    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private volatile int remote = 0;

    private int remoteId() {
        return remote;
    }

    /** 打开一条流，返回 localId；失败返回 -1。 */
    private int open(String destination) throws IOException {
        int localId = nextLocalId;
        nextLocalId += 2;
        byte[] payload = (destination + "\0").getBytes("UTF-8");
        send(OPEN, localId, 0, payload);
        Msg m = read();
        if (m == null) {
            HookLog.log(TAG + " open '" + destination + "' no reply");
            return -1;
        }
        if (m.cmd == FAIL) {
            HookLog.log(TAG + " open '" + destination + "' FAIL: "
                    + new String(m.payload, "UTF-8"));
            return -1;
        }
        if (m.cmd != OKAY) {
            HookLog.log(TAG + " open '" + destination + "' unexpected 0x"
                    + Integer.toHexString(m.cmd));
            return -1;
        }
        // OKAY: arg0 = 设备侧 local id，arg1 = 我们的 local id
        remote = m.arg0;
        return localId;
    }

    /** 读一条消息，若是 WRTE 就回 OKAY 并继续，直到拿到 OKAY/CLSE/超时。 */
    private Msg readAck(int localId) throws IOException {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            Msg m = read();
            if (m == null) {
                return null;
            }
            if (m.cmd == WRTE) {
                send(OKAY, remoteId(), localId, null);
                continue;
            }
            return m;
        }
        return null;
    }

    private void drain(int localId, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            socket.setSoTimeout(Math.max(300, timeoutMs));
            while (System.currentTimeMillis() < deadline) {
                Msg m = read();
                if (m == null || m.cmd == CLSE) {
                    return;
                }
                if (m.cmd == WRTE) {
                    send(OKAY, remoteId(), localId, null);
                }
            }
        } catch (Throwable ignored) {
            // 超时即认为结束
        }
    }

    private void send(int cmd, int arg0, int arg1, byte[] payload) throws IOException {
        send(cmd, arg0, arg1, payload, payload == null ? 0 : payload.length);
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] payload, int len)
            throws IOException {
        CRC32 crc = new CRC32();
        if (payload != null && len > 0) {
            crc.update(payload, 0, len);
        }
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(len);
        b.putInt((int) crc.getValue());
        b.putInt(cmd ^ 0xFFFFFFFF);
        if (len > 0) {
            b.put(payload, 0, len);
        }
        out.write(b.array());
        out.flush();
    }

    private Msg read() {
        try {
            byte[] hdr = new byte[24];
            in.readFully(hdr);
            ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            Msg m = new Msg();
            m.cmd = b.getInt();
            m.arg0 = b.getInt();
            m.arg1 = b.getInt();
            int len = b.getInt();
            m.dataCrc = b.getInt();
            m.magic = b.getInt();
            if (len < 0 || len > (64 * 1024 * 1024)) {
                HookLog.log(TAG + " bad length " + len);
                return null;
            }
            m.payload = new byte[len];
            if (len > 0) {
                in.readFully(m.payload);
            }
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    private static final class Msg {
        int cmd;
        int arg0;
        int arg1;
        int dataCrc;
        int magic;
        byte[] payload = new byte[0];
    }
}
