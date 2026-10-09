package net.siftvanilla.siftcore.feature.homes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Setting, using and deleting homes, and the homes dialogs. Every method runs on the acting player's thread. */
final class HomesService {

    static final String LIMIT_PREFIX = "siftcore.homes";
    static final String BYPASS_COOLDOWN = "siftcore.bypass.cooldown";
    private static final String COOLDOWN_KEY = "homes:teleport";

    /**
     * A message to show for an action: the key, its arguments and whether the action worked; or, when
     * {@code overwrite} is set, nothing yet: the player is asked before that existing home moves.
     */
    record Result(boolean ok, MessageKey key, Home overwrite, Arg... args) {

        Result(boolean ok, MessageKey key, Arg... args) {
            this(ok, key, null, args);
        }

        /** Setting this home would move an existing one, which the player wants to be asked about first. */
        static Result askFirst(Home existing) {
            return new Result(false, null, existing);
        }
    }

    private final Services services;
    private final Setting<HomesSettings> settings;
    private final HomeStore store;
    private final SpawnArea spawn;
    private final CombatStatus combat;

    HomesService(Services services, Setting<HomesSettings> settings, HomeStore store, SpawnArea spawn, CombatStatus combat) {
        this.services = services;
        this.settings = settings;
        this.store = store;
        this.spawn = spawn;
        this.combat = combat;
    }

    HomeStore store() {
        return this.store;
    }

    /** How many homes the player may have (rank nodes {@code siftcore.homes.<n>}, highest wins). */
    int limit(Player player) {
        return Limits.highest(player, LIMIT_PREFIX, this.settings.get().defaultLimit());
    }

    private Arg limitArg(int limit) {
        return HomesViews.limit(this.services.lang(), limit);
    }

    private void tell(Player player, Result result) {
        this.services.messenger().send(player, result.key(), result.args());
    }

    // ------------------------------------------------------------------ set

    /** /sethome [name]: moving a home that exists asks first (unless the player turned that off). */
    void setHome(Player player, String input) {
        Result result = trySet(player, input, false);
        if (result.overwrite() != null) {
            this.services.dialogs().show(player, overwriteView(player, result.overwrite(),
                yes -> tell(yes.player(), trySet(yes.player(), result.overwrite().name(), true)), null, true));
            return;
        }
        tell(player, result);
    }

    /** Whether the player hides coordinates (streamer mode) in their own homes list and windows. */
    private boolean hidesCoordinates(Player player) {
        return this.services.settings().get(player, SharedSettings.HIDE_COORDINATES);
    }

    /**
     * "Move home X to where you stand?" with where it is now and where it would go (worlds only in streamer mode).
     *
     * @param onYes  moves it (runs {@link #trySet} again, so every rule is checked at the moment of the click)
     * @param onNo   what Cancel does (null closes)
     * @param closes whether Move closes the window at once (nothing follows it), or waits for the next window
     */
    private View overwriteView(Player player, Home existing, Button.Handler onYes, Button.Handler onNo, boolean closes) {
        Lang lang = this.services.lang();
        Location here = player.getLocation();
        String world = here.getWorld().getName();
        List<Component> lines = new ArrayList<>();
        lines.add(lang.get(HomesMessages.OVERWRITE_BODY, Arg.text("name", existing.name())));
        if (hidesCoordinates(player)) {
            lines.add(lang.get(HomesMessages.OVERWRITE_FROM_HIDDEN, Arg.text("world", existing.world())));
            lines.add(lang.get(HomesMessages.OVERWRITE_TO_HIDDEN, Arg.text("world", world)));
        } else {
            lines.add(lang.get(HomesMessages.OVERWRITE_FROM, HomesViews.at(existing.world(), existing.blockX(), existing.blockY(),
                existing.blockZ())));
            lines.add(lang.get(HomesMessages.OVERWRITE_TO, HomesViews.at(world, here.getBlockX(), here.getBlockY(), here.getBlockZ())));
        }
        View confirm = this.services.templates().confirm(lang.get(HomesMessages.OVERWRITE_TITLE), lines,
            lang.get(HomesMessages.OVERWRITE_BUTTON), lang.get(CoreMessages.UI_CANCEL), onYes, onNo);
        if (!closes) {
            return confirm;
        }
        List<Button> buttons = List.of(confirm.buttons().get(0).closes(), confirm.buttons().get(1));
        return new View(confirm.kind(), confirm.title(), confirm.body(), confirm.inputs(), buttons, confirm.exit(), confirm.columns(),
            confirm.escapable());
    }

