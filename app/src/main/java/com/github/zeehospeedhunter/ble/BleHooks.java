package com.github.zeehospeedhunter.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.github.zeehospeedhunter.core.HookLog;

/**
 * BLE GATT 流量记录 —— 只读观察，不改动任何写入内容。
 *
 * <p>两条记录通道：</p>
 * <ol>
 *   <li><b>带设备上下文</b>：{@code BluetoothGatt} 系方法，能拿到远端设备地址/名字；</li>
 *   <li><b>带数据上下文</b>：{@code BluetoothGattCharacteristic#setValue([B)}，
 *       AOSP 框架在收到通知（onNotify）与 App 组包写入时都调用它 —— 一条 hook 覆盖两个方向。
 *       该入口没有设备上下文，用 {@link #activeDevice} 会话标记过滤。</li>
 * </ol>
 */
public final class BleHooks {

    /** 当前活跃的车辆会话（设备名前缀命中时的 address），null = 无会话。 */
    private static volatile String activeDevice;

    /** address -> name 缓存，避免频繁 binder 调用。 */
    private static final Map<String, String> NAME_CACHE = new HashMap<>();

    public static void installAll() {
        if (!BleOptions.LOG) {
            HookLog.log("[ZeehoBLE] disabled by options");
            return;
        }
        hookAdapterConnectGatt();
        hookGattMethods();
        hookCharacteristicSetValue();
        HookLog.log("[ZeehoBLE] installed (gatt + setValue + connectGatt)");
    }

    // ==================== connectGatt ====================

