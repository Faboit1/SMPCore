package net.siftvanilla.siftcore.feature.kits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.api.economy.TransactionStatus;
import net.siftvanilla.siftcore.api.event.KitClaimEvent;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.CrateKeys;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.economy.LedgerTx;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Claiming and giving kits. A claim is one economy transaction: the cooldown is checked under the economy lock, the
 * claim time is set and stored, and the kit's items go into the claim box with it, so a claim can never be paid twice
 * and its items can never be lost. Once stored, the items that fit move into the inventory ({@link KitHandouts}) and
 * the kit's crate keys are given.
 */
final class KitService {

    /** What starting one claim led to. */
    private sealed interface Attempt permits Refusal, Started {
    }

    /** Why a claim did not start, as a message with its arguments. */
    record Refusal(MessageKey key, List<Arg> args) implements Attempt {
        Refusal(MessageKey key, Arg... args) {
            this(key, List.of(args));
        }

        Arg[] argArray() {
            return this.args.toArray(Arg[]::new);
        }
    }

    /** A claim that is stored once {@code committed} completes. */
    private record Started(Kit kit, String ref, CompletableFuture<Void> committed) implements Attempt {
    }

    private final Services services;
    private final Setting<KitsSettings> settings;
    private final KitClaims claims;
    private final KitItems items;
    private final KitHandouts handouts;
    private final CombatStatus combat;
    private final CrateKeys crateKeys;
    private final KitText text;
    private final Logger logger;
    private volatile KitReminders reminders;

    KitService(Services services, Setting<KitsSettings> settings, KitClaims claims, KitItems items, KitHandouts handouts,
               CombatStatus combat, CrateKeys crateKeys, KitText text) {
        this.services = services;
        this.settings = settings;
        this.claims = claims;
        this.items = items;
        this.handouts = handouts;
        this.combat = combat;
        this.crateKeys = crateKeys;
        this.text = text;
        this.logger = services.plugin().getLogger();
    }

    void reminders(KitReminders reminders) {
        this.reminders = reminders;
    }

    KitClaims claims() {
        return this.claims;
    }

    KitHandouts handouts() {
        return this.handouts;
    }

    KitText text() {
        return this.text;
    }

    KitItems items() {
        return this.items;
    }

    KitsSettings settings() {
        return this.settings.get();
    }

    // ------------------------------------------------------------------ what a player sees

    boolean permitted(Player player, Kit kit) {
        return player.hasPermission(kit.permission());
    }

    /** The kits a player sees in /kits: the ones they may claim, plus locked ones when the config shows them. */
    List<Kit> visible(Player player) {
        KitsSettings current = this.settings.get();
        List<Kit> visible = new ArrayList<>();
        for (Kit kit : current.kits()) {
            if (current.showLocked() || permitted(player, kit)) {
                visible.add(kit);
            }
        }
        return visible;
    }

    /** The kits the player may claim right now. */
    List<Kit> ready(Player player) {
        List<Kit> ready = new ArrayList<>();
        for (Kit kit : this.settings.get().kits()) {
            if (permitted(player, kit) && this.claims.status(player.getUniqueId(), kit).ready()) {
                ready.add(kit);
            }
        }
        return ready;
    }

    KitStatus status(Player player, Kit kit) {
        return this.claims.status(player.getUniqueId(), kit);
    }

    /** Kit items of the player still waiting in the claim box. */
    int waitingStacks(UUID player) {
        return this.handouts.waiting(player, null).size();
    }

    // ------------------------------------------------------------------ claiming

    /** Claims a kit by the name a player typed. Call on the player's thread. Returns null when the claim started. */
    Refusal claim(Player player, String input) {
        Kit kit = this.settings.get().kit(input);
        if (kit == null) {
            return new Refusal(KitsMessages.UNKNOWN, Arg.text("input", input));
        }
        return claim(player, kit);
    }

    /**
     * Claims a kit. Call on the player's thread. Returns null when the claim started. The kit is looked up again in the
     * current settings, so a dialog opened before a reload can't claim a kit that was removed or changed since.
     */
    Refusal claim(Player player, Kit shown) {
        Kit kit = this.settings.get().kit(shown.id());
        if (kit == null) {
            return new Refusal(KitsMessages.UNKNOWN, Arg.text("input", shown.id()));
        }
        Refusal combatRefusal = combatRefusal(player);
        if (combatRefusal != null) {
            return combatRefusal;
        }
        return switch (start(player, kit)) {
            case Refusal refusal -> refusal;
            case Started claim -> {
                finish(player, List.of(claim));
                yield null;
            }
        };
    }