    /**
     * Sets a home where the player stands: /sethome and the "Set a home here" form, refused in combat either way.
     *
     * @param confirmed the player already said yes to moving an existing home (or it is a new name)
     */
    private Result trySet(Player player, String input, boolean confirmed) {
        if (this.combat.tagged(player.getUniqueId())) {
            return new Result(false, HomesMessages.IN_COMBAT,
                Arg.text("time", Durations.format(this.combat.remaining(player.getUniqueId()))));
        }
        Optional<String> name = HomeNames.normalize(input);
        if (name.isEmpty()) {
            return new Result(false, HomesMessages.INVALID_NAME);
        }
        Location here = player.getLocation();
        if (this.settings.get().disabled(here.getWorld().getName())) {
            return new Result(false, HomesMessages.WORLD_DISABLED);
        }
        if (this.spawn.contains(here)) {
            return new Result(false, HomesMessages.IN_SPAWN);
        }
        if (!confirmed && this.services.settings().get(player, HomesFeature.CONFIRM_OVERWRITE)) {
            Optional<Home> existing = this.store.get(player.getUniqueId(), name.get());
            if (existing.isPresent()) {
                return Result.askFirst(existing.get());
            }
        }
        int limit = limit(player);
        HomeStore.SetResult result = this.store.set(player.getUniqueId(), Home.at(name.get(), here, System.currentTimeMillis()), limit);
        return switch (result.outcome()) {
            case CREATED -> new Result(true, HomesMessages.SET, Arg.text("name", name.get()), Arg.text("count", Lang.number(result.count())),
                limitArg(limit));
            case MOVED -> new Result(true, HomesMessages.MOVED, Arg.text("name", name.get()));
            case LIMIT -> new Result(false, HomesMessages.LIMIT, Arg.text("count", Lang.number(result.count())), limitArg(limit));
            case NOT_LOADED -> new Result(false, HomesMessages.LOADING);
        };
    }

    // ------------------------------------------------------------------ teleport

    /**
     * /home with no name, as the player's "/home with no name" setting says: the only home or the list when there are
     * several (the default), the home named 'home' (else like the default), or always the list.
     */
    void home(Player player) {
        Optional<Map<String, Home>> homes = this.store.homes(player.getUniqueId());
        if (homes.isEmpty()) {
            this.services.messenger().send(player, HomesMessages.LOADING);
            return;
        }
        Map<String, Home> all = homes.get();
        BareHome mode = this.services.settings().get(player, HomesFeature.BARE_COMMAND);
        switch (mode.decide(all.size(), all.containsKey(HomeNames.DEFAULT))) {
            case NONE -> this.services.messenger().send(player, HomesMessages.NONE);
            case ONLY -> teleport(player, all.keySet().iterator().next());
            case DEFAULT -> teleport(player, HomeNames.DEFAULT);
            case LIST -> openList(player, null);
        }
    }

    /** /home <name>: warmup, then teleport to wherever that home is when the warmup ends, if it still looks safe. */
    void teleport(Player player, String input) {
        teleport(player, input, true);
    }

