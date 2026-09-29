package com.github.zeehospeedhunter.ble;

/**
 * BLE 区块开关与常量。
 *
 * <p>目标：记录 ZEEHO App 与车辆（仪表 BLE = ZEEHO-XXXXXX / TBOX = CFMOTO-XXXX）
 * 之间的 GATT 通信，摸清 b555 服务（写 b356 / 通知 b357）上的指令协议。</p>
 *
 * <p>锚点全部是框架类（boot classloader），与加固无关：</p>
 * <ul>
 *   <li>{@code BluetoothGatt#writeCharacteristic} / {@code readCharacteristic} —— 带设备上下文；</li>
 *   <li>{@code BluetoothGattCharacteristic#setValue([B)} —— AOSP 里通知数据也走这里，
 *       所以一条 hook 同时覆盖「App 发出的写」与「收到的通知」两个方向；</li>
 *   <li>{@code BluetoothAdapter#connectGatt} —— 建立会话并锁定目标设备。</li>
 * </ul>
 *
 * <p><b>红线</b>：只记录，不修改、不重放、不伪造任何指令。协议没弄清之前，
 * 主动向车辆写未知数据可能触发不可预期的车控行为。</p>
 */
public final class BleOptions {

    // ==================== 开关 ====================

    /** 总开关：记录 GATT 通信。 */
    public static final boolean LOG = true;

    /** 记录连接事件（connectGatt / connect / discoverServices）。 */
    public static final boolean LOG_CONNECT = true;

    /** 记录 App 发出的写指令（writeCharacteristic）。 */
    public static final boolean LOG_WRITE = true;

    /** 记录读操作与通知订阅（readCharacteristic / setCharacteristicNotification）。 */
    public static final boolean LOG_READ_NOTIFY_SUB = true;

    // ==================== 过滤 ====================

    /**
     * 只关心这些设备名前缀的 GATT 会话。命中后整个会话期间的
     * setValue（含收到的通知）都会被记录；没命中就不记，避免手表等设备刷屏。
     */
    public static final String[] NAME_PREFIXES = {"ZEEHO", "CFMOTO"};

    /** 无论会话是否激活，见到这些 UUID 片段一律记录（b555 服务族）。 */
    public static final String[] UUID_HINTS = {"b356", "b357", "b555"};

    // ==================== 阈值 ====================

    /** 单条日志最多记录的字节数，超出截断（指令一般 8~32 字节）。 */
    public static final int MAX_BYTES = 96;

    private BleOptions() {
    }
}