    /** Claims every kit that is ready. Call on the player's thread. Returns null when at least one claim started. */
    Refusal claimReady(Player player) {
        Refusal combatRefusal = combatRefusal(player);
        if (combatRefusal != null) {
            return combatRefusal;
        }
        List<Started> started = new ArrayList<>();
        Refusal first = null;
        for (Kit kit : ready(player)) {
            switch (start(player, kit)) {
                case Started claim -> started.add(claim);
                case Refusal refusal -> first = first == null ? refusal : first;
            }
        }
        if (started.isEmpty()) {
            return first != null ? first : new Refusal(KitsMessages.NONE_READY);
        }
        finish(player, started);
        return null;
    }

    private Refusal combatRefusal(Player player) {
        if (this.settings.get().blockInCombat() && this.combat.tagged(player.getUniqueId())) {
            return new Refusal(KitsMessages.IN_COMBAT, Arg.time("time", KitText.roundUp(this.combat.remaining(player.getUniqueId()))));
        }
        return null;
    }

    /** Checks and runs one claim's transaction. */
    private Attempt start(Player player, Kit kit) {
        UUID uuid = player.getUniqueId();
        if (!permitted(player, kit)) {
            return new Refusal(KitsMessages.LOCKED, Arg.text("name", kit.name()));
        }
        Refusal notReady = notReady(uuid, kit);
        if (notReady != null) {
            return notReady;
        }
        if (!new KitClaimEvent(player, kit.id(), false).callEvent()) {
            return new Refusal(KitsMessages.CANCELLED, Arg.text("name", kit.name()));
        }
        String ref = newRef(kit);
        LedgerTx.Builder tx = LedgerTx.builder().actor(uuid).silent().note("kit " + kit.id());
        this.claims.claim(tx, uuid, kit);
        for (ItemStack item : this.items.build(kit)) {
            this.services.deliveries().add(tx, uuid, KitHandouts.SOURCE, ref, item);
        }
        TransactionResult result;
        try {
            result = this.services.ledger().execute(tx.build());
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Claiming the " + kit.id() + " kit for " + player.getName() + " failed", e);
            return new Refusal(KitsMessages.FAILED);
        }
        if (result.success()) {
            return new Started(kit, ref, result.committed());
        }
        if (result.status() == TransactionStatus.REJECTED && KitClaims.NOT_READY.equals(result.reason())) {
            Refusal raced = notReady(uuid, kit);
            if (raced != null) {
                return raced;
            }
        }
        return new Refusal(KitsMessages.FAILED);
    }

    private Refusal notReady(UUID player, Kit kit) {
        return switch (this.claims.status(player, kit)) {
            case KitStatus.Ready ready -> null;
            case KitStatus.Waiting waiting -> new Refusal(KitsMessages.NOT_READY, Arg.text("name", kit.name()),
                Arg.time("time", waiting.shown()));
            case KitStatus.Claimed claimed -> new Refusal(KitsMessages.ALREADY_CLAIMED, Arg.text("name", kit.name()));
        };
    }

    private static String newRef(Kit kit) {
        return "kit:" + kit.id() + ":" + Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
    }