    /**
     * @param checkSafety false after the player confirmed a home that looks unsafe ("Teleport anyway")
     */
    private void teleport(Player player, String input, boolean checkSafety) {
        UUID id = player.getUniqueId();
        String name = HomeNames.normalize(input).orElse(null);
        if (!this.store.isLoaded(id)) {
            this.services.messenger().send(player, HomesMessages.LOADING);
            return;
        }
        Home home = name == null ? null : this.store.get(id, name).orElse(null);
        if (home == null) {
            this.services.messenger().send(player, HomesMessages.NOT_FOUND, Arg.text("name", input));
            return;
        }
        Result usable = usable(home);
        if (!usable.ok()) {
            tell(player, usable);
            return;
        }
        Duration left = this.services.cooldowns().remaining(id, COOLDOWN_KEY);
        if (!left.isZero() && !player.hasPermission(BYPASS_COOLDOWN)) {
            this.services.messenger().send(player, CoreMessages.COOLDOWN, Arg.time("time", left));
            return;
        }
        this.services.teleports().teleport(player, "home", this.settings.get().warmup(), () -> {
            Home current = this.store.get(id, name).orElse(null);
            if (current == null) {
                this.services.messenger().send(player, HomesMessages.GONE, Arg.text("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Result check = usable(current);
            if (!check.ok()) {
                tell(player, check);
                return CompletableFuture.completedFuture(null);
            }
            Location location = current.toLocation();
            return checkSafety ? safe(player, current, location) : CompletableFuture.completedFuture(location);
        }, ok -> {
            if (ok) {
                this.services.cooldowns().start(id, COOLDOWN_KEY, this.settings.get().cooldown());
                // The welcome is the arrival line: it shows where the player's teleport display setting says.
                this.services.teleports().arrival(player, HomesMessages.TELEPORTED, Arg.text("name", name));
            }
        }, true);
    }

    /**
     * The home's location when its two blocks look safe; otherwise null, and the player is asked whether to go
     * anyway. The chunk is loaded without generating and read on the thread that owns it.
     */
    private CompletableFuture<Location> safe(Player player, Home home, Location location) {
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        CompletableFuture<Location> result = new CompletableFuture<>();
        world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                result.complete(location);
                return;
            }
            this.services.scheduler().region(world, chunkX, chunkZ, () -> {
                Block feet = world.getBlockAt(location);
                HomeSafety.Danger danger = HomeSafety.danger(kind(feet), kind(feet.getRelative(BlockFace.UP)));
                if (danger == HomeSafety.Danger.NONE) {
                    result.complete(location);
                    return;
                }
                result.complete(null);
                this.services.scheduler().entity(player, () -> confirmUnsafe(player, home, danger), null);
            });
        });
        return result;
    }

    /** What a block means for a player standing in it. Region thread. */
    private static HomeSafety.Kind kind(Block block) {
        Material type = block.getType();
        if (type == Material.LAVA) {
            return HomeSafety.Kind.LAVA;
        }
        if (Tag.FIRE.isTagged(type) || (Tag.CAMPFIRES.isTagged(type) && block.getBlockData() instanceof Lightable lit && lit.isLit())) {
            return HomeSafety.Kind.FIRE;
        }
        return block.isSuffocating() ? HomeSafety.Kind.FULL : HomeSafety.Kind.CLEAR;
    }

    /** "Home X looks unsafe: lava. Teleport anyway?" The answer starts a new teleport (with its warmup). */
    private void confirmUnsafe(Player player, Home home, HomeSafety.Danger danger) {
        Lang lang = this.services.lang();
        MessageKey reason = switch (danger) {
            case LAVA -> HomesMessages.UNSAFE_LAVA;
            case FIRE -> HomesMessages.UNSAFE_FIRE;
            default -> HomesMessages.UNSAFE_BLOCKED;
        };
        this.services.dialogs().show(player, this.services.templates().confirm(lang.get(HomesMessages.UNSAFE_TITLE),
            List.of(lang.get(HomesMessages.UNSAFE_BODY, Arg.text("name", home.name()), Arg.component("reason", lang.get(reason)))),
            lang.get(HomesMessages.UNSAFE_GO), lang.get(CoreMessages.UI_CANCEL),
            yes -> teleport(yes.player(), home.name(), false),
            null).closing());
    }

    private Result usable(Home home) {
        if (this.settings.get().disabled(home.world())) {
            return new Result(false, HomesMessages.HOME_WORLD_DISABLED, Arg.text("name", home.name()));
        }
        if (Bukkit.getWorld(home.world()) == null) {
            return new Result(false, HomesMessages.WORLD_MISSING, Arg.text("name", home.name()));
        }
        return new Result(true, HomesMessages.TELEPORTED, Arg.text("name", home.name()));
    }

    // ------------------------------------------------------------------ delete

    /** Deletes one of the player's own homes (after the confirmation). */
    private void delete(Player player, String name) {
        UUID id = player.getUniqueId();
        if (this.store.get(id, name).isEmpty()) {
            this.services.messenger().send(player, HomesMessages.NOT_FOUND, Arg.text("name", name));
            return;
        }
        this.store.delete(id, name);
        this.services.messenger().send(player, HomesMessages.DELETED, Arg.text("name", name));
    }