    private static void hookAdapterConnectGatt() {
        if (!BleOptions.LOG_CONNECT) {
            return;
        }
        for (java.lang.reflect.Method m : BluetoothAdapter.class.getDeclaredMethods()) {
            if (!"connectGatt".equals(m.getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object dev = findArg(param.args, BluetoothDevice.class);
                        if (dev != null) {
                            onDeviceSeen((BluetoothDevice) dev, "connectGatt");
                        }
                    }
                });
            } catch (Throwable t) {
                HookLog.log("[ZeehoBLE] hook connectGatt fail " + m + ": " + t);
            }
        }
    }

    // ==================== BluetoothGatt 系方法 ====================

    private static void hookGattMethods() {
        Class<?> gatt = BluetoothGatt.class;

        hookMethod(gatt, "connect", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logDevice(param.thisObject, "connect");
            }
        });

        hookMethod(gatt, "discoverServices", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logDevice(param.thisObject, "discoverServices");
            }
        });

        if (BleOptions.LOG_WRITE) {
            for (java.lang.reflect.Method m : gatt.getDeclaredMethods()) {
                if (!"writeCharacteristic".equals(m.getName())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object c = param.args.length > 0 ? param.args[0] : null;
                            logDevice(param.thisObject, "writeCharacteristic");
                            logChar("WRITE-REQ", c);
                        }
                    });
                } catch (Throwable t) {
                    HookLog.log("[ZeehoBLE] hook writeCharacteristic fail: " + t);
                }
            }
        }

        if (BleOptions.LOG_READ_NOTIFY_SUB) {
            hookMethod(gatt, "readCharacteristic", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object c = param.args.length > 0 ? param.args[0] : null;
                    logChar("READ-REQ", c);
                }
            });
            hookMethod(gatt, "setCharacteristicNotification", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2) {
                        return;
                    }
                    boolean enable = Boolean.TRUE.equals(param.args[1]);
                    logDevice(param.thisObject, enable ? "SUB-NOTIFY" : "UNSUB-NOTIFY");
                    logChar(enable ? "SUB" : "UNSUB", param.args[0]);
                }
            });
        }
    }

    /** 框架类专用：单个方法名 hook（所有重载），失败只记一行不中断。 */
    private static void hookMethod(Class<?> clazz, String method, XC_MethodHook callback) {
        int n = 0;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (!method.equals(m.getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, callback);
                n++;
            } catch (Throwable t) {
                HookLog.log("[ZeehoBLE] hook " + clazz.getSimpleName() + "#" + method + " fail: " + t);
            }
        }
        if (n == 0) {
            HookLog.log("[ZeehoBLE] skip " + clazz.getSimpleName() + "#" + method + " (no overload matched)");
        }
    }

    // ==================== setValue（双向数据） ====================

    private static void hookCharacteristicSetValue() {
        Class<?> c = BluetoothGattCharacteristic.class;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!"setValue".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            final boolean bytes = p.length >= 1 && p[0] == byte[].class;
            if (!bytes) {
                continue; // 只关心字节数据
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        byte[] v = (byte[]) param.args[0];
                        String uuid;
                        try {
                            uuid = ((BluetoothGattCharacteristic) param.thisObject).getUuid().toString();
                        } catch (Throwable t) {
                            uuid = "?";
                        }
                        if (!uuidHinted(uuid) && activeDevice == null) {
                            return; // 与车辆无关且 UUID 不特殊，跳过
                        }
                        String dir = activeDevice != null ? "DATA" : "DATA(dev?)";
                        HookLog.log("[ZeehoBLE] " + dir + " setValue uuid=" + uuid
                                + " len=" + (v == null ? -1 : v.length)
                                + " hex=" + hex(v));
                    }
                });
            } catch (Throwable t) {
                HookLog.log("[ZeehoBLE] hook setValue fail: " + t);
            }
        }
    }

    // ==================== 辅助 ====================

    private static Object findArg(Object[] args, Class<?> type) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a != null && type.isInstance(a)) {
                return a;
            }
        }
        return null;
    }

    private static void logDevice(Object gattObj, String what) {
        try {
            BluetoothDevice d = (BluetoothDevice) XposedHelpers.callMethod(gattObj, "getDevice");
            onDeviceSeen(d, what);
        } catch (Throwable t) {
            HookLog.log("[ZeehoBLE] " + what + " (device resolve fail: " + t + ")");
        }
    }

    private static void onDeviceSeen(BluetoothDevice d, String what) {
        if (d == null) {
            return;
        }
        String addr = d.getAddress();
        String name = NAME_CACHE.get(addr);
        if (name == null) {
            try {
                name = d.getName();
            } catch (Throwable t) {
                name = null;
            }
            NAME_CACHE.put(addr, name == null ? "?" : name);
        }
        boolean hit = name != null;
        for (String p : BleOptions.NAME_PREFIXES) {
            if (name != null && name.regionMatches(true, 0, p, 0, p.length())) {
                hit = true;
                break;
            }
        }
        if (hit) {
            activeDevice = addr;
            HookLog.log("[ZeehoBLE] " + what + " -> " + name + " (" + addr + ") session=ACTIVE");
        } else {
            HookLog.log("[ZeehoBLE] " + what + " -> " + name + " (" + addr + ") session=skip");
        }
    }

    private static void logChar(String tag, Object characteristic) {
        if (characteristic == null) {
            return;
        }
        try {
            BluetoothGattCharacteristic c = (BluetoothGattCharacteristic) characteristic;
            String uuid = c.getUuid().toString();
            HookLog.log("[ZeehoBLE] " + tag + " uuid=" + uuid + " val=" + hex(c.getValue()));
        } catch (Throwable t) {
            HookLog.log("[ZeehoBLE] " + tag + " (resolve fail: " + t + ")");
        }
    }

    private static boolean uuidHinted(String uuid) {
        if (uuid == null) {
            return false;
        }
        for (String h : BleOptions.UUID_HINTS) {
            if (uuid.contains(h)) {
                return true;
            }
        }
        return false;
    }

    private static String hex(byte[] v) {
        if (v == null) {
            return "null";
        }
        int n = Math.min(v.length, BleOptions.MAX_BYTES);
        StringBuilder sb = new StringBuilder(n * 3 + 2);
        sb.append('[');
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", v[i]));
        }
        if (v.length > n) {
            sb.append(" ...(").append(v.length).append(')');
        }
        return sb.append(']').toString();
    }

    private BleHooks() {
    }
}
