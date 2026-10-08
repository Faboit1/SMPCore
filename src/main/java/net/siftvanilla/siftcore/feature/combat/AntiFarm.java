package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Whether a player kill counts (kill stat, streak, bounty) or is a farmed kill. Pure: the caller looks up teams,
 * friendships, IP hashes and the last counted kill of the pair, this decides. Rules are checked in a fixed order so
 * the logged reason is stable: same team, friends, same IP, then the same pair again too soon.
 */
final class AntiFarm {

    /** Why a kill did not count. The id is stored in the kill log. */
    enum Reason {
        SAME_TEAM("same_team"),
        FRIENDS("friends"),
        SAME_IP("same_ip"),
        REPEATED_PAIR("repeated_pair"),
        CANCELLED("cancelled");

        private final String id;

        Reason(String id) {
            this.id = id;
        }

        String id() {
            return this.id;
        }

        static Reason byId(String id) {
            for (Reason reason : values()) {
                if (reason.id.equals(id)) {
                    return reason;
                }
            }
            return null;
        }
    }

    /** Which rules are on; a zero cooldown turns the repeated-pair rule off. */
    record Rules(boolean sameTeam, boolean friends, boolean sameIp, Duration repeatedPairCooldown) {
        Rules {
            Objects.requireNonNull(repeatedPairCooldown);
        }
    }

    /**
     * What is known about one kill.
     *
     * @param sameTeam        killer and victim are in the same team
     * @param friends         killer and victim are friends
     * @param sameIp          killer and victim were last seen from the same IP
     * @param lastCountedPair when this killer last got a counted kill on this victim
     */
    record Facts(boolean sameTeam, boolean friends, boolean sameIp, OptionalLong lastCountedPair) {
        Facts {
            Objects.requireNonNull(lastCountedPair);
        }

        /** A kill between strangers who never killed each other before. */
        static final Facts FAIR = new Facts(false, false, false, OptionalLong.empty());
    }

    /** The outcome: counted, or not counted with a reason. */
    record Decision(boolean counted, Reason reason) {

        static final Decision COUNTED = new Decision(true, null);

        static Decision denied(Reason reason) {
            return new Decision(false, Objects.requireNonNull(reason));
        }
    }

    private AntiFarm() {
    }

    /** Decides one kill that happened at {@code now} (epoch millis). */
    static Decision decide(Rules rules, Facts facts, long now) {
        if (rules.sameTeam() && facts.sameTeam()) {
            return Decision.denied(Reason.SAME_TEAM);
        }
        if (rules.friends() && facts.friends()) {
            return Decision.denied(Reason.FRIENDS);
        }
        if (rules.sameIp() && facts.sameIp()) {
            return Decision.denied(Reason.SAME_IP);
        }
        long cooldown = rules.repeatedPairCooldown().toMillis();
        OptionalLong last = facts.lastCountedPair();
        if (cooldown > 0 && last.isPresent() && now - last.getAsLong() < cooldown) {
            return Decision.denied(Reason.REPEATED_PAIR);
        }
        return Decision.COUNTED;
    }
}
