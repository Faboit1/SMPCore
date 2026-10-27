package net.siftvanilla.siftcore.feature.staff;

/**
 * Where a punishment stands. A ban or mute is {@link #ACTIVE} until its end time passes ({@link #EXPIRED}) or staff
 * lift it ({@link #LIFTED}); both are final. Kicks and warnings are plain {@link #RECORD}s.
 */
public enum PunishmentState {
    ACTIVE,
    EXPIRED,
    LIFTED,
    RECORD
}