    /** /delhome <name>: asks first. {@code after} runs after either answer (null closes the dialog). */
    void confirmDelete(Player player, String input, Runnable after) {
        String name = HomeNames.normalize(input).orElse(null);
        if (!this.store.isLoaded(player.getUniqueId())) {
            this.services.messenger().send(player, HomesMessages.LOADING);
            return;
        }
        Home home = name == null ? null : this.store.get(player.getUniqueId(), name).orElse(null);
        if (home == null) {
            this.services.messenger().send(player, HomesMessages.NOT_FOUND, Arg.text("name", input));
            return;
        }
        Lang lang = this.services.lang();
        List<Component> body = hidesCoordinates(player)
            ? lang.lines(HomesMessages.DELETE_BODY_HIDDEN, Arg.text("name", home.name()), Arg.text("world", home.world()))
            : lang.lines(HomesMessages.DELETE_BODY, HomesViews.with(Arg.text("name", home.name()), HomesViews.at(home)));
        View confirm = this.services.templates().confirm(lang.get(HomesMessages.DELETE_TITLE), body,
            lang.get(HomesMessages.DELETE_BUTTON), lang.get(CoreMessages.UI_CANCEL),
            yes -> {
                delete(yes.player(), home.name());
                if (after != null) {
                    after.run();
                }
            },
            no -> {
                if (after != null) {
                    after.run();
                }
            });
        // From /delhome both answers finish; from the list both lead back to it.
        this.services.dialogs().show(player, after == null ? confirm.closing() : confirm);
    }

    // ------------------------------------------------------------------ dialogs

    /**
     * The homes list ({@link HomesViews#list}): a button per home that teleports there and a Delete next to it, then
     * "Set a home here". No pages: the dialog scrolls.
     */
    void openList(Player player, Button.Handler back) {
        Optional<Map<String, Home>> loaded = this.store.homes(player.getUniqueId());
        if (loaded.isEmpty()) {
            this.services.messenger().send(player, HomesMessages.LOADING);
            return;
        }
        List<Home> all = new ArrayList<>(loaded.get().values());
        HomesViews.Actions actions = new HomesViews.Actions(
            home -> s -> teleport(s.player(), home.name()),
            home -> s -> confirmDelete(s.player(), home.name(), () -> openList(s.player(), back)),
            s -> openSetForm(s.player(), back),
            back);
        this.services.dialogs().show(player, HomesViews.list(this.services.lang(), this.services.templates(), all, limit(player),
            hidesCoordinates(player), actions));
    }

    /**
     * A form to name a new home where the player stands. A name that already exists asks before that home moves
     * (unless the player turned that off); Cancel there goes back to the form.
     */
    void openSetForm(Player player, Button.Handler back) {
        Lang lang = this.services.lang();
        String suggestion = HomeNames.suggest(this.store.homes(player.getUniqueId()).orElse(Map.of()));
        View form = this.services.templates().form(lang.get(HomesMessages.FORM_TITLE), List.of(),
            List.of(Templates.text("name", lang.get(HomesMessages.FORM_NAME), suggestion, HomeNames.MAX_LENGTH)),
            lang.get(HomesMessages.FORM_SUBMIT),
            submission -> {
                Result result = trySet(submission.player(), submission.values().text("name"), false);
                if (result.overwrite() != null) {
                    String name = result.overwrite().name();
                    submission.show(overwriteView(submission.player(), result.overwrite(), yes -> {
                        Result moved = trySet(yes.player(), name, true);
                        tell(yes.player(), moved);
                        if (moved.ok()) {
                            openList(yes.player(), back);
                        } else {
                            openSetForm(yes.player(), back);
                        }
                    }, no -> openSetForm(no.player(), back), false));
                    return;
                }
                if (!result.ok()) {
                    submission.error(lang.get(result.key(), result.args()));
                    return;
                }
                tell(submission.player(), result);
                openList(submission.player(), back);
            },
            submission -> openList(submission.player(), back));
        // What the form does is on its button: the home is set where you stand; the name rule shows if one is refused.
        List<Button> buttons = List.of(form.buttons().get(0).tooltip(lang.get(HomesMessages.FORM_SUBMIT_TOOLTIP)), form.buttons().get(1));
        this.services.dialogs().show(player, new View(form.kind(), form.title(), form.body(), form.inputs(), buttons, form.exit(),
            form.columns(), form.escapable()));
    }

    // ------------------------------------------------------------------ staff