    /**
     * Waits until the claims are stored, then hands their items over on the player's thread, gives the crate keys and
     * tells the player. Claims that could not be stored were undone by the ledger (cooldown and items both).
     */
    private void finish(Player player, List<Started> started) {
        UUID uuid = player.getUniqueId();
        List<CompletableFuture<Started>> each = new ArrayList<>(started.size());
        for (Started claim : started) {
            each.add(claim.committed().handle((ok, error) -> {
                if (error != null) {
                    this.logger.log(Level.SEVERE, "The " + claim.kit().id() + " kit claim of " + player.getName()
                        + " could not be stored and was undone", error);
                    return null;
                }
                return claim;
            }));
        }
        CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).thenRun(() -> {
            List<Started> stored = new ArrayList<>();
            for (CompletableFuture<Started> future : each) {
                Started claim = future.join();
                if (claim != null) {
                    stored.add(claim);
                }
            }
            if (stored.size() < started.size()) {
                this.services.messenger().send(player, KitsMessages.FAILED);
            }
            if (stored.isEmpty()) {
                return;
            }
            for (Started claim : stored) {
                this.services.audit().record(uuid.toString(), "kits.claim", uuid.toString(), "kit=" + claim.kit().id() + " ref=" + claim.ref());
            }
            Set<String> refs = new LinkedHashSet<>();
            for (Started claim : stored) {
                refs.add(claim.ref());
            }
            for (Started claim : stored) {
                giveKeys(uuid, player, claim.kit(), claim.ref());
            }
            try {
                // When the player left meanwhile the items simply stay in the claim box for /kits.
                this.services.scheduler().entity(player, () -> this.handouts.handOver(player, refs, outcome -> {
                    if (stored.size() == 1) {
                        String name = stored.getFirst().kit().name();
                        this.services.messenger().send(player, KitsMessages.CLAIMED, Arg.text("name", name));
                        if (outcome.left() > 0) {
                            this.services.messenger().send(player, KitsMessages.CLAIM_BOX, Arg.text("name", name));
                        }
                    } else {
                        this.services.messenger().send(player, KitsMessages.CLAIMED_MANY, Arg.number("count", stored.size()));
                        if (outcome.left() > 0) {
                            this.services.messenger().send(player, KitsMessages.CLAIM_BOX_MANY);
                        }
                    }
                    KitReminders current = this.reminders;
                    if (current != null) {
                        current.schedule(player);
                    }
                }), null);
            } catch (RuntimeException stopping) {
                // The scheduler refuses new work while the plugin stops; the items wait in the claim box.
            }
        }).exceptionally(error -> {
            this.logger.log(Level.WARNING, "Finishing kit claims of " + player.getName() + " failed; their items wait in the claim box", error);
            return null;
        });
    }

    /**
     * Gives a kit's crate keys once its claim is stored, and tells the player (when online) what they got. Each crate's
     * keys are one transaction of the crates feature; a refusal (the player holds the most keys there can be, the
     * crate was removed) is logged with the claim's reference so staff can make up for it.
     */
    private void giveKeys(UUID uuid, Player player, Kit kit, String ref) {
        if (kit.keys().isEmpty()) {
            return;
        }
        Map<String, Integer> given = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, Integer> failed = new java.util.concurrent.ConcurrentHashMap<>();
        List<CompletableFuture<Void>> stored = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : kit.keys().entrySet()) {
            String crate = entry.getKey();
            int amount = entry.getValue();
            TransactionResult result;
            try {
                result = this.crateKeys.give(uuid, crate, amount, "kit:" + kit.id(), null);
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "Giving " + amount + " " + crate + " keys from the " + kit.id() + " kit to " + uuid
                    + " failed (" + ref + ")", e);
                failed.put(crate, amount);
                continue;
            }
            if (!result.success()) {
                this.logger.warning("The " + kit.id() + " kit could not give " + amount + " " + crate + " keys to " + uuid + " ("
                    + result.status() + " " + result.reason() + ", " + ref + ")");
                failed.put(crate, amount);
                continue;
            }
            stored.add(result.committed().handle((ok, error) -> {
                if (error != null) {
                    this.logger.log(Level.SEVERE, "Crate keys from the " + kit.id() + " kit for " + uuid + " could not be stored ("
                        + ref + ")", error);
                    failed.put(crate, amount);
                } else {
                    given.put(crate, amount);
                }
                return null;
            }));
        }
        CompletableFuture.allOf(stored.toArray(CompletableFuture[]::new)).thenRun(() -> {
            if (player == null || !player.isOnline()) {
                return;
            }
            if (!given.isEmpty()) {
                this.services.messenger().send(player, KitsMessages.KEYS_GIVEN, Arg.text("name", kit.name()),
                    Arg.component("keys", this.text.keys(ordered(kit, given))));
            }
            if (!failed.isEmpty()) {
                this.services.messenger().send(player, KitsMessages.KEYS_FAILED, Arg.text("name", kit.name()),
                    Arg.component("keys", this.text.keys(ordered(kit, failed))));
            }
        });
    }

    /** The entries of {@code keys} in the kit's file order. */
    private static Map<String, Integer> ordered(Kit kit, Map<String, Integer> keys) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String crate : kit.keys().keySet()) {
            Integer amount = keys.get(crate);
            if (amount != null) {
                result.put(crate, amount);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ the claim box

    /** Collects every waiting kit item that fits. Call on the player's thread. */
    void collect(Player player) {
        if (this.handouts.waiting(player.getUniqueId(), null).isEmpty()) {
            this.services.messenger().send(player, KitsMessages.COLLECT_NONE);
            return;
        }
        this.handouts.handOver(player, null, outcome -> {
            if (outcome.handed() == 0) {
                this.services.messenger().send(player, outcome.failed() ? KitsMessages.FAILED : KitsMessages.COLLECT_NO_ROOM);
            } else if (outcome.left() > 0) {
                this.services.messenger().send(player, KitsMessages.COLLECTED_PARTLY);
            } else {
                this.services.messenger().send(player, KitsMessages.COLLECTED);
            }
        });
    }

    // ------------------------------------------------------------------ staff

    /**
     * Gives a kit without permission or cooldown checks and without starting its cooldown. An online player gets it
     * like a claim; for an offline player everything waits in their claim box.
     */
    void give(CommandSender sender, UUID target, Kit kit) {
        String actor = sender instanceof Player staff ? staff.getUniqueId().toString() : "console";
        String name = this.services.directory().name(target);
        Player online = Bukkit.getPlayer(target);
        if (online == null) {
            giveStored(sender, target, name, kit, actor, null);
            return;
        }
        this.services.scheduler().entity(online, () -> {
            if (!online.isOnline()) {
                giveStored(sender, target, name, kit, actor, null);
                return;
            }
            if (!new KitClaimEvent(online, kit.id(), true).callEvent()) {
                this.services.messenger().chat(sender, KitsMessages.ADMIN_GIVE_CANCELLED, Arg.text("player", name), Arg.text("name", kit.name()));
                return;
            }
            giveStored(sender, target, name, kit, actor, online);
        }, () -> giveStored(sender, target, name, kit, actor, null));
    }

    private void giveStored(CommandSender sender, UUID target, String name, Kit kit, String actor, Player online) {
        String ref = newRef(kit);
        CompletableFuture<Void> committed;
        if (kit.items().isEmpty()) {
            committed = CompletableFuture.completedFuture(null);
        } else {
            LedgerTx.Builder tx = LedgerTx.builder().actor(actor).silent().note("kit give " + kit.id());
            for (ItemStack item : this.items.build(kit)) {
                this.services.deliveries().add(tx, target, KitHandouts.SOURCE, ref, item);
            }
            TransactionResult result;
            try {
                result = this.services.ledger().execute(tx.build());
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "Giving the " + kit.id() + " kit to " + name + " failed", e);
                this.services.messenger().chat(sender, KitsMessages.ADMIN_GIVE_FAILED, Arg.text("player", name), Arg.text("name", kit.name()));
                return;
            }
            if (!result.success()) {
                this.services.messenger().chat(sender, KitsMessages.ADMIN_GIVE_FAILED, Arg.text("player", name), Arg.text("name", kit.name()));
                return;
            }
            committed = result.committed();
        }
        committed.whenComplete((ok, error) -> {
            if (error != null) {
                this.logger.log(Level.SEVERE, "The " + kit.id() + " kit for " + name + " could not be stored and was undone", error);
                this.services.messenger().chat(sender, KitsMessages.ADMIN_GIVE_FAILED, Arg.text("player", name), Arg.text("name", kit.name()));
                return;
            }
            this.services.audit().record(actor, "kits.give", target.toString(), "kit=" + kit.id() + " ref=" + ref);
            giveKeys(target, online, kit, ref);
            if (online == null) {
                this.services.messenger().chat(sender, KitsMessages.ADMIN_GIVEN_OFFLINE, Arg.text("player", name), Arg.text("name", kit.name()));
                return;
            }
            this.services.messenger().chat(sender, KitsMessages.ADMIN_GIVEN, Arg.text("player", name), Arg.text("name", kit.name()));
            this.services.scheduler().entity(online, () -> this.handouts.handOver(online, Set.of(ref), outcome -> {
                this.services.messenger().send(online, KitsMessages.ADMIN_RECEIVED, Arg.text("name", kit.name()));
                if (outcome.left() > 0) {
                    this.services.messenger().send(online, KitsMessages.CLAIM_BOX, Arg.text("name", kit.name()));
                }
            }), null);
        });
    }

    /** Clears a player's cooldown of one kit, or of every kit when {@code kit} is null. */
    void reset(CommandSender sender, UUID target, Kit kit) {
        String actor = sender instanceof Player staff ? staff.getUniqueId().toString() : "console";
        String name = this.services.directory().name(target);
        TransactionResult result = this.claims.reset(target, kit == null ? null : kit.id(), actor);
        if (!result.success()) {
            if (KitClaims.NOTHING.equals(result.reason())) {
                this.services.messenger().chat(sender, KitsMessages.ADMIN_RESET_NONE, Arg.text("player", name));
            } else {
                this.services.messenger().chat(sender, KitsMessages.FAILED);
            }
            return;
        }
        result.committed().whenComplete((ok, error) -> {
            if (error != null) {
                this.logger.log(Level.SEVERE, "Resetting kits of " + name + " could not be stored and was undone", error);
                this.services.messenger().chat(sender, KitsMessages.FAILED);
                return;
            }
            this.services.audit().record(actor, "kits.reset", target.toString(), kit == null ? "all" : "kit=" + kit.id());
            if (kit == null) {
                this.services.messenger().chat(sender, KitsMessages.ADMIN_RESET_ALL, Arg.text("player", name));
            } else {
                this.services.messenger().chat(sender, KitsMessages.ADMIN_RESET_ONE, Arg.text("player", name), Arg.text("name", kit.name()));
            }
            Player online = Bukkit.getPlayer(target);
            KitReminders current = this.reminders;
            if (online != null && current != null) {
                this.services.scheduler().entity(online, () -> current.schedule(online), null);
            }
        });
    }
}
