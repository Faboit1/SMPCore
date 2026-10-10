package net.siftvanilla.siftcore.feature.rtp;

/**
 * What {@code /rtp} with no region does: the options of the {@code rtp-default} setting, and the pure decisions of the
 * random teleport settings (unit tested).
 */
public enum RtpDefault {
    /** Open the region picker (the classic behaviour). */
    MENU("menu"),
    /** Go straight to the region the player used last, when it can be used now; otherwise the picker. */
    LAST("last");

    private final String id;

    RtpDefault(String id) {
        this.id = id;
    }

    /** The stored option id. */
    public String id() {
        return this.id;
    }

    /**
     * Whether a bare {@code /rtp} goes straight to the last region instead of opening the picker.
     *
     * @param last   the region the player used last still exists (it is null or removed otherwise)
     * @param usable it is enabled, the player may use it, it fits its world and its cooldown is over
     */
    public boolean straight(boolean last, boolean usable) {
        return this == LAST && last && usable;
    }

    /**
     * Whether starting a random teleport asks first with the price ("Confirm paid random teleports"): only for a
     * region that costs money, typed as a command (the picker already shows the price), while the player keeps the
     * setting on.
     */
    static boolean asksCost(boolean wanted, long cost, boolean fromPicker) {
        return wanted && cost > 0 && !fromPicker;
    }
}