    /** Staff: another player's homes (online or offline) with teleport and delete buttons. */
    void openOther(Player staff, UUID target, String targetName) {
        this.store.fetch(target).whenComplete((homes, error) -> this.services.scheduler().entity(staff, () -> {
            if (error != null) {
                this.services.messenger().send(staff, CoreMessages.ACTION_FAILED);
                return;
            }
            showOther(staff, target, targetName, homes);
        }, null));
    }

    private void showOther(Player staff, UUID target, String targetName, Map<String, Home> homes) {
        HomesViews.Actions actions = new HomesViews.Actions(
            home -> s -> this.services.teleports().teleport(s.player(), "home-staff", Duration.ZERO, () -> {
                Location location = home.toLocation();
                if (location == null) {
                    this.services.messenger().send(s.player(), HomesMessages.WORLD_MISSING, Arg.text("name", home.name()));
                }
                return CompletableFuture.completedFuture(location);
            }, ok -> {
                // A staff member visiting a player's base is written down like invsee and whois are.
                if (ok) {
                    this.services.audit().record(s.player().getUniqueId().toString(), "homes.teleport", target.toString(),
                        staffTeleportDetails(home));
                }
            }),
            home -> s -> confirmOtherDelete(s.player(), target, targetName, home),
            null,
            null);
        // Shown after a database read, outside the command's scope: written for the staff member.
        this.services.dialogs().show(staff, () ->
            HomesViews.other(this.services.lang(), this.services.templates(), targetName, new ArrayList<>(homes.values()), actions));
    }

    /** The audit details of a staff teleport to a home: its name, world and block position. */
    static String staffTeleportDetails(Home home) {
        return home.name() + " " + home.world() + " " + home.blockX() + "," + home.blockY() + "," + home.blockZ();
    }

    private void confirmOtherDelete(Player staff, UUID target, String targetName, Home home) {
        Lang lang = this.services.lang();
        this.services.dialogs().show(staff, this.services.templates().confirm(lang.get(HomesMessages.DELETE_TITLE),
            lang.lines(HomesMessages.DELETE_BODY, HomesViews.with(Arg.text("name", home.name()), HomesViews.at(home))),
            lang.get(HomesMessages.DELETE_BUTTON), lang.get(CoreMessages.UI_CANCEL),
            yes -> deleteOther(yes.player(), target, targetName, home.name(), () -> openOther(yes.player(), target, targetName)),
            no -> openOther(no.player(), target, targetName)));
    }

    /** Staff: deletes another player's home (works offline too). {@code then} runs on the sender's thread afterwards. */
    void deleteOther(CommandSender sender, UUID target, String targetName, String input, Runnable then) {
        String name = HomeNames.normalize(input).orElse(input);
        this.store.delete(target, name).whenComplete((deleted, error) -> {
            Runnable report = () -> {
                if (error != null) {
                    this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                } else if (Boolean.TRUE.equals(deleted)) {
                    this.services.messenger().chat(sender, HomesMessages.ADMIN_DELETED, Arg.text("home", name), Arg.text("name", targetName));
                    this.services.audit().record(sender instanceof Player p ? p.getUniqueId().toString() : "console", "homes.delete",
                        target.toString(), name);
                } else {
                    this.services.messenger().send(sender, HomesMessages.NOT_FOUND, Arg.text("name", name));
                }
                if (then != null) {
                    then.run();
                }
            };
            if (sender instanceof Player player) {
                this.services.scheduler().entity(player, report, null);
            } else {
                report.run();
            }
        });
    }

    /** Staff and console: another player's homes as chat lines. */
    void listOther(CommandSender sender, UUID target, String targetName) {
        this.store.fetch(target).whenComplete((homes, error) -> {
            if (error != null) {
                this.services.messenger().send(sender, CoreMessages.ACTION_FAILED);
                return;
            }
            if (homes.isEmpty()) {
                this.services.messenger().chat(sender, HomesMessages.ADMIN_EMPTY, Arg.text("name", targetName));
                return;
            }
            this.services.messenger().chat(sender, HomesMessages.ADMIN_HEADER, Arg.text("name", targetName), Arg.number("count", homes.size()));
            for (Home home : homes.values()) {
                this.services.messenger().chat(sender, HomesMessages.ADMIN_LINE, Arg.text("home", home.name()), Arg.text("world", home.world()),
                    Arg.number("x", home.blockX()), Arg.number("y", home.blockY()), Arg.number("z", home.blockZ()));
            }
        });
    }
}
