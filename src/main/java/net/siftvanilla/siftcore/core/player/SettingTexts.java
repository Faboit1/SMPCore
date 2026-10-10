package net.siftvanilla.siftcore.core.player;

import net.siftvanilla.siftcore.core.text.MessageKey;

/** Text the settings model itself shows: toggle states and shared units ({@code lang/settings.yml}). */
public final class SettingTexts {

    public static final MessageKey STATE_ON = MessageKey.ui("settings.state-on");
    public static final MessageKey STATE_OFF = MessageKey.ui("settings.state-off");
    /** The unit of percentages (written right after the number). */
    public static final MessageKey UNIT_PERCENT = MessageKey.ui("settings.units.percent");

    private SettingTexts() {
    }
}
