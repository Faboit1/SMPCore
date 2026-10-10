package net.siftvanilla.siftcore.feature.cosmetics;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.Database;

/**
 * Every player's cosmetic choices (table {@code player_cosmetics}), held in memory so chat, combat and placeholders
 * read them from any thread without touching the database. Loaded once at startup (one row per player who chose
 * something) and written through on every change, in order, by the database's writer thread. A profile is
 * immutable and replaced atomically; the nickname index changes under the same lock, so two players can never claim
 * one nickname, and a nickname whose hold ran out passes to its new owner in one step.
 */
final class Profiles {

    /**
     * The result of claiming a nickname.
     *
     * @param ok        the nickname is the player's now
     * @param displaced the player who held it before and lost it (their hold had run out), or null
     * @param lost      the nickname as that player had it, or null
     */
    record Claim(boolean ok, UUID displaced, String lost) {

        static final Claim TAKEN = new Claim(false, null, null);
        static final Claim FREE = new Claim(true, null, null);
    }

    private final Database database;
    private final Logger logger;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    /** Lowercase nickname to its owner. */
    private final Map<String, UUID> nicks = new ConcurrentHashMap<>();

    Profiles(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /** Loads every stored profile; blocks, so call it at startup only. */
    void load() throws Exception {
        Map<UUID, Profile> loaded = this.database.read(c -> {
            Map<UUID, Profile> map = new HashMap<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, chat_style, nick, nick_style, tag, owned_tags, join_message, leave_message, "
                     + "kill_effect, nick_seen, nick_lost FROM player_cosmetics")) {
                while (rs.next()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    ChatStyle chat = ChatStyle.parse(rs.getString(2));
                    ChatStyle nickStyle = ChatStyle.parse(rs.getString(4));
                    String nick = rs.getString(3);
                    if (nick != null && !nick.matches("[A-Za-z0-9_]{1,16}")) {
                        nick = null;
                    }
                    String lost = rs.getString(11);
                    if (lost != null && !lost.matches("[A-Za-z0-9_]{1,16}")) {
                        lost = null;
                    }
                    map.put(uuid, new Profile(chat, nick, nickStyle, rs.getString(5), Profile.parseOwnedTags(rs.getString(6)),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getLong(10), lost));
                }
            }
            return map;
        }).get();
        synchronized (this) {
            this.profiles.clear();
            this.nicks.clear();
            loaded.forEach((uuid, profile) -> {
                if (profile.nick() != null && this.nicks.putIfAbsent(profile.nick().toLowerCase(Locale.ROOT), uuid) != null) {
                    // Two stored rows with one nickname (only possible by editing the table): the second loses it.
                    profile = profile.withNick(null, profile.nickStyle(), 0L);
                }
                this.profiles.put(uuid, profile);
            });
        }
    }

    /** A player's profile ({@link Profile#EMPTY} when they never chose anything). */
    Profile get(UUID player) {
        Profile profile = this.profiles.get(player);
        return profile == null ? Profile.EMPTY : profile;
    }

    /** Who uses a nickname, ignoring case. */
    Optional<UUID> nickOwner(String nick) {
        return nick == null ? Optional.empty() : Optional.ofNullable(this.nicks.get(nick.toLowerCase(Locale.ROOT)));
    }

    int size() {
        return this.profiles.size();
    }

    int nickCount() {
        return this.nicks.size();
    }

    /**
     * Changes a profile (not its nickname) and stores it. Returns the new profile. The write is queued under the
     * same lock as the change, so the database receives changes in the order memory made them.
     */
    synchronized Profile update(UUID player, UnaryOperator<Profile> change) {
        Profile current = get(player);
        Profile updated = change.apply(current);
        if (updated.nick() == null ? current.nick() != null : !updated.nick().equals(current.nick())) {
            throw new IllegalArgumentException("Nicknames change through setNick");
        }
        put(player, updated);
        store(player, updated);
        return updated;
    }

    /**
     * Sets or clears ({@code nick} null) a player's nickname. When another player has it stored, {@code holds} decides
     * under the same lock: while it holds, nothing changes and the claim is refused; once it no longer does (they
     * can't show it and its hold ran out), they lose it to this player, and the returned claim names them.
     *
     * @param now   when the player is seen able to show the new nickname
     * @param holds whether another player's stored nickname still blocks this one
     */
    synchronized Claim setNick(UUID player, String nick, ChatStyle style, long now, Predicate<UUID> holds) {
        UUID displaced = null;
        String lost = null;
        if (nick != null) {
            String lower = nick.toLowerCase(Locale.ROOT);
            UUID holder = this.nicks.get(lower);
            if (holder != null && !holder.equals(player)) {
                if (holds.test(holder)) {
                    return Claim.TAKEN;
                }
                Profile previous = get(holder);
                lost = previous.nick() == null ? nick : previous.nick();
                Profile released = previous.withNick(null, previous.nickStyle(), 0L).withNickLost(lost);
                this.nicks.remove(lower, holder);
                put(holder, released);
                store(holder, released);
                displaced = holder;
            }
        }
        Profile current = get(player);
        if (current.nick() != null) {
            this.nicks.remove(current.nick().toLowerCase(Locale.ROOT), player);
        }
        Profile updated = current.withNick(nick, nick == null ? current.nickStyle() : style, now);
        if (nick != null) {
            this.nicks.put(nick.toLowerCase(Locale.ROOT), player);
        }
        put(player, updated);
        store(player, updated);
        return displaced == null ? Claim.FREE : new Claim(true, displaced, lost);
    }

    /** Records that a player was seen able to show their nickname at {@code now} (no change without one). */
    synchronized void seeNick(UUID player, long now) {
        Profile current = get(player);
        if (current.nick() == null || current.nickSeen() >= now) {
            return;
        }
        Profile updated = current.withNickSeen(now);
        put(player, updated);
        store(player, updated);
    }

    /**
     * Staff reset: forgets every choice but the monthly exclusives the player owns (those can't be picked again once
     * their month is over). Returns the profile as it was, for the audit log.
     */
    synchronized Profile reset(UUID player) {
        Profile current = get(player);
        if (current.nick() != null) {
            this.nicks.remove(current.nick().toLowerCase(Locale.ROOT), player);
        }
        Profile updated = current.reset();
        put(player, updated);
        store(player, updated);
        return current;
    }

    private void put(UUID player, Profile profile) {
        if (profile.empty()) {
            this.profiles.remove(player);
        } else {
            this.profiles.put(player, profile);
        }
    }

    /** Queues the write of a profile (an empty one deletes the row). Writes run in order, so the last change wins. */
    private void store(UUID player, Profile profile) {
        CompletableFuture<Void> write = this.database.write(c -> {
            if (profile.empty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM player_cosmetics WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(this.database.dialect().replaceUpsert("player_cosmetics", new String[] {"uuid"},
                new String[] {"chat_style", "nick", "nick_lower", "nick_style", "tag", "owned_tags", "join_message", "leave_message",
                    "kill_effect", "nick_seen", "nick_lost", "updated"}))) {
                ps.setString(1, player.toString());
                ps.setString(2, emptyToNull(profile.chatStyle().serialize()));
                ps.setString(3, profile.nick());
                ps.setString(4, profile.nick() == null ? null : profile.nick().toLowerCase(Locale.ROOT));
                ps.setString(5, emptyToNull(profile.nickStyle().serialize()));
                ps.setString(6, profile.tag());
                ps.setString(7, emptyToNull(profile.ownedTagsText()));
                ps.setString(8, profile.joinMessage());
                ps.setString(9, profile.leaveMessage());
                ps.setString(10, profile.killEffect());
                ps.setLong(11, profile.nickSeen());
                ps.setString(12, profile.nickLost());
                ps.setLong(13, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
        write.exceptionally(error -> {
            this.logger.log(Level.WARNING, "Could not store the cosmetics of " + player, error);
            return null;
        });
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    /** Rows in the table, for the self-test. */
    CompletableFuture<Integer> countRows() {
        return this.database.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM player_cosmetics")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
