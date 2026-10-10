package net.siftvanilla.siftcore.feature.admin;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text of the admin tools ({@code lang/admin.yml}). */
public final class AdminMessages {

    public static final MessageKey RELOADED = MessageKey.chat("admin.reload.done", "files", "time");
    public static final MessageKey RELOAD_FAILED = MessageKey.chat("admin.reload.failed", "count");
    public static final MessageKey RELOAD_PARTIAL = MessageKey.chat("admin.reload.partial", "files", "time", "count");
    public static final MessageKey PROBLEM = MessageKey.chat("admin.problem", "file", "path", "message");
    public static final MessageKey DEBUG = MessageKey.chat("admin.debug", "state");
    public static final MessageKey VERSION = MessageKey.chat("admin.version", "version", "server", "threading");
    public static final MessageKey METRICS_HEADER = MessageKey.chat("admin.metrics.header", "uptime");
    public static final MessageKey METRICS_LINE = MessageKey.chat("admin.metrics.line", "name", "value");
    public static final MessageKey SELFTEST_START = MessageKey.chat("admin.selftest.start", "count");
    public static final MessageKey SELFTEST_PASS = MessageKey.chat("admin.selftest.pass", "feature", "name", "time");
    public static final MessageKey SELFTEST_FAIL = MessageKey.chat("admin.selftest.fail", "feature", "name", "detail");
    public static final MessageKey SELFTEST_DONE = MessageKey.chat("admin.selftest.done", "passed", "failed", "time");
    public static final MessageKey CONFIG_ALERT = MessageKey.notify("admin.config-alert.line", "count");
    public static final MessageKey CONFIG_ALERT_HOVER_MORE = MessageKey.ui("admin.config-alert.hover-more", "count");
    public static final MessageKey SETTING_CONFIG_ALERTS = MessageKey.ui("admin.settings.config-alerts");
    public static final MessageKey SETTING_CONFIG_ALERTS_DESCRIPTION = MessageKey.ui("admin.settings.config-alerts-description");
    public static final MessageKey ON = MessageKey.ui("admin.on");
    public static final MessageKey OFF = MessageKey.ui("admin.off");

    private AdminMessages() {
    }
}
