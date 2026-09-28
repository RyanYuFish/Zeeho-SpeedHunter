package io.github.codex.zeehospeed.ota;

import io.github.codex.zeehospeed.core.HookLog;

/** OTA 区块的装配入口：按 {@link OtaOptions} 里的开关依次安装各条 hook。 */
public final class OtaHooks {

    private OtaHooks() {
    }

    public static void installAll() {
        try {
            if (OtaOptions.UNLOCK_UI) OtaPageUnlock.install();
            if (OtaOptions.LOG_HTTP) OtaTraffic.hookRequests();
            if (OtaOptions.LOG_RESPONSE) OtaTraffic.hookResponseBodies();
            if (OtaOptions.LOG_DOWNLOAD) OtaTraffic.hookDownloadProbe();
            if (OtaOptions.LOG_WEBVIEW) OtaTraffic.hookWebView();
            if (OtaOptions.LOG_SERVICE) OtaTraffic.hookServices();
            if (OtaOptions.DISCOVER_CLASSES) OtaTraffic.hookClassDiscovery();
            HookLog.log(HookLog.OTA + " ota hooks installed");
        } catch (Throwable t) {
            HookLog.log(HookLog.OTA + " install failed: " + t);
        }
    }
}
