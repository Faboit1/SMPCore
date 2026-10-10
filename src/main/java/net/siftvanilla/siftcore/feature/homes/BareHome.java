package net.siftvanilla.siftcore.feature.homes;

/**
 * What {@code /home} with no name does: the options of the {@code homes-bare-command} setting, and the pure decision
 * (unit tested).
 */
public enum BareHome {
    /** Go to the only home, or open the list when there are several (the classic behaviour). */
    SMART("smart"),
    /** Go to the home named {@code home}; without one, like {@link #SMART}. */
    DEFAULT_HOME("default-home"),
    /** Always open the list. */
    LIST("list");

    /** What one bare {@code /home} does. */
    public enum Action {
        /** The player has no homes: tell them. */
        NONE,
        /** Teleport to the player's only home. */
        ONLY,
        /** Teleport to the home named {@code home}. */
        DEFAULT,
        /** Open the homes list. */
        LIST
    }

    private final String id;

    BareHome(String id) {
        this.id = id;
    }

    /** The stored option id. */
    public String id() {
        return this.id;
    }

    /**
     * What a bare {@code /home} does for a player with {@code homes} homes.
     *
     * @param hasDefault whether one of them is named {@code home}
     */
    public Action decide(int homes, boolean hasDefault) {
        if (homes <= 0) {
            return Action.NONE;
        }
        return switch (this) {
            case LIST -> Action.LIST;
            case DEFAULT_HOME -> hasDefault ? Action.DEFAULT : smart(homes);
            case SMART -> smart(homes);
        };
    }

    private static Action smart(int homes) {
        return homes == 1 ? Action.ONLY : Action.LIST;
    }
}
