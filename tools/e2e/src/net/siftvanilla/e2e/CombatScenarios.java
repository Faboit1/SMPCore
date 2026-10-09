package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.economy.SystemAccounts;
import net.siftvanilla.siftcore.feature.combat.CombatFeature;
import net.siftvanilla.siftcore.feature.friends.FriendsFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

/**
 * Combat and bounties with real clients: tagging by hits, refused commands and pearls, the action-bar timer, kill
 * credit (also for deaths after a hit), anti-farm, death messages and their setting, combat logging with both
 * punishments, and bounties from placement through the dialogs to claims, refunds and the escrow self-test.
 * <p>
 * All bots connect from 127.0.0.1, so scenarios that need counted kills turn the same-IP rule off for their
 * duration (and restore the file afterwards); {@code bounty-claim} also checks the rule itself.
 */
final class CombatScenarios {

    private static final String COMBAT = "features/combat.yml";
    private static final long JOIN_PROTECTION_MILLIS = 3_500;
    private static final long BOUNTY_COOLDOWN_MILLIS = 5_200;

    private CombatScenarios() {
    }

    private record Named(String name, Body body) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            this.body.run(e2e);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(E2E e2e) throws Exception;
    }

    private static Scenario of(String name, Body body) {
        return new Named(name, body);
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(of("combat-tag", CombatScenarios::tag));
        list.add(of("combat-kill", CombatScenarios::kill));
        list.add(of("combat-credit", CombatScenarios::credit));
        list.add(of("combat-log", CombatScenarios::combatLog));
        list.add(of("combat-log-none", CombatScenarios::combatLogNone));
        list.add(of("combat-pearl", CombatScenarios::pearl));
        list.add(of("combat-streak", CombatScenarios::streak));
        list.add(of("combat-team", CombatScenarios::team));
        list.add(of("combat-vanish", CombatScenarios::vanish));
        list.add(of("combat-pair-memory", CombatScenarios::pairMemory));
        list.add(of("combat-ex-friends", CombatScenarios::exFriends));
        list.add(of("bounty-place", CombatScenarios::bountyPlace));
        list.add(of("bounty-claim", CombatScenarios::bountyClaim));
        list.add(of("bounty-admin", CombatScenarios::bountyAdmin));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    /** Runs {@code body} with text in a SiftCore config file replaced, then restores the file and reloads. */
    private static void withConfig(E2E e2e, String file, Map<String, String> replacements, Body body) throws Exception {
        Path path = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve(file);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String changed = original;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            e2e.expect(changed.contains(entry.getKey()), file + " contains '" + entry.getKey() + "'");
            changed = changed.replace(entry.getKey(), entry.getValue());
        }
        Files.writeString(path, changed, StandardCharsets.UTF_8);
        e2e.expect(String.join(" ", output(e2e, "sift reload", 0)).contains("Reloaded"), "the changed " + file + " reloads");
        try {
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /** Runs a command as a console-like sender and returns what it said (plain text), after waiting a little. */
    private static List<String> output(E2E e2e, String command, long waitMillis) {
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(harness(), () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message ->
                    lines.add(PlainTextComponentSerializer.plainText().serialize(message))), command);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("command '" + command + "' failed: " + e);
        }
        e2e.sleep(Math.max(200, waitMillis));
        return List.copyOf(lines);
    }

    private static UUID known(E2E e2e, String name) {
        return e2e.services().directory().uuid(name).orElseThrow(() -> new E2E.Failure(name + " never joined"));
    }

    private static String placeholder(E2E e2e, String name, String placeholder) {
        return e2e.services().placeholders().resolve(Bukkit.getOfflinePlayer(known(e2e, name)), placeholder);
    }

    private static boolean tagged(E2E e2e, String name) {
        return "true".equals(placeholder(e2e, name, "combat_tagged"));
    }

    private static long bounty(E2E e2e, String name) {
        return Long.parseLong(placeholder(e2e, name, "bounty_total_raw"));
    }

    /** A stats placeholder as a number ({@code kills}, {@code deaths}, {@code streak}). */
    private static long stat(E2E e2e, String name, String stat) {
        return Long.parseLong(placeholder(e2e, name, "stats_" + stat).replace(",", ""));
    }

    private static long escrow(E2E e2e) {
        return e2e.services().ledger().balance(SystemAccounts.BOUNTY_ESCROW, Currency.MONEY);
    }

    private static long money(E2E e2e, String name) {
        return e2e.services().ledger().balance(known(e2e, name), Currency.MONEY);
    }

    /** Puts the victim where the attacker stands, so a punch reaches (players spawn anywhere around spawn). */
    private static void together(E2E e2e, String attacker, String victim) {
        Location at = e2e.onPlayer(attacker, () -> e2e.player(attacker).getLocation().clone());
        try {
            Boolean moved = e2e.player(victim).teleportAsync(at).get(10, TimeUnit.SECONDS);
            e2e.expect(Boolean.TRUE.equals(moved), victim + " was moved next to " + attacker);
        } catch (E2E.Failure failure) {
            throw failure;
        } catch (Exception e) {
            throw new E2E.Failure("moving " + victim + " next to " + attacker + " failed: " + e);
        }
        e2e.sleep(400);
    }

    /** Hits {@code victim} until the hit lands (join and respawn protection drop early hits). */
    private static void hit(E2E e2e, Bot attacker, String victim) {
        together(e2e, attacker.name, victim);
        long end = System.currentTimeMillis() + 12_000;
        while (System.currentTimeMillis() < end) {
            attacker.attack(e2e.player(victim).getEntityId());
            if (Bot.await(() -> tagged(e2e, victim), 700)) {
                return;
            }
        }
        throw new E2E.Failure(attacker.name + " could not land a hit on " + victim);
    }

    /** Brings the victim down to half a heart and hits them until they die, then waits out the respawn protection. */
    private static void killWithHit(E2E e2e, Bot killer, Bot victim) {
        together(e2e, killer.name, victim.name);
        int before = victim.deaths();
        long end = System.currentTimeMillis() + 12_000;
        while (victim.deaths() == before && System.currentTimeMillis() < end) {
            Player target = e2e.player(victim.name);
            e2e.onPlayer(victim.name, () -> {
                if (!target.isDead()) {
                    target.setHealth(Math.min(target.getHealth(), 0.5));
                }
                return null;
            });
            killer.attack(target.getEntityId());
            Bot.await(() -> victim.deaths() > before, 700);
        }
        e2e.expect(victim.deaths() > before, victim.name + " died from " + killer.name + "'s hit");
        afterDeath(e2e, victim);
    }

    /** Kills a player without any attacker (the game's generic death), then waits out the respawn protection. */
    private static void killQuietly(E2E e2e, Bot victim) {
        int before = victim.deaths();
        Player target = e2e.player(victim.name);
        e2e.onPlayer(victim.name, () -> {
            target.setHealth(0);
            return null;
        });
        e2e.eventually(() -> victim.deaths() > before, victim.name + " died");
        afterDeath(e2e, victim);
    }

    /**
     * Waits for the respawn, moves the player out of the protected spawn area they respawn in (nobody can fight
     * there), then waits out the respawn protection.
     */
    private static void afterDeath(E2E e2e, Bot victim) {
        String name = victim.name;
        e2e.eventually(() -> {
            Player player = Bukkit.getPlayerExact(name);
            return player != null && !player.isDead() && player.getHealth() > 0;
        }, name + " respawned");
        e2e.leaveSpawn(victim);
        e2e.sleep(JOIN_PROTECTION_MILLIS);
    }

    /** The kill rows of a pair, oldest first: "counted" or the reason it did not count. */
    private static List<String> kills(E2E e2e, String killer, String victim) {
        UUID k = known(e2e, killer);
        UUID v = known(e2e, victim);
        try {
            return e2e.services().database().read(c -> {
                List<String> rows = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT counted, reason FROM kills WHERE killer = ? AND victim = ? ORDER BY id")) {
                    ps.setString(1, k.toString());
                    ps.setString(2, v.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(rs.getInt(1) == 1 ? "counted" : rs.getString(2));
                        }
                    }
                }
                return rows;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("reading the kill log failed: " + e);
        }
    }

    /** Collects every death-screen message (the same line the server sends to chat) by victim name. */
    private static final class Deaths implements Listener, AutoCloseable {

        final Map<String, Component> lines = new ConcurrentHashMap<>();

        Deaths() {
            Bukkit.getPluginManager().registerEvent(PlayerDeathEvent.class, this, EventPriority.MONITOR, (listener, event) -> {
                if (event instanceof PlayerDeathEvent death && death.deathScreenMessageOverride() != null) {
                    this.lines.put(death.getPlayer().getName(), death.deathScreenMessageOverride());
                }
            }, harness());
        }

        @Override
        public void close() {
            HandlerList.unregisterAll(this);
        }
    }

    private static boolean showsAnItem(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM) {
            return true;
        }
        if (component instanceof net.kyori.adventure.text.TranslatableComponent translatable) {
            for (var argument : translatable.arguments()) {
                if (showsAnItem(argument.asComponent())) {
                    return true;
                }
            }
        }
        for (Component child : component.children()) {
            if (showsAnItem(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holds(E2E e2e, String name, Material material, int amount) {
        Player player = e2e.player(name);
        return e2e.onPlayer(name, () -> player.getInventory().contains(material, amount));
    }

    // ------------------------------------------------------------------ combat

    static void tag(E2E e2e) {
        String attackerName = e2e.name("TagA");
        String victimName = e2e.name("TagB");
        Bot a = e2e.bot(attackerName);
        Bot b = e2e.bot(victimName);
        e2e.sleep(JOIN_PROTECTION_MILLIS);

        e2e.step("one hit tags both players");
        e2e.expect(!tagged(e2e, attackerName) && !tagged(e2e, victimName), "nobody is in combat before the hit");
        a.clearLogs();
        b.clearLogs();
        hit(e2e, a, victimName);
        e2e.eventually(() -> tagged(e2e, attackerName), attackerName + " (the attacker) is in combat too");
        e2e.eventually(() -> b.actionBarContains("In combat"), "the timer on the action bar: " + b.actionBar());
        e2e.eventually(() -> a.actionBarContains("In combat"), "the attacker's timer: " + a.actionBar());
        long left = Long.parseLong(placeholder(e2e, victimName, "combat_time"));
        e2e.expect(left >= 15 && left <= 20, "combat_time counts down from 20, got " + left);

        e2e.step("blocked commands are refused, with namespaces and subcommands");
        for (String command : List.of("home", "siftcore:spawn", "TPA " + attackerName, "team home")) {
            b.clearLogs();
            b.command(command);
            e2e.eventually(() -> b.actionBarContains("can't use that in combat"), "/" + command + " refused: " + b.actionBar());
        }
        b.clearLogs();
        b.command("balance");
        e2e.eventually(() -> b.chatContains("You have"), "other commands still work: " + b.chat());

        e2e.step("staff see who hit whom");
        List<String> status = output(e2e, "combat status " + victimName, 300);
        e2e.expect(String.join(" ", status).contains(victimName + " is in combat for")
            && String.join(" ", status).contains("Last hit by " + attackerName), "status: " + status);

        e2e.step("staff untag: the player is told once");
        b.clearLogs();
        e2e.expect(String.join(" ", output(e2e, "combat untag " + victimName, 0)).contains("no longer in combat"), "untag reply");
        e2e.eventually(() -> b.actionBarContains("You are no longer in combat."), "end message: " + b.actionBar());
        e2e.expect(!tagged(e2e, victimName), "untagged");
        e2e.sleep(1_500);
        e2e.expect(b.actionBar().stream().filter(line -> line.contains("no longer in combat")).count() == 1, "told once: " + b.actionBar());
        b.clearLogs();
        b.command("home");
        e2e.sleep(800);
        e2e.expect(!b.actionBarContains("can't use that in combat"), "commands are free again");

        e2e.step("a tag runs out on its own");
        e2e.expect(String.join(" ", output(e2e, "combat tag " + victimName + " 2s", 0)).contains("now in combat for 2s"), "staff tag reply");
        e2e.eventually(() -> tagged(e2e, victimName), "tagged by staff");
        b.clearLogs();
        e2e.eventually(() -> b.actionBarContains("You are no longer in combat."), 6_000, "the 2s tag ended: " + b.actionBar());
        e2e.expect("0".equals(placeholder(e2e, victimName, "combat_time")), "combat_time is 0 again");
        b.clearLogs();
        b.command("combat");
        e2e.eventually(() -> b.actionBarContains("You are not in combat."), "/combat answers: " + b.actionBar());
    }

    static void kill(E2E e2e) throws Exception {
        withConfig(e2e, COMBAT, Map.of("same-ip: true", "same-ip: false"), x -> {
            String killerName = e2e.name("Killer");
            String victimName = e2e.name("Victim");
            Bot k = e2e.bot(killerName);
            Bot v = e2e.bot(victimName);
            e2e.console("give " + killerName + " diamond_sword");
            e2e.eventually(() -> holds(e2e, killerName, Material.DIAMOND_SWORD, 1), "the killer holds a sword");
            e2e.sleep(JOIN_PROTECTION_MILLIS);
            try (Deaths deaths = new Deaths()) {
                e2e.step("a sword kill is credited, counted and announced with the weapon");
                k.clearLogs();
                v.clearLogs();
                killWithHit(e2e, k, v);
                String line = victimName + " was killed by " + killerName + " using Diamond Sword.";
                e2e.eventually(() -> k.chatContains(line), "the killer sees '" + line + "': " + k.chat());
                e2e.eventually(() -> v.chatContains(line), "the victim sees it: " + v.chat());
                e2e.eventually(() -> kills(e2e, killerName, victimName).equals(List.of("counted")),
                    "one counted kill: " + kills(e2e, killerName, victimName));
                e2e.expect(stat(e2e, killerName, "kills") == 1 && stat(e2e, killerName, "streak") == 1
                    && stat(e2e, victimName, "deaths") == 1, "the stats got the kill and the death");
                Component screen = deaths.lines.get(victimName);
                e2e.expect(screen != null && showsAnItem(screen), "the weapon shows the item on hover: " + screen);
                e2e.expect(!tagged(e2e, victimName), "the victim's combat ended with the death");

                e2e.step("the same pair again within 10 minutes is logged but not counted");
                killWithHit(e2e, k, v);
                e2e.eventually(() -> kills(e2e, killerName, victimName).equals(List.of("counted", "repeated_pair")),
                    "a repeated pair: " + kills(e2e, killerName, victimName));
                e2e.eventually(() -> v.chat().stream().filter(text -> text.contains(line)).count() == 2, "the death is still announced");
                e2e.expect(stat(e2e, killerName, "kills") == 1 && stat(e2e, killerName, "streak") == 1,
                    "a kill that does not count adds no kill or streak");
                e2e.expect(stat(e2e, victimName, "deaths") == 2, "the victim's death still counts");
            }

            e2e.step("staff read the kill log");
            String log = String.join("\n", output(e2e, "combat kills " + victimName, 1_000));
            e2e.expect(log.contains("killed " + victimName + ", counted") && log.contains("not counted: killed again too soon"), "kill log:\n" + log);
            // The killer leaves while still tagged and dies at spawn; without this their sword would lie there for
            // the next scenario's players to pick up.
            e2e.console("clear " + killerName);
            e2e.eventually(() -> !holds(e2e, killerName, Material.DIAMOND_SWORD, 1), "the sword is gone");
        });
    }

    static void credit(E2E e2e) throws Exception {
        withConfig(e2e, COMBAT, Map.of("same-ip: true", "same-ip: false"), x -> {
            String attackerName = e2e.name("CredA");
            String fallerName = e2e.name("CredB");
            String quietName = e2e.name("CredQuiet");
            String loudName = e2e.name("CredLoud");
            Bot a = e2e.bot(attackerName);
            Bot b = e2e.bot(fallerName);
            Bot quiet = e2e.bot(quietName);
            Bot loud = e2e.bot(loudName);
            e2e.sleep(JOIN_PROTECTION_MILLIS);
            // Bare hands: anything picked up at spawn (items from an earlier scenario's deaths) would add "using".
            e2e.console("clear " + attackerName);
            e2e.eventually(() -> e2e.onPlayer(attackerName, () -> e2e.player(attackerName).getInventory().getItemInMainHand().isEmpty()),
                "the attacker's hand is empty");
            try (Deaths deaths = new Deaths()) {
                e2e.step("a death after a hit is credited to the attacker");
                hit(e2e, a, fallerName);
                a.clearLogs();
                killQuietly(e2e, b);
                String line = fallerName + " was killed by " + attackerName + ".";
                e2e.eventually(() -> a.chatContains(line), "credited: " + a.chat());
                e2e.eventually(() -> kills(e2e, attackerName, fallerName).equals(List.of("counted")), "counted: "
                    + kills(e2e, attackerName, fallerName));

                e2e.step("other deaths keep the game's message, in gray, and respect the setting");
                e2e.services().settings().set(known(e2e, quietName), CombatFeature.DEATH_MESSAGES, false);
                quiet.clearLogs();
                loud.clearLogs();
                a.clearLogs();
                killQuietly(e2e, loud);
                // The game's own line: "<name> died", or "<name> was slain by Zombie" when a mob hit them just before.
                Component screen = deaths.lines.get(loudName);
                e2e.expect(screen != null && screen.color() != null && screen.color().value() == 0xAAAAAA, "shown in gray: " + screen);
                String died = PlainTextComponentSerializer.plainText().serialize(screen);
                e2e.expect(died.startsWith(loudName + " "), "the game's message names the player: " + died);
                e2e.eventually(() -> a.chatContains(died), "others see '" + died + "': " + a.chat());
                e2e.expect(loud.chatContains(died), "the player sees their own death");
                e2e.expect(!quiet.chatContains(died), "a player who turned death messages off does not: " + quiet.chat());
                e2e.expect(kills(e2e, attackerName, loudName).isEmpty(), "no kill logged without a killer");

                e2e.step("players who turned them off still see their own death");
                killQuietly(e2e, quiet);
                Component own = deaths.lines.get(quietName);
                e2e.expect(own != null, "the death screen got the restyled line");
                String ownLine = PlainTextComponentSerializer.plainText().serialize(own);
                e2e.eventually(() -> quiet.chatContains(ownLine), "own death '" + ownLine + "': " + quiet.chat());
            }
        });
    }

    static void combatLog(E2E e2e) throws Exception {
        withConfig(e2e, COMBAT, Map.of("same-ip: true", "same-ip: false"), x -> {
            String hunterName = e2e.name("LogHunter");
            String loggerName = e2e.name("Logger");
            String sponsorName = e2e.name("LogSponsor");
            Bot hunter = e2e.bot(hunterName);
            Bot logger = e2e.bot(loggerName);
            Bot sponsor = e2e.bot(sponsorName);
            e2e.console("eco give " + sponsorName + " 100k");
            e2e.console("give " + loggerName + " diamond 3");
            e2e.eventually(() -> holds(e2e, loggerName, Material.DIAMOND, 3), "the logger carries diamonds");
            e2e.sleep(JOIN_PROTECTION_MILLIS);

            e2e.step("a bounty on the logger");
            long escrowBefore = escrow(e2e);
            sponsor.command("bounty " + loggerName + " 5k");
            e2e.eventually(() -> bounty(e2e, loggerName) == 5_000, "a $5,000 bounty");
            long hunterMoney = money(e2e, hunterName);

            e2e.step("leaving in combat kills the logger and credits the hunter");
            hit(e2e, hunter, loggerName);
            Location at = e2e.onPlayer(loggerName, () -> e2e.player(loggerName).getLocation().clone());
            hunter.clearLogs();
            logger.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(loggerName) == null, "the logger left");
            String announce = loggerName + " logged out in combat. " + hunterName + " gets the kill.";
            e2e.eventually(() -> hunter.chatContains(announce), "announced: " + hunter.chat());
            e2e.eventually(() -> kills(e2e, hunterName, loggerName).equals(List.of("counted")), "the kill counts");
            e2e.eventually(() -> hunter.chatContains("You claimed $4,500 for killing " + loggerName + "."), "bounty claimed: " + hunter.chat());
            e2e.eventually(() -> money(e2e, hunterName) == hunterMoney + 4_500, "paid $4,500 after tax");
            e2e.expect(escrow(e2e) == escrowBefore, "the escrow gave the bounty out");
            e2e.eventually(() -> e2e.onPlayer(hunterName, () -> {
                Player player = e2e.player(hunterName);
                if (player.getInventory().contains(Material.DIAMOND)) {
                    return true;
                }
                for (Entity entity : player.getWorld().getNearbyEntities(at, 8, 8, 8)) {
                    if (entity instanceof Item item && item.getItemStack().getType() == Material.DIAMOND) {
                        return true;
                    }
                }
                return false;
            }), "the logger's diamonds dropped where they stood");
            UUID loggerId = known(e2e, loggerName);
            e2e.eventually(() -> !e2e.services().audit().recent("combat.log", loggerId.toString(), 5).join().isEmpty(),
                "the combat log is in the audit log");

            e2e.step("the logger comes back empty-handed and out of combat");
            e2e.bot(loggerName);
            e2e.eventually(() -> e2e.onPlayer(loggerName, () -> {
                Player player = e2e.player(loggerName);
                return !player.isDead() && player.getHealth() > 0 && !player.getInventory().contains(Material.DIAMOND);
            }), "respawned without the diamonds");
            e2e.expect(!tagged(e2e, loggerName), "not in combat after coming back");
        });
    }

    static void combatLogNone(E2E e2e) throws Exception {
        withConfig(e2e, COMBAT, Map.of("punishment: kill", "punishment: none"), x -> {
            String hunterName = e2e.name("NoneHunt");
            String loggerName = e2e.name("NoneLog");
            Bot hunter = e2e.bot(hunterName);
            Bot logger = e2e.bot(loggerName);
            e2e.console("give " + loggerName + " diamond 2");
            e2e.eventually(() -> holds(e2e, loggerName, Material.DIAMOND, 2), "the logger carries diamonds");
            e2e.sleep(JOIN_PROTECTION_MILLIS);
            e2e.step("without punishment only the announcement is made");
            hit(e2e, hunter, loggerName);
            hunter.clearLogs();
            logger.quit();
            e2e.eventually(() -> hunter.chatContains(loggerName + " logged out in combat."), "announced: " + hunter.chat());
            e2e.sleep(1_000);
            e2e.expect(!hunter.chatContains("gets the kill"), "nobody gets a kill");
            e2e.expect(kills(e2e, hunterName, loggerName).isEmpty(), "no kill logged");
            e2e.step("the logger keeps everything");
            e2e.bot(loggerName);
            e2e.eventually(() -> e2e.onPlayer(loggerName, () -> {
                Player player = e2e.player(loggerName);
                return !player.isDead() && player.getInventory().contains(Material.DIAMOND, 2);
            }), "alive with the diamonds");
            e2e.expect(!tagged(e2e, loggerName), "the tag ended when they left");
        });
    }

    static void pearl(E2E e2e) throws Exception {
        withConfig(e2e, COMBAT, Map.of("block-ender-pearls: false", "block-ender-pearls: true"), x -> {
            String attackerName = e2e.name("PearlA");
            String throwerName = e2e.name("PearlB");
            Bot a = e2e.bot(attackerName);
            Bot b = e2e.bot(throwerName);
            e2e.console("give " + throwerName + " ender_pearl 4");
            e2e.eventually(() -> holds(e2e, throwerName, Material.ENDER_PEARL, 4), "the thrower holds pearls");
            e2e.sleep(JOIN_PROTECTION_MILLIS);
            e2e.step("pearls are refused in combat and not used up");
            hit(e2e, a, throwerName);
            b.clearLogs();
            b.useItem();
            e2e.eventually(() -> b.actionBarContains("can't throw ender pearls in combat"), "refused: " + b.actionBar());
            e2e.sleep(500);
            e2e.expect(holds(e2e, throwerName, Material.ENDER_PEARL, 4), "still 4 pearls");
        });
    }

    static void streak(E2E e2e) throws Exception {
        Map<String, String> changes = Map.of(
            "same-ip: true", "same-ip: false",
            "announce-at: [5, 10, 15, 20, 25, 30, 40, 50, 75, 100]", "announce-at: [2]",
            "announce-ended-from: 5", "announce-ended-from: 2");
        withConfig(e2e, COMBAT, changes, x -> {
            String hunterName = e2e.name("StrHunter");
            String firstName = e2e.name("StrFirst");
            String secondName = e2e.name("StrSecond");
            String quietName = e2e.name("StrQuiet");
            Bot hunter = e2e.bot(hunterName);
            Bot first = e2e.bot(firstName);
            Bot second = e2e.bot(secondName);
            Bot quiet = e2e.bot(quietName);
            e2e.services().settings().set(known(e2e, quietName), CombatFeature.DEATH_MESSAGES, false);
            e2e.sleep(JOIN_PROTECTION_MILLIS);

            e2e.step("two counted kills in a row: a streak of 2 is announced");
            killWithHit(e2e, hunter, first);
            e2e.expect(stat(e2e, hunterName, "streak") == 1, "streak 1 after the first kill");
            second.clearLogs();
            quiet.clearLogs();
            killWithHit(e2e, hunter, second);
            String reached = hunterName + " is on a kill streak of 2.";
            e2e.eventually(() -> first.chatContains(reached), "announced: " + first.chat());
            e2e.expect(hunter.chatContains(reached), "the hunter hears it");
            e2e.expect(stat(e2e, hunterName, "streak") == 2, "the stats keep the streak");
            e2e.sleep(500);
            e2e.expect(!quiet.chatContains(reached), "players who turned death messages off don't: " + quiet.chat());

            e2e.step("killing the hunter ends the streak, and that is announced too");
            second.clearLogs();
            killWithHit(e2e, first, hunter);
            String ended = firstName + " ended " + hunterName + "'s kill streak of 2.";
            e2e.eventually(() -> second.chatContains(ended), "announced: " + second.chat());
            e2e.expect(stat(e2e, hunterName, "streak") == 0, "the hunter's streak is over");
            e2e.expect(stat(e2e, hunterName, "best_streak") == 2, "and their best streak is 2");
            e2e.expect(stat(e2e, firstName, "streak") == 1, "the avenger starts a streak");
        });
    }

    static void team(E2E e2e) {
        String ownerName = e2e.name("TmOwner");
        String mateName = e2e.name("TmMate");
        String team = e2e.name("Tm");
        Bot owner = e2e.bot(ownerName);
        Bot mate = e2e.bot(mateName);
        e2e.console("eco set " + ownerName + " 50k");
        e2e.eventually(() -> money(e2e, ownerName) == 50_000, "the owner can pay for a team");
        owner.command("team create " + team);
        e2e.dialog(owner, "Start a team");
        e2e.click(owner, "Start team");
        e2e.eventually(() -> team.equals(placeholder(e2e, ownerName, "team_name")), ownerName + " owns " + team);
        List<String> added = e2e.consoleOutput("team admin add " + team + " " + mateName);
        e2e.expect(added.stream().anyMatch(line -> line.contains("Added")), "the mate was added: " + added);
        e2e.eventually(() -> team.equals(placeholder(e2e, mateName, "team_name")), mateName + " is in " + team);
        e2e.sleep(JOIN_PROTECTION_MILLIS);

        e2e.step("without friendly fire, teammates can't hurt or tag each other");
        together(e2e, ownerName, mateName);
        for (int i = 0; i < 4; i++) {
            owner.attack(e2e.player(mateName).getEntityId());
            e2e.sleep(300);
        }
        e2e.expect(!tagged(e2e, mateName) && !tagged(e2e, ownerName), "nobody is in combat");

        e2e.step("with friendly fire on, a kill between teammates is logged but never counted");
        owner.command("team friendlyfire on");
        e2e.sleep(800);
        long killsBefore = stat(e2e, ownerName, "kills");
        killWithHit(e2e, owner, mate);
        e2e.eventually(() -> kills(e2e, ownerName, mateName).equals(List.of("same_team")),
            "not counted, same team: " + kills(e2e, ownerName, mateName));
        e2e.expect(stat(e2e, ownerName, "kills") == killsBefore, "no kill for the owner");
        e2e.expect(stat(e2e, mateName, "deaths") >= 1, "the mate's death counts");
        String log = String.join("\n", output(e2e, "combat kills " + mateName, 1_000));
        e2e.expect(log.contains("not counted: same team"), "the staff kill log names the reason:\n" + log);
    }

    static void vanish(E2E e2e) {
        String modName = e2e.name("VanCombat");
        String playerName = e2e.name("VanPlayer");
        String watcherName = e2e.name("VanWatch");
        Bot mod = e2e.bot(modName);
        e2e.console("op " + modName);
        Bot player = e2e.bot(playerName);
        Bot watcher = e2e.bot(watcherName);
        e2e.sleep(JOIN_PROTECTION_MILLIS);
        try {
            e2e.step("a vanished moderator's hits put nobody in combat");
            mod.clearLogs();
            mod.command("vanish");
            e2e.eventually(() -> mod.actionBarContains("You are vanished") || mod.chatContains("You are vanished"),
                "vanished: " + mod.actionBar() + " " + mod.chat());
            together(e2e, modName, playerName);
            int before = player.deaths();
            for (int i = 0; i < 4; i++) {
                mod.attack(e2e.player(playerName).getEntityId());
                e2e.sleep(300);
            }
            e2e.expect(player.deaths() == before, "the player is still alive");
            e2e.expect(!tagged(e2e, playerName) && !tagged(e2e, modName), "nobody is in combat");

            e2e.step("a vanished moderator's death is not announced to players who can't see them");
            watcher.clearLogs();
            mod.clearLogs();
            try (Deaths deaths = new Deaths()) {
                killQuietly(e2e, mod);
                Component screen = deaths.lines.get(modName);
                e2e.expect(screen != null, "the death screen got the restyled line");
                String line = PlainTextComponentSerializer.plainText().serialize(screen);
                e2e.eventually(() -> mod.chatContains(line), "the moderator sees their own death '" + line + "': " + mod.chat());
            }
            e2e.sleep(500);
            e2e.expect(watcher.chat().stream().noneMatch(text -> text.contains(modName)), "the watcher never reads the name: " + watcher.chat());
        } finally {
            if (e2e.services().directory().uuid(modName).isPresent() && Bukkit.getPlayerExact(modName) != null) {
                mod.command("vanish");
                e2e.sleep(500);
            }
            e2e.console("deop " + modName);
        }
    }

    /**
     * The repeated-pair rule remembers counted kills across restarts. Uses the same two names on every run: when the
     * stored log has a counted kill of this pair less than 10 minutes old (an earlier run, possibly before a restart),
     * the new kill must not count; otherwise it must. Run it, restart the server, run it again.
     */
    static void pairMemory(E2E e2e) throws Exception {
        withConfig(e2e, COMBAT, Map.of("same-ip: true", "same-ip: false"), x -> {
            String killerName = "PairMemKiller";
            String victimName = "PairMemVictim";
            Bot k = e2e.bot(killerName);
            Bot v = e2e.bot(victimName);
            e2e.sleep(JOIN_PROTECTION_MILLIS);
            UUID killer = known(e2e, killerName);
            UUID victim = known(e2e, victimName);
            long lastCounted = e2e.services().database().read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT MAX(ts) FROM kills WHERE killer = ? AND victim = ? AND counted = 1")) {
                    ps.setString(1, killer.toString());
                    ps.setString(2, victim.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            }).get(5, TimeUnit.SECONDS);
            long age = System.currentTimeMillis() - lastCounted;
            boolean remembered = lastCounted > 0 && age < 9 * 60_000L;
            boolean stale = lastCounted > 0 && !remembered && age < 11 * 60_000L;
            if (stale) {
                e2e.log("the last counted kill of the pair is " + age / 1000 + "s old, too close to the cooldown to tell; skipped");
                return;
            }
            e2e.step(remembered ? "a counted kill " + age / 1000 + "s ago is remembered: this one does not count"
                : "no recent counted kill: this one counts");
            int rowsBefore = kills(e2e, killerName, victimName).size();
            killWithHit(e2e, k, v);
            e2e.eventually(() -> kills(e2e, killerName, victimName).size() == rowsBefore + 1, "the kill was logged");
            List<String> rows = kills(e2e, killerName, victimName);
            String expected = remembered ? "repeated_pair" : "counted";
            e2e.expect(rows.getLast().equals(expected), "the kill is " + expected + ": " + rows);
        });
    }

    /** Unfriend, kill, re-friend: a friendship that ended within the friends window still blocks the credit. */
    static void exFriends(E2E e2e) throws Exception {
        withConfig(e2e, COMBAT, Map.of("same-ip: true", "same-ip: false"), x -> {
            String killerName = e2e.name("ExKiller");
            String victimName = e2e.name("ExVictim");
            Bot killer = e2e.bot(killerName);
            Bot victim = e2e.bot(victimName);
            UUID killerId = known(e2e, killerName);
            UUID victimId = known(e2e, victimName);
            var friends = e2e.feature(FriendsFeature.class).lookup();

            e2e.step("they are friends, then the killer removes the victim right before the fight");
            List<String> made = output(e2e, "sift friends add " + killerName + " " + victimName, 500);
            e2e.eventually(() -> friends.friends(killerId, victimId), "friends: " + made);
            killer.command("friend remove " + victimName);
            e2e.dialog(killer, "Remove friend");
            e2e.click(killer, "Remove");
            e2e.eventually(() -> !friends.friends(killerId, victimId), "no longer friends");
            e2e.sleep(JOIN_PROTECTION_MILLIS);

            e2e.step("the kill doesn't count: they were friends a moment ago");
            long killsBefore = stat(e2e, killerName, "kills");
            killWithHit(e2e, killer, victim);
            e2e.eventually(() -> kills(e2e, killerName, victimName).equals(List.of("friends")),
                "not counted, recently friends: " + kills(e2e, killerName, victimName));
            e2e.expect(stat(e2e, killerName, "kills") == killsBefore, "no kill for the killer");
        });
    }

    // ------------------------------------------------------------------ bounties

    static void bountyPlace(E2E e2e) {
        String sponsorName = e2e.name("Sponsor");
        String targetName = e2e.name("Target");
        String watcherName = e2e.name("Watcher");
        Bot s = e2e.bot(sponsorName);
        Bot t = e2e.bot(targetName);
        Bot w = e2e.bot(watcherName);
        e2e.console("eco set " + sponsorName + " 1m");
        e2e.eventually(() -> money(e2e, sponsorName) == 1_000_000, "the sponsor has $1,000,000");
        long escrow0 = escrow(e2e);

        e2e.step("too small, or on yourself: refused, nothing moves");
        s.command("bounty " + targetName + " 500");
        e2e.eventually(() -> s.actionBarContains("The smallest bounty is $1,000."), "minimum: " + s.actionBar());
        s.command("bounty " + sponsorName + " 5k");
        e2e.eventually(() -> s.actionBarContains("can't put a bounty on yourself"), "self: " + s.actionBar());
        s.command("bounty " + targetName + " 2m");
        e2e.eventually(() -> s.actionBarContains("You need $2m for that."), "not enough money: " + s.actionBar());
        e2e.expect(money(e2e, sponsorName) == 1_000_000 && escrow(e2e) == escrow0, "nothing moved");

        e2e.step("a bounty, its receipt and the target's notice");
        s.clearLogs();
        t.clearLogs();
        w.clearLogs();
        s.command("bounty " + targetName + " 5k");
        e2e.eventually(() -> s.chatContains("You put $5,000 on " + targetName + ". Their bounty is now $5,000."), "receipt: " + s.chat());
        e2e.eventually(() -> t.chatContains("Someone put $5,000 on your head. Your bounty is now $5,000."), "notice: " + t.chat());
        e2e.expect(money(e2e, sponsorName) == 995_000 && escrow(e2e) == escrow0 + 5_000, "the money is in the escrow");
        e2e.sleep(500);
        e2e.expect(!w.chatContains("was put on"), "small bounties are not announced");

        e2e.step("the cooldown stops spam");
        s.clearLogs();
        s.command("bounty " + targetName + " 5k");
        e2e.eventually(() -> s.actionBarContains("Wait"), "cooldown: " + s.actionBar());
        e2e.expect(bounty(e2e, targetName) == 5_000, "unchanged");

        e2e.step("a big bounty is confirmed first and announced");
        e2e.sleep(BOUNTY_COOLDOWN_MILLIS);
        s.clearLogs();
        s.command("bounty " + targetName + " 200k");
        Bot.SeenDialog confirm = e2e.dialog(s, "Confirm bounty");
        e2e.expect(confirm.bodyText().contains("Put $200,000 on " + targetName + "?"), "the exact amount: " + confirm.body());
        e2e.expect(bounty(e2e, targetName) == 5_000, "nothing before confirming");
        e2e.click(s, "Place bounty");
        e2e.eventually(() -> bounty(e2e, targetName) == 205_000, "stacked to $205,000");
        e2e.eventually(() -> w.chatContains("$200,000 was put on " + targetName + ". Their bounty is now $205,000."), "announced: " + w.chat());
        e2e.expect(!s.chatContains("was put on"), "the sponsor gets the receipt, not the announcement");

        e2e.step("cancelling a confirmation moves nothing");
        e2e.sleep(BOUNTY_COOLDOWN_MILLIS);
        s.command("bounty " + targetName + " 150k");
        e2e.dialog(s, "Confirm bounty");
        e2e.click(s, "Cancel");
        e2e.sleep(500);
        e2e.expect(bounty(e2e, targetName) == 205_000 && money(e2e, sponsorName) == 795_000, "unchanged after cancelling");

        e2e.step("the list and a target's details");
        s.command("bounties");
        Bot.SeenDialog list = e2e.dialog(s, "Bounties");
        e2e.expect(list.bodyText().contains(targetName + " $205,000 from 1 player"), "the list: " + list.body());
        e2e.click(s, targetName);
        Bot.SeenDialog details = e2e.dialog(s, "Bounty on " + targetName);
        e2e.expect(details.bodyText().contains("$205,000") && details.bodyText().contains("Your part $205,000")
            && details.bodyText().contains("Put up by 1 player"), "details: " + details.body());

        e2e.step("the form checks what was typed and keeps it");
        e2e.click(s, "Add to this bounty");
        e2e.dialog(s, "Place a bounty");
        e2e.click(s, "Submit", Map.of("player", targetName, "amount", "abc"));
        e2e.eventually(() -> s.dialog() != null && s.dialog().bodyText().contains("is not an amount"), "amount error: " + s.dialog());
        e2e.click(s, "Submit", Map.of("player", targetName, "amount", "500"));
        e2e.eventually(() -> s.dialog() != null && s.dialog().bodyText().contains("The smallest bounty is $1,000."), "minimum: " + s.dialog());
        e2e.click(s, "Submit", Map.of("player", e2e.name("Nobody"), "amount", "5k"));
        e2e.eventually(() -> s.dialog() != null && s.dialog().bodyText().contains("has played here"), "unknown player: " + s.dialog());
        e2e.sleep(BOUNTY_COOLDOWN_MILLIS);
        int cleared = s.dialogsCleared();
        e2e.click(s, "Submit", Map.of("player", targetName, "amount", "2k"));
        Bot.SeenDialog after = e2e.dialog(s, "Bounty on " + targetName);
        e2e.eventually(() -> bounty(e2e, targetName) == 207_000, "added through the form");
        e2e.expect(after.bodyText().contains("$207,000"), "the details show the new total: " + after.body());
        e2e.expect(escrow(e2e) == escrow0 + 207_000 && money(e2e, sponsorName) == 793_000, "every dollar accounted for");
        e2e.expect(s.dialogsCleared() == cleared, "the details replaced the form without a close in between");

        e2e.step("submitting again during the cooldown says so in the form and keeps what was typed");
        e2e.click(s, "Add to this bounty");
        e2e.dialog(s, "Place a bounty");
        e2e.click(s, "Submit", Map.of("player", targetName, "amount", "3k"));
        e2e.eventually(() -> s.dialog() != null && s.dialog().bodyText().contains("before doing that again"), "cooldown: " + s.dialog());
        e2e.expect("3k".equals(s.dialog().initial("amount")), "the typed amount: " + s.dialog().initial());
        e2e.expect(s.dialogsCleared() == cleared, "the form stayed");
        e2e.expect(bounty(e2e, targetName) == 207_000, "nothing added during the cooldown");

        e2e.step("the target sees their own bounty");
        t.command("bounties");
        Bot.SeenDialog own = e2e.dialog(t, "Bounties");
        e2e.expect(own.bodyText().contains("The bounty on you is $207,000."), "own bounty line: " + own.body());
        e2e.expect(own.button("Place a bounty") != null, "a place button");
    }

    static void bountyClaim(E2E e2e) throws Exception {
        String sponsorName = e2e.name("ClSponsor");
        String targetName = e2e.name("ClTarget");
        String hunterName = e2e.name("ClHunter");
        Bot s = e2e.bot(sponsorName);
        Bot t = e2e.bot(targetName);
        Bot h = e2e.bot(hunterName);
        e2e.console("eco set " + sponsorName + " 100k");
        e2e.console("eco set " + hunterName + " 100k");
        e2e.eventually(() -> money(e2e, sponsorName) == 100_000 && money(e2e, hunterName) == 100_000, "both funded");
        long escrow0 = escrow(e2e);
        s.command("bounty " + targetName + " 10k");
        h.command("bounty " + targetName + " 20k");
        e2e.eventually(() -> bounty(e2e, targetName) == 30_000, "two sponsors, $30,000");
        e2e.sleep(JOIN_PROTECTION_MILLIS);

        withConfig(e2e, COMBAT, Map.of("same-ip: true", "same-ip: false"), x -> {
            e2e.step("a sponsor who kills the target claims only the others' part");
            s.clearLogs();
            h.clearLogs();
            killWithHit(e2e, s, t);
            e2e.eventually(() -> s.chatContains("You claimed $18,000 for killing " + targetName + ". $2,000 went to tax."), "claim: " + s.chat());
            e2e.eventually(() -> s.chatContains("You can't claim the part of " + targetName + "'s bounty that you put up yourself."),
                "own part explained: " + s.chat());
            e2e.eventually(() -> h.chatContains("Your bounty on " + targetName + " was claimed by " + sponsorName + "."), "sponsor told: " + h.chat());
            e2e.eventually(() -> h.chatContains(sponsorName + " claimed the $20,000 bounty on " + targetName + "."), "announced: " + h.chat());
            e2e.expect(money(e2e, sponsorName) == 90_000 + 18_000, "paid: " + money(e2e, sponsorName));
            e2e.expect(bounty(e2e, targetName) == 10_000, "the sponsor's own $10,000 stays");

            e2e.step("someone else claims the rest");
            killWithHit(e2e, h, t);
            e2e.eventually(() -> money(e2e, hunterName) == 80_000 + 9_000, "the hunter got $9,000: " + money(e2e, hunterName));
            e2e.eventually(() -> bounty(e2e, targetName) == 0, "nothing left");
            e2e.expect(escrow(e2e) == escrow0, "the escrow is back where it started");
            e2e.expect(kills(e2e, sponsorName, targetName).equals(List.of("counted"))
                && kills(e2e, hunterName, targetName).equals(List.of("counted")), "both kills counted");
        });

        e2e.step("kills that do not count claim nothing (same IP, the default)");
        String altName = e2e.name("ClAlt");
        Bot alt = e2e.bot(altName);
        s.command("bounty " + targetName + " 5k");
        e2e.eventually(() -> bounty(e2e, targetName) == 5_000, "a new bounty");
        e2e.sleep(JOIN_PROTECTION_MILLIS);
        killWithHit(e2e, alt, t);
        e2e.eventually(() -> kills(e2e, altName, targetName).equals(List.of("same_ip")), "not counted: " + kills(e2e, altName, targetName));
        e2e.expect(bounty(e2e, targetName) == 5_000, "the bounty stays");
        e2e.expect(money(e2e, altName) == 0, "the alt got nothing");
    }

    static void bountyAdmin(E2E e2e) {
        String sponsorName = e2e.name("AdSponsor");
        String targetName = e2e.name("AdTarget");
        Bot s = e2e.bot(sponsorName);
        e2e.bot(targetName);
        e2e.console("eco set " + sponsorName + " 100k");
        e2e.eventually(() -> money(e2e, sponsorName) == 100_000, "funded");
        s.command("bounty " + targetName + " 7k");
        e2e.eventually(() -> bounty(e2e, targetName) == 7_000, "a $7,000 bounty");

        e2e.step("staff see every part with its sponsor");
        String info = String.join("\n", output(e2e, "bountyadmin info " + targetName, 300));
        e2e.expect(info.contains("Bounty on " + targetName + ": $7,000") && info.contains("$7,000 from " + sponsorName), "info:\n" + info);
        String summary = String.join("\n", output(e2e, "bountyadmin", 600));
        e2e.expect(summary.contains("have a bounty") && summary.contains("Escrow"), "summary:\n" + summary);
        String console = String.join("\n", output(e2e, "bounties " + targetName, 300));
        e2e.expect(console.contains("$7,000 from " + sponsorName), "the console's /bounties <player>:\n" + console);

        e2e.step("staff removal refunds the sponsor");
        s.clearLogs();
        long escrow0 = escrow(e2e);
        String removed = String.join("\n", output(e2e, "bountyadmin remove " + targetName, 300));
        e2e.expect(removed.contains("Removed the bounty on " + targetName + " and refunded $7,000"), "remove:\n" + removed);
        e2e.eventually(() -> s.chatContains("Your $7,000 bounty on " + targetName + " was removed by staff. You got it back."), "told: " + s.chat());
        e2e.expect(money(e2e, sponsorName) == 100_000 && bounty(e2e, targetName) == 0 && escrow(e2e) == escrow0 - 7_000, "refunded");
        UUID targetId = known(e2e, targetName);
        e2e.eventually(() -> !e2e.services().audit().recent("bounties.remove", targetId.toString(), 5).join().isEmpty(),
            "the removal is audited");
        e2e.expect(String.join(" ", output(e2e, "bountyadmin remove " + targetName, 300)).contains("has no bounty"), "nothing left to remove");
        e2e.expect(String.join(" ", output(e2e, "bountyadmin expire", 300)).contains("No bounty has run out."), "nothing expired");

        e2e.step("the self-test agrees: escrow equals the active bounties");
        String selftest = String.join("\n", output(e2e, "sift selftest", 3_000));
        for (String check : List.of("bounty escrow equals active bounties", "stored bounty escrow equals stored active bounties",
            "claim tax math", "anti-farm rules", "combat timer is running", "only online players are in combat", "kill log is readable",
            "ledger invariants")) {
            e2e.expect(selftest.lines().anyMatch(l -> l.startsWith("pass") && l.contains(check)), "'" + check + "' passes:\n" + selftest);
        }
        e2e.expect(selftest.lines().noneMatch(l -> l.startsWith("fail combat") || l.startsWith("fail bounties")), "no combat or bounty failures:\n" + selftest);
    }
}
