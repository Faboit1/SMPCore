package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Combat alerts for staff with {@link CombatCommands#ADMIN}: players who log out in combat and, for those who chose
 * it, kills that didn't count (signs of kill farming). Each staff member's {@code staff-combat-alerts} choice decides;
 * the players concerned never get the staff line. Safe from any thread (permission checks and packets only).
 */
final class StaffNotices {

    private final PlayerSettings settings;
    private final Messenger messenger;
    private final Lang lang;
    private final PlayerDirectory directory;

    StaffNotices(PlayerSettings settings, Messenger messenger, Lang lang, PlayerDirectory directory) {
        this.settings = settings;
        this.messenger = messenger;
        this.lang = lang;
        this.directory = directory;
    }

    /** {@code player} left in combat with {@code left} to go; {@code lastAttacker} may be null. */
    void combatLog(UUID player, String name, Duration left, UUID lastAttacker) {
        Arg time = Arg.time("time", Duration.ofSeconds(TagTicker.secondsLeft(left.toMillis(), 0)));
        if (lastAttacker == null) {
            send(StaffAlerts::combatLogs, player, null, CombatMessages.STAFF_COMBAT_LOG_NO_HIT, Arg.text("name", name), time);
        } else {
            send(StaffAlerts::combatLogs, player, lastAttacker, CombatMessages.STAFF_COMBAT_LOG, Arg.text("name", name), time,
                Arg.text("attacker", this.directory.name(lastAttacker)));
        }
    }

    /** A kill that didn't count, with the reason staff see in {@code /combat kills}. */
    void notCounted(UUID killer, UUID victim, AntiFarm.Reason reason) {
        send(StaffAlerts::farming, killer, victim, CombatMessages.STAFF_NOT_COUNTED, Arg.text("killer", this.directory.name(killer)),
            Arg.text("victim", this.directory.name(victim)), Arg.text("reason", this.lang.plain(CombatMessages.reason(reason))));
    }

    private void send(Predicate<StaffAlerts> wants, UUID first, UUID second, MessageKey key, Arg... args) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            UUID id = staff.getUniqueId();
            if (id.equals(first) || id.equals(second) || !staff.hasPermission(CombatCommands.ADMIN)) {
                continue;
            }
            if (wants.test(this.settings.get(staff, CombatFeature.STAFF_ALERTS))) {
                this.messenger.chat(staff, key, args);
            }
        }
    }
}
