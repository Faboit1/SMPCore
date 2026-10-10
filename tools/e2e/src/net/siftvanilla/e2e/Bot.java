package net.siftvanilla.e2e;

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A scripted test player: a real protocol client running inside the server JVM (it uses the server's own codecs),
 * so player-only flows (commands, dialogs, chest GUIs, chat) can be exercised end to end without a Minecraft
 * client. Test tooling only; never part of SiftCore.
 */
public final class Bot {

    /**
     * A slider (number_range input) as the client received it: its range, step (null when the client may pick any
     * value), the value it starts at, the label format and the label.
     */
    public record RangeSeen(float start, float end, Float step, float initial, String labelFormat, String label) {
    }

    /**
     * A dialog as the client received it. {@code after} is what the client does after a click: close, none (stay until
     * the next dialog) or wait_for_response. {@code initial} holds the text inputs' pre-filled values (what the player
     * would see typed in the fields), {@code initials} the value each toggle input starts with, {@code options} each
     * choice's option ids in order, {@code choiceInitial} the option each choice starts on, {@code optionLabels} the
     * options' labels, and {@code ranges} each slider.
     */
    public record SeenDialog(String type, String title, List<String> body, List<Button> buttons, Map<String, String> inputs, long at,
                             String after, Map<String, String> initial, Map<String, Boolean> initials,
                             Map<String, List<String>> options, Map<String, String> choiceInitial,
                             Map<String, List<String>> optionLabels, Map<String, RangeSeen> ranges) {

        /**
         * Every input's shown value, typed like a vanilla client sends it when the player touches nothing: text as a
         * String, a toggle as a Boolean, a choice as its option id, a slider as a Float. Put changed values over it and
         * pass it to {@link Bot#clickButton}.
         */
        public Map<String, Object> values() {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> input : this.inputs.entrySet()) {
                String key = input.getKey();
                switch (input.getValue()) {
                    case "text" -> values.put(key, this.initial.getOrDefault(key, ""));
                    case "toggle" -> values.put(key, this.initials.getOrDefault(key, false));
                    case "choice" -> values.put(key, this.choiceInitial.get(key));
                    case "range" -> values.put(key, this.ranges.get(key).initial());
                    default -> {
                    }
                }
            }
            return values;
        }

        /** The option a choice input starts on, or null when there is no such choice. */
        public String choiceValue(String key) {
            return this.choiceInitial.get(key);
        }

        /** A slider input, or null when there is no such slider. */
        public RangeSeen range(String key) {
            return this.ranges.get(key);
        }

        /** The pre-filled text of a text input, or null when the dialog has no such input. */
        public String initial(String key) {
            return this.initial.get(key);
        }

        /** The value a toggle input starts with, or null when there is no such toggle. */
        public Boolean toggleValue(String key) {
            return this.initials.get(key);
        }

        public Button button(String labelContains) {
            for (Button button : this.buttons) {
                if (button.label().toLowerCase().contains(labelContains.toLowerCase())) {
                    return button;
                }
            }
            return null;
        }

        public String bodyText() {
            return String.join("\n", this.body);
        }
    }

    /**
     * A dialog button and the custom click it sends. {@code tooltip} is its tooltip as plain text (lines joined with
     * newlines), or null when it has none; {@code labelComponent} keeps the label's colours.
     */
    public record Button(String label, String actionId, CompoundTag additions, String tooltip, Component labelComponent) {

        public Button(String label, String actionId, CompoundTag additions) {
            this(label, actionId, additions, null, null);
        }

        /** The colour of the last coloured part of the label (the value of "Label: ON"), as #RRGGBB, or null. */
        public String valueColor() {
            if (this.labelComponent == null) {
                return null;
            }
            String[] found = new String[1];
            this.labelComponent.visit((style, text) -> {
                if (!text.isBlank() && style.getColor() != null) {
                    found[0] = String.format("#%06X", style.getColor().getValue());
                }
                return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);
            return found[0];
        }
    }

    /** An open container screen: its menu type id (e.g. {@code minecraft:generic_9x6}) and the title as sent. */
    public record Screen(int containerId, String title, long at, String type, Component titleComponent) {
    }

    /** A sign editor the server opened for this client (sign input), and which side of the sign it edits. */
    public record SignEditor(BlockPos pos, boolean front, long at) {
    }

    /** A chat message that opens a dialog when clicked (a show_dialog click event), and that dialog. */
    public record ChatDialog(String text, SeenDialog dialog) {
    }

    /** A scoreboard objective as the client knows it; {@code numberFormat} is the format's class name or "default". */
    public record SeenObjective(String name, Component displayName, String numberFormat) {
    }

    /** A score as the client knows it: owner (entry), value, custom text (null when none) and the number of the packet that last set it. */
    public record SeenScore(String owner, int value, Component display, String numberFormat, int packet) {

        /** What the sidebar shows for this score: the custom text, or the owner name without one. */
        public String text() {
            return this.display == null ? this.owner : this.display.getString();
        }
    }

    /** A scoreboard team as the client knows it; {@code color} is the team colour's name or "none". */
    public record SeenTeam(String name, Component prefix, Component suffix, String color, byte options, Set<String> members) {
    }

    /** A sound the server played for this client: its id, volume and pitch. */
    public record SeenSound(String sound, float volume, float pitch, long at) {
    }

    /**
     * A boss bar the client shows: its title, progress (0 to 1), colour and overlay (enum names, like GREEN and
     * PROGRESS).
     */
    public record SeenBossBar(UUID id, Component text, float progress, String color, String overlay) {

        /** The title as plain text. */
        public String name() {
            return this.text.getString();
        }

        /** The title as plain text (the same as {@link #name()}). */
        public String title() {
            return this.text.getString();
        }
    }

    /** An entity the server added for this client, with its synced data values by data id. */
    public record SeenEntity(int id, UUID uuid, String type, double x, double y, double z, Map<Integer, Object> data) {
    }

    private static final Logger LOG = Logger.getLogger("SiftE2E");

    public final String name;
    public final UUID uuid;
    private final ScheduledExecutorService exec;
    private final RegistryAccess registryAccess = MinecraftServer.getServer().registryAccess();
    private volatile Connection conn;
    private volatile double x, y, z;
    private volatile float yRot, xRot;
    private volatile boolean loaded;
    private volatile boolean disconnected;
    private volatile String disconnectReason = "";
    private volatile int inventoryStateId;
    private volatile int screenStateId;
    private volatile SeenDialog dialog;
    private volatile Screen screen;
    /** The open screen's items, replaced whole on every change so a reader never sees a half-filled screen. */
    private volatile Map<Integer, ItemStack> screenItems = Map.of();
    /** The container whose items arrived last; a screen counts as open only once its items are here. */
    private volatile int contentFor = -1;
    private final List<String> chat = new CopyOnWriteArrayList<>();
    private final List<Component> chatComponents = new CopyOnWriteArrayList<>();
    private volatile SignEditor signEditor;
    private final List<String> actionBar = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final List<SeenSound> sounds = new CopyOnWriteArrayList<>();
    private final Map<UUID, SeenBossBar> bossBars = new ConcurrentHashMap<>();
    private final List<SeenDialog> dialogs = new CopyOnWriteArrayList<>();
    /** Registry entries the server sent in the configuration phase, in network id order, by registry id. */
    private final Map<String, List<String>> registries = new java.util.concurrent.ConcurrentHashMap<>();
    /** Tags the server sent: registry id, then tag id, then the network ids of its entries. */
    private final Map<String, Map<String, List<Integer>>> tags = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<ChatDialog> chatDialogs = new CopyOnWriteArrayList<>();
    private final Map<Integer, SeenEntity> entities = new ConcurrentHashMap<>();
    private final AtomicInteger deaths = new AtomicInteger();
    private final AtomicInteger dialogsCleared = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile long lastDeathScreen;
    private final Set<UUID> playerInfo = ConcurrentHashMap.newKeySet();
    private final Set<UUID> everPlayerInfo = ConcurrentHashMap.newKeySet();
    private final Map<String, SeenObjective> objectives = new ConcurrentHashMap<>();
    private final Map<String, String> displaySlots = new ConcurrentHashMap<>();
    private final Map<String, Map<String, SeenScore>> scores = new ConcurrentHashMap<>();
    private final Map<String, SeenTeam> teams = new ConcurrentHashMap<>();
    private final AtomicInteger scorePackets = new AtomicInteger();
    private final AtomicInteger teamPackets = new AtomicInteger();
    private final AtomicInteger tabListPackets = new AtomicInteger();
    private volatile Component tabHeader;
    private volatile Component tabFooter;
    private final Map<UUID, Component> listNames = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> listOrders = new ConcurrentHashMap<>();
    private final AtomicInteger bossBarPackets = new AtomicInteger();
    /** Particles received, by particle type id (minecraft:heart), counting packets. */
    private final Map<String, AtomicInteger> particles = new ConcurrentHashMap<>();
    /** Every entity type the server ever added for this client (kept after the entity is removed). */
    private final Set<String> everEntityTypes = ConcurrentHashMap.newKeySet();

    public Bot(String name) {
        this.name = name;
        this.uuid = UUIDUtil.createOfflinePlayerUUID(name);
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "SiftE2E-Bot-" + name));
        // A vanilla client ticks its connection 20 times a second; the tick flushes packets queued before the
        // channel became active. Without it a login can stall forever when connectToServer returns early.
        this.exec.scheduleAtFixedRate(() -> {
            Connection c = this.conn;
            if (c != null) {
                try {
                    c.tick();
                } catch (RuntimeException e) {
                    LOG.fine("[" + name + "] connection tick failed: " + e);
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ state

    public boolean loaded() {
        return this.loaded;
    }

    public boolean disconnected() {
        return this.disconnected;
    }

    public String disconnectReason() {
        return this.disconnectReason;
    }

    /**
     * The entries of a registry tag as the client received them at login, resolved to entry ids (for example the
     * dialogs in {@code minecraft:pause_screen_additions}). Vanilla entries the client already knows from a known
     * pack are listed by the server too, so this is the full order.
     */
    public List<String> tag(String registry, String tag) {
        Map<String, List<Integer>> byTag = this.tags.get(registry);
        List<String> entries = this.registries.getOrDefault(registry, List.of());
        if (byTag == null || !byTag.containsKey(tag)) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (int id : byTag.get(tag)) {
            ids.add(id >= 0 && id < entries.size() ? entries.get(id) : "#" + id);
        }
        return ids;
    }

    /** The ids of a registry's entries as sent at login (empty when it was not sent). */
    public List<String> registry(String registry) {
        return this.registries.getOrDefault(registry, List.of());
    }

    public SeenDialog dialog() {
        return this.dialog;
    }

    public List<SeenDialog> dialogs() {
        return List.copyOf(this.dialogs);
    }

    /**
     * The open screen, once its items arrived. The server sends a screen's items right after opening it; a check
     * between the two would otherwise see the right title over an empty screen.
     */
    public Screen screen() {
        Screen current = this.screen;
        return current != null && current.containerId() == this.contentFor ? current : null;
    }

    public Map<Integer, ItemStack> screenItems() {
        return this.screenItems;
    }

    public List<String> chat() {
        return List.copyOf(this.chat);
    }

    /** Chat messages as the components the server sent (system and player chat, in the order of {@link #chat()}), to check their styling, hover and click events. */
    public List<Component> chatComponents() {
        return List.copyOf(this.chatComponents);
    }

    /** The last sign editor the server opened, or null. */
    public SignEditor signEditor() {
        return this.signEditor;
    }

    public List<String> actionBar() {
        return List.copyOf(this.actionBar);
    }

    public List<String> titles() {
        return List.copyOf(this.titles);
    }

    /** Sounds played for this client since the logs were last cleared. */
    public List<SeenSound> sounds() {
        return List.copyOf(this.sounds);
    }

    public int deaths() {
        return this.deaths.get();
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    /** Entities currently added for this client (spawned and not yet removed). */
    public List<SeenEntity> entities() {
        return List.copyOf(this.entities.values());
    }

    public int dialogsCleared() {
        return this.dialogsCleared.get();
    }

    /** Whether the client currently has a player info (tab list) entry for this profile. */
    public boolean hasPlayerInfo(UUID profile) {
        return this.playerInfo.contains(profile);
    }

    /** Whether the client was ever sent a player info entry for this profile since it connected. */
    public boolean everHadPlayerInfo(UUID profile) {
        return this.everPlayerInfo.contains(profile);
    }

    /** Whether the client currently tracks an entity with this uuid (it was spawned and not removed). */
    public boolean seesEntity(UUID uuid) {
        return this.entities.values().stream().anyMatch(entity -> entity.uuid().equals(uuid));
    }

    // ------------------------------------------------------------------ boss bars (client state)

    /** The boss bars the client shows now (added and not yet removed). */
    public List<SeenBossBar> bossBars() {
        return List.copyOf(this.bossBars.values());
    }

    /** The boss bar whose title contains the text, or null. */
    public SeenBossBar bossBar(String titleContains) {
        String needle = titleContains.toLowerCase();
        for (SeenBossBar bar : this.bossBars.values()) {
            if (bar.title().toLowerCase().contains(needle)) {
                return bar;
            }
        }
        return null;
    }

    /** Boss bar packets received so far (adds, updates and removals). */
    public int bossBarPackets() {
        return this.bossBarPackets.get();
    }

    // ------------------------------------------------------------------ scoreboard and tab list (client state)

    public SeenObjective objective(String name) {
        return this.objectives.get(name);
    }

    /** The objective shown in a display slot ({@code sidebar}, {@code list}, {@code below_name}), or null. */
    public SeenObjective displayed(String slot) {
        String name = this.displaySlots.get(slot);
        return name == null ? null : this.objectives.get(name);
    }

    /** The scores of an objective by owner. */
    public Map<String, SeenScore> scores(String objective) {
        Map<String, SeenScore> map = this.scores.get(objective);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    /**
     * The sidebar as the client draws it: text of each score, highest score first (ties by owner), or empty. A score
     * without custom text shows its owner inside the owner's team prefix and suffix, as the client draws it (TAB builds
     * its lines that way, around an owner of colour codes only, which draw nothing).
     */
    public List<String> sidebarLines() {
        SeenObjective sidebar = displayed("sidebar");
        if (sidebar == null) {
            return List.of();
        }
        List<SeenScore> sorted = new ArrayList<>(scores(sidebar.name()).values());
        sorted.sort(java.util.Comparator.comparingInt(SeenScore::value).reversed().thenComparing(SeenScore::owner));
        List<String> lines = new ArrayList<>(sorted.size());
        for (SeenScore score : sorted) {
            SeenTeam team = score.display() == null ? teamOf(score.owner()) : null;
            lines.add(team == null ? score.text()
                : team.prefix().getString() + score.owner().replaceAll("\u00a7.", "") + team.suffix().getString());
        }
        return lines;
    }

    public SeenTeam team(String name) {
        return this.teams.get(name);
    }

    public Map<String, SeenTeam> teams() {
        return Map.copyOf(this.teams);
    }

    /** The team an entry (player name) belongs to on this client's board, or null. */
    public SeenTeam teamOf(String entry) {
        for (SeenTeam team : this.teams.values()) {
            if (team.members().contains(entry)) {
                return team;
            }
        }
        return null;
    }

    /** Score packets received so far (to check that unchanged lines are not resent). */
    public int scorePackets() {
        return this.scorePackets.get();
    }

    public int teamPackets() {
        return this.teamPackets.get();
    }

    public int tabListPackets() {
        return this.tabListPackets.get();
    }

    public String tabHeader() {
        Component header = this.tabHeader;
        return header == null ? null : header.getString();
    }

    public String tabFooter() {
        Component footer = this.tabFooter;
        return footer == null ? null : footer.getString();
    }

    /** The tab list name the server set for a profile, or null when it shows the plain name. */
    public Component listName(UUID profile) {
        return this.listNames.get(profile);
    }

    /** The tab list order the server set for a profile (0 when never set). */
    public int listOrder(UUID profile) {
        return this.listOrders.getOrDefault(profile, 0);
    }

    /** How many particle packets of a type (like {@code minecraft:heart}) arrived since the last {@link #clearParticles}. */
    public int particles(String type) {
        AtomicInteger count = this.particles.get(type);
        return count == null ? 0 : count.get();
    }

    /** The particle types received so far. */
    public Set<String> particleTypes() {
        return Set.copyOf(this.particles.keySet());
    }

    public void clearParticles() {
        this.particles.clear();
        this.everEntityTypes.clear();
    }

    /** Whether the server ever added an entity of this type (like {@code minecraft:lightning_bolt}) since the last clear. */
    public boolean sawEntityType(String type) {
        return this.everEntityTypes.contains(type);
    }

    /** Forgets everything received so far (start of a step). */
    public void clearLogs() {
        this.chat.clear();
        this.chatComponents.clear();
        this.signEditor = null;
        this.actionBar.clear();
        this.titles.clear();
        this.sounds.clear();
        this.dialogs.clear();
        this.chatDialogs.clear();
        this.dialog = null;
    }

    /** Forgets the sounds heard so far, keeping everything else (the open dialog too). */
    public void clearSounds() {
        this.sounds.clear();
    }

    /** Forgets the chat, action bar and titles received so far, keeping the open dialog (to click it next). */
    public void clearMessages() {
        this.chat.clear();
        this.actionBar.clear();
        this.titles.clear();
    }

    public boolean chatContains(String text) {
        String needle = text.toLowerCase();
        return this.chat.stream().anyMatch(line -> line.toLowerCase().contains(needle));
    }

    public boolean actionBarContains(String text) {
        String needle = text.toLowerCase();
        return this.actionBar.stream().anyMatch(line -> line.toLowerCase().contains(needle));
    }

    public boolean anyFeedbackContains(String text) {
        return chatContains(text) || actionBarContains(text);
    }

    /** Chat messages received so far that open a dialog when clicked. */
    public List<ChatDialog> chatDialogs() {
        return List.copyOf(this.chatDialogs);
    }

    /**
     * Clicks the newest chat message containing the text that opens a dialog. Like the vanilla client, this shows
     * the embedded dialog locally (nothing is sent); its buttons then work through {@link #clickButton}.
     */
    public boolean openChatDialog(String textContains) {
        String needle = textContains.toLowerCase();
        for (int i = this.chatDialogs.size() - 1; i >= 0; i--) {
            ChatDialog entry = this.chatDialogs.get(i);
            if (entry.text().toLowerCase().contains(needle)) {
                this.dialog = entry.dialog();
                this.dialogs.add(entry.dialog());
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ connection

    public void connect(int port) {
        this.exec.execute(() -> {
            try {
                Connection connection = Connection.connectToServer(new InetSocketAddress("127.0.0.1", port), EventLoopGroupHolder.remote(false), null);
                // channelActive runs on the event loop and may not have happened yet; packets sent before it
                // are only queued, so wait for it (the scheduled tick also flushes anything still queued).
                long until = System.currentTimeMillis() + 5_000;
                while (!connection.isConnected() && System.currentTimeMillis() < until) {
                    Thread.sleep(5);
                }
                this.conn = connection;
                this.conn.initiateServerboundPlayConnection("127.0.0.1", port, proxy(ClientLoginPacketListener.class));
                this.conn.send(new ServerboundHelloPacket(this.name, this.uuid));
            } catch (Throwable e) {
                this.disconnected = true;
                this.disconnectReason = "connect failed: " + e;
            }
        });
    }

    /**
     * Waits until a freshly connected client connection is active. {@code connectToServer} returns as soon as the TCP
     * connect completes, which can be just before netty marks the channel active; anything sent before that is
     * queued and only flushed by {@code Connection#tick()}, which a bot never calls, so the login would hang.
     */
    public static void awaitActive(Connection connection) throws InterruptedException {
        long end = System.currentTimeMillis() + 5_000;
        while (!connection.becomeActive() && System.currentTimeMillis() < end) {
            Thread.sleep(5);
        }
    }

    /** Disconnects. Safe to call again (a scenario may quit a bot before the cleanup does). */
    public void quit() {
        if (this.exec.isShutdown()) {
            return;
        }
        Connection c = this.conn;
        if (c != null) {
            this.exec.execute(() -> c.disconnect(Component.literal("bot quit")));
        }
        this.exec.shutdown();
    }

    private void send(Packet<?> packet) {
        Connection c = this.conn;
        if (c != null && c.isConnected()) {
            c.send(packet);
        }
    }

    // ------------------------------------------------------------------ actions

    public void command(String command) {
        send(new ServerboundChatCommandPacket(command.startsWith("/") ? command.substring(1) : command));
    }

    public void chat(String message) {
        send(new ServerboundChatPacket(message, Instant.now(), 0L, null, new LastSeenMessages.Update(0, new BitSet(), (byte) 0)));
    }

    /** Walks along x by {@code dx} blocks and turns a bit (counts as activity). */
    public void move(double dx, float turn) {
        this.x += dx;
        this.yRot += turn;
        send(new ServerboundMovePlayerPacket.PosRot(this.x, this.y, this.z, this.yRot, this.xRot, true, false));
    }

    /** Moves by the given offsets and turns; the server decides whether the move stands. */
    public void moveBy(double dx, double dy, double dz, float turn) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        this.yRot += turn;
        send(new ServerboundMovePlayerPacket.PosRot(this.x, this.y, this.z, this.yRot, this.xRot, false, false));
    }

    /** Holds or releases the sneak key, like the vanilla client's input packet. */
    public void sneak(boolean sneaking) {
        send(new ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(false, false, false, false, false, sneaking, false)));
    }

    public void respawn() {
        send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    /** The id of the entity with this uuid as this client knows it, or -1 when it doesn't track it. */
    public int entityId(UUID uuid) {
        for (SeenEntity entity : this.entities.values()) {
            if (entity.uuid().equals(uuid)) {
                return entity.id();
            }
        }
        return -1;
    }

    /** Attacks an entity, like a left click on it (the server checks the reach). */
    public void attack(int entityId) {
        send(new ServerboundAttackPacket(entityId));
    }

    /** Uses the main hand item in the air, like a right click (throws an ender pearl, for example). */
    public void useItem() {
        send(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, this.sequence.incrementAndGet(), this.yRot, this.xRot));
    }

    /** Right-clicks an entity with the main hand, like the vanilla client does. */
    public void interact(int entityId) {
        send(new ServerboundInteractPacket(entityId, InteractionHand.MAIN_HAND, Vec3.ZERO, false));
    }

    /** Picks a trade in an open villager or wandering trader screen (the server moves the payment in). */
    public void selectTrade(int index) {
        send(new ServerboundSelectTradePacket(index));
    }

    /** Right-clicks an entity with the main hand while holding sneak (the packet carries the sneak state). */
    public void interactSneaking(int entityId) {
        send(new ServerboundInteractPacket(entityId, InteractionHand.MAIN_HAND, Vec3.ZERO, true));
    }

    /**
     * Starts breaking a block, like a left click. The server breaks it at once when the player mines fast enough
     * ("insta mine"), so give the player a fast tool or a high block break speed first.
     */
    public void breakBlock(int x, int y, int z) {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, new BlockPos(x, y, z),
            Direction.UP, this.sequence.incrementAndGet()));
    }

    /** Right-clicks the top face of a block with the main hand item (a block item is placed on top of it). */
    public void useItemOnTop(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, this.sequence.incrementAndGet()));
    }

    /**
     * Clicks a button of the current dialog like the vanilla client would: the custom click carries the button's
     * additions plus every input value (text and option ids as strings, toggles as bytes, sliders as floats).
     */
    public boolean clickButton(String labelContains, Map<String, Object> values) {
        SeenDialog current = this.dialog;
        if (current == null) {
            return false;
        }
        Button button = current.button(labelContains);
        if (button == null || button.actionId() == null) {
            return false;
        }
        CompoundTag payload = button.additions() == null ? new CompoundTag() : button.additions().copy();
        for (Map.Entry<String, String> input : current.inputs().entrySet()) {
            Object value = values.containsKey(input.getKey()) ? values.get(input.getKey()) : null;
            String kind = input.getValue();
            switch (kind) {
                case "text", "choice" -> payload.put(input.getKey(), StringTag.valueOf(value == null ? "" : String.valueOf(value)));
                case "toggle" -> payload.put(input.getKey(), ByteTag.valueOf(value != null && Boolean.parseBoolean(String.valueOf(value))));
                case "range" -> payload.put(input.getKey(), FloatTag.valueOf(value == null ? 0f : Float.parseFloat(String.valueOf(value))));
                default -> {
                }
            }
        }
        send(new ServerboundCustomClickActionPacket(Identifier.parse(button.actionId()), Optional.of(payload)));
        return true;
    }

    /** Sends an arbitrary custom click (for forging tests). */
    public void rawClick(String id, CompoundTag payload) {
        send(new ServerboundCustomClickActionPacket(Identifier.parse(id), Optional.ofNullable(payload)));
    }

    /** Left-clicks a slot of the open screen (raw slot index). */
    public void clickSlot(int slot) {
        clickSlot(slot, 0, ContainerInput.PICKUP);
    }

    public void clickSlot(int slot, int button, ContainerInput input) {
        Screen current = this.screen;
        int container = current == null ? 0 : current.containerId();
        int state = current == null ? this.inventoryStateId : this.screenStateId;
        send(new ServerboundContainerClickPacket(container, state, (short) slot, (byte) button, input, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
    }

    /** Shift-clicks a slot of the open screen (quick move). */
    public void shiftClick(int slot) {
        clickSlot(slot, 0, ContainerInput.QUICK_MOVE);
    }

    /** Right-clicks a slot of the open screen. */
    public void rightClick(int slot) {
        clickSlot(slot, 1, ContainerInput.PICKUP);
    }

    /** Presses a hotbar number key (0-8) while hovering a slot: swaps that slot with the hotbar slot. */
    public void numberKey(int slot, int hotbar) {
        clickSlot(slot, hotbar, ContainerInput.SWAP);
    }

    /** Selects a hotbar slot (0-8), like scrolling or pressing its number outside a screen. */
    public void selectHotbar(int hotbar) {
        send(new ServerboundSetCarriedItemPacket(hotbar));
    }

    /** Drops the held item (Q), or the whole stack (Ctrl+Q); a modified client can send this with a screen open. */
    public void dropHeld(boolean wholeStack) {
        send(new ServerboundPlayerActionPacket(wholeStack ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
            : ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN, 0));
    }

    /** Swaps the held item with the off hand (F). */
    public void swapHands() {
        send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO,
            Direction.DOWN, 0));
    }

    /** Types into the open anvil's name field. */
    public void renameInAnvil(String name) {
        send(new ServerboundRenameItemPacket(name));
    }

    /**
     * Finishes the open sign editor with these lines (missing lines are empty), like pressing Done. Returns false
     * when no sign editor was opened.
     */
    public boolean signDone(String... lines) {
        SignEditor editor = this.signEditor;
        if (editor == null) {
            return false;
        }
        String[] four = new String[] {"", "", "", ""};
        for (int i = 0; i < Math.min(4, lines.length); i++) {
            four[i] = lines[i];
        }
        send(new ServerboundSignUpdatePacket(editor.pos(), editor.front(), four[0], four[1], four[2], four[3]));
        this.signEditor = null;
        return true;
    }

    public void closeScreen() {
        Screen current = this.screen;
        if (current != null) {
            send(new ServerboundContainerClosePacket(current.containerId()));
            this.screen = null;
            this.screenItems = Map.of();
        }
    }

    // ------------------------------------------------------------------ waiting

    /** Polls until the condition holds or the timeout passes. */
    public static boolean await(BooleanSupplier condition, long timeoutMillis) {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (RuntimeException ignored) {
                // Condition not ready yet.
            }
        }
        return condition.getAsBoolean();
    }

    // ------------------------------------------------------------------ protocol plumbing

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> iface) {
        InvocationHandler handler = (proxy, method, args) -> dispatch(proxy, iface, method, args);
        return (T) Proxy.newProxyInstance(Bot.class.getClassLoader(), new Class<?>[] {iface}, handler);
    }

    private Object dispatch(Object proxy, Class<?> iface, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "isAcceptingMessages":
                return this.conn != null && this.conn.isConnected();
            case "onDisconnect":
                this.disconnected = true;
                // The kick message arrives in a disconnect packet first; the generic reason the connection reports
                // when it then closes must not replace it.
                if (this.disconnectReason.isEmpty()) {
                    this.disconnectReason = ((DisconnectionDetails) args[0]).reason().getString();
                }
                return null;
            case "onPacketError":
                LOG.warning("[" + this.name + "] packet error " + args[0] + ": " + args[1]);
                return null;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "Bot$" + iface.getSimpleName();
            default:
                break;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        // Vanilla listener methods are mostly handleX(packet), but some are named differently
        // (setActionBarText, setTitleText, ...): forward every single-packet callback.
        if (args == null || args.length != 1 || !(args[0] instanceof Packet<?>)) {
            return null;
        }
        try {
            onPacket(proxy, args[0]);
        } catch (Throwable t) {
            LOG.warning("[" + this.name + "] handler failed: " + t);
        }
        return null;
    }

    private void onPacket(Object proxy, Object packet) {
        switch (packet) {
            case ClientboundLoginCompressionPacket c -> this.conn.setupCompression(c.getCompressionThreshold(), false);
            case ClientboundCustomQueryPacket q -> send(new ServerboundCustomQueryAnswerPacket(q.transactionId(), null));
            case ClientboundLoginDisconnectPacket d -> {
                this.disconnected = true;
                this.disconnectReason = d.reason().getString();
            }
            case ClientboundCookieRequestPacket c -> send(new ServerboundCookieResponsePacket(c.key(), null));
            case net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket f -> this.exec.execute(() -> {
                this.conn.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, proxy(ClientConfigurationPacketListener.class));
                this.conn.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                this.conn.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                this.conn.send(new ServerboundClientInformationPacket(new ClientInformation("en_us", 2, ChatVisiblity.FULL, true,
                    0x7F, HumanoidArm.RIGHT, false, true, ParticleStatus.ALL)));
            });
            case ClientboundKeepAlivePacket k -> send(new ServerboundKeepAlivePacket(k.getId()));
            case ClientboundPingPacket ping -> send(new ServerboundPongPacket(ping.getId()));
            case ClientboundDisconnectPacket d -> {
                this.disconnected = true;
                this.disconnectReason = d.reason().getString();
            }
            case ClientboundShowDialogPacket sd -> {
                SeenDialog seen = parse(sd.dialog().value());
                this.dialog = seen;
                this.dialogs.add(seen);
            }
            case ClientboundClearDialogPacket c -> {
                this.dialog = null;
                this.dialogsCleared.incrementAndGet();
            }
            case ClientboundSelectKnownPacks k -> send(new ServerboundSelectKnownPacks(k.knownPacks()));
            case net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket rd -> {
                List<String> ids = new ArrayList<>();
                for (var entry : rd.entries()) {
                    ids.add(entry.id().toString());
                }
                this.registries.put(rd.registry().identifier().toString(), List.copyOf(ids));
            }
            case net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket ut -> ut.getTags().forEach((registry, payload) -> {
                Map<String, List<Integer>> byTag = new java.util.HashMap<>();
                try {
                    java.lang.reflect.Field field = payload.getClass().getDeclaredField("tags");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<Object, it.unimi.dsi.fastutil.ints.IntList> raw = (Map<Object, it.unimi.dsi.fastutil.ints.IntList>) field.get(payload);
                    raw.forEach((tag, ids) -> byTag.put(tag.toString(), List.copyOf(ids)));
                } catch (ReflectiveOperationException e) {
                    byTag.put("(unreadable: " + e + ")", List.of());
                }
                this.tags.put(registry.identifier().toString(), byTag);
            });
            case net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket f -> this.exec.execute(() -> {
                this.conn.setupInboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.registryAccess)),
                    proxy(ClientGamePacketListener.class));
                this.conn.send(ServerboundFinishConfigurationPacket.INSTANCE);
                this.conn.setupOutboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.registryAccess), () -> false));
            });
            case ClientboundBundlePacket b -> {
                for (Packet<? super ClientGamePacketListener> sub : b.subPackets()) {
                    sub.handle((ClientGamePacketListener) proxy);
                }
            }
            case ClientboundPlayerPositionPacket pp -> {
                var change = pp.change();
                this.x = change.position().x;
                this.y = change.position().y;
                this.z = change.position().z;
                this.yRot = change.yRot();
                this.xRot = change.xRot();
                send(new ServerboundAcceptTeleportationPacket(pp.id()));
                send(new ServerboundMovePlayerPacket.PosRot(this.x, this.y, this.z, this.yRot, this.xRot, true, false));
                if (!this.loaded) {
                    this.loaded = true;
                    send(new ServerboundPlayerLoadedPacket());
                }
            }
            case ClientboundOpenScreenPacket os -> {
                this.contentFor = -1;
                this.screenItems = Map.of();
                this.screen = new Screen(os.getContainerId(), os.getTitle().getString(), System.currentTimeMillis(),
                    String.valueOf(BuiltInRegistries.MENU.getKey(os.getType())), os.getTitle());
            }
            case ClientboundOpenSignEditorPacket se -> this.signEditor = new SignEditor(se.getPos(), se.isFrontText(), System.currentTimeMillis());
            case ClientboundContainerClosePacket cc -> {
                this.screen = null;
                this.screenItems = Map.of();
            }
            case ClientboundContainerSetContentPacket cc -> {
                if (cc.containerId() == 0) {
                    this.inventoryStateId = cc.stateId();
                } else {
                    this.screenStateId = cc.stateId();
                    Map<Integer, ItemStack> filled = new java.util.HashMap<>();
                    List<ItemStack> items = cc.items();
                    for (int i = 0; i < items.size(); i++) {
                        if (!items.get(i).isEmpty()) {
                            filled.put(i, items.get(i));
                        }
                    }
                    this.screenItems = Map.copyOf(filled);
                    this.contentFor = cc.containerId();
                }
            }
            case ClientboundContainerSetSlotPacket slot -> {
                if (slot.getContainerId() != 0) {
                    Map<Integer, ItemStack> changed = new java.util.HashMap<>(this.screenItems);
                    if (slot.getItem().isEmpty()) {
                        changed.remove(slot.getSlot());
                    } else {
                        changed.put(slot.getSlot(), slot.getItem());
                    }
                    this.screenItems = Map.copyOf(changed);
                }
            }
            case ClientboundSystemChatPacket sc -> {
                (sc.overlay() ? this.actionBar : this.chat).add(sc.content().getString());
                if (!sc.overlay()) {
                    this.chatComponents.add(sc.content());
                }
                SeenDialog embedded = sc.overlay() ? null : embeddedDialog(sc.content());
                if (embedded != null) {
                    this.chatDialogs.add(new ChatDialog(sc.content().getString(), embedded));
                }
            }
            case net.minecraft.network.protocol.game.ClientboundPlayerChatPacket pc -> {
                Component content = pc.unsignedContent() != null ? pc.unsignedContent() : Component.literal(pc.body().content());
                this.chatComponents.add(content);
                this.chat.add(content.getString());
            }
            case net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket dc -> {
                this.chatComponents.add(dc.message());
                this.chat.add(dc.message().getString());
            }
            case ClientboundSetActionBarTextPacket ab -> this.actionBar.add(ab.text().getString());
            case ClientboundSetTitleTextPacket t -> this.titles.add(t.text().getString());
            case net.minecraft.network.protocol.game.ClientboundSoundEntityPacket s -> this.sounds.add(new SeenSound(soundId(s.getSound()),
                s.getVolume(), s.getPitch(), System.currentTimeMillis()));
            case net.minecraft.network.protocol.game.ClientboundSoundPacket s -> this.sounds.add(new SeenSound(soundId(s.getSound()),
                s.getVolume(), s.getPitch(), System.currentTimeMillis()));
            case ClientboundSetHealthPacket health -> {
                // A player who logged in dead gets no death screen packet, only their health: respawn like a client.
                if (health.getHealth() <= 0) {
                    deathScreen();
                }
            }
            case ClientboundPlayerCombatKillPacket ck -> {
                this.deaths.incrementAndGet();
                deathScreen();
            }
            case ClientboundAddEntityPacket add -> {
                String type = BuiltInRegistries.ENTITY_TYPE.getKey(add.getType()).toString();
                this.everEntityTypes.add(type);
                this.entities.put(add.getId(), new SeenEntity(add.getId(), add.getUUID(), type, add.getX(), add.getY(), add.getZ(),
                    new ConcurrentHashMap<>()));
            }
            case net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket particle -> {
                var key = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getParticle().getType());
                this.particles.computeIfAbsent(key == null ? "unknown" : key.toString(), k -> new AtomicInteger()).incrementAndGet();
            }
            case ClientboundSetEntityDataPacket data -> {
                SeenEntity entity = this.entities.get(data.id());
                if (entity != null) {
                    for (SynchedEntityData.DataValue<?> value : data.packedItems()) {
                        if (value.value() != null) {
                            entity.data().put(value.id(), value.value());
                        }
                    }
                }
            }
            case ClientboundRemoveEntitiesPacket removed -> {
                var ids = removed.getEntityIds().iterator();
                while (ids.hasNext()) {
                    this.entities.remove(ids.nextInt());
                }
            }
            case ClientboundPlayerInfoUpdatePacket info -> {
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.newEntries()) {
                    this.playerInfo.add(entry.profileId());
                    this.everPlayerInfo.add(entry.profileId());
                }
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
                    if (info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) {
                        if (entry.displayName() == null) {
                            this.listNames.remove(entry.profileId());
                        } else {
                            this.listNames.put(entry.profileId(), entry.displayName());
                        }
                    }
                    if (info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER)) {
                        this.listOrders.put(entry.profileId(), entry.listOrder());
                    }
                }
            }
            case ClientboundSetObjectivePacket objective -> {
                String name = objective.getObjectiveName();
                if (objective.getMethod() == 1) {
                    this.objectives.remove(name);
                    this.scores.remove(name);
                    this.displaySlots.values().removeIf(name::equals);
                } else {
                    this.objectives.put(name, new SeenObjective(name, objective.getDisplayName(),
                        objective.getNumberFormat().map(format -> format.getClass().getSimpleName()).orElse("default")));
                }
            }
            case ClientboundSetDisplayObjectivePacket display -> {
                String slot = display.getSlot().getSerializedName();
                if (display.getObjectiveName() == null) {
                    this.displaySlots.remove(slot);
                } else {
                    this.displaySlots.put(slot, display.getObjectiveName());
                }
            }
            case ClientboundSetScorePacket score -> {
                int number = this.scorePackets.incrementAndGet();
                this.scores.computeIfAbsent(score.objectiveName(), k -> new ConcurrentHashMap<>()).put(score.owner(),
                    new SeenScore(score.owner(), score.score(), score.display().orElse(null),
                        score.numberFormat().map(format -> format.getClass().getSimpleName()).orElse("default"), number));
            }
            case ClientboundResetScorePacket reset -> {
                if (reset.objectiveName() == null) {
                    this.scores.values().forEach(map -> map.remove(reset.owner()));
                } else {
                    Map<String, SeenScore> map = this.scores.get(reset.objectiveName());
                    if (map != null) {
                        map.remove(reset.owner());
                    }
                }
            }
            case ClientboundSetPlayerTeamPacket team -> {
                this.teamPackets.incrementAndGet();
                String name = team.getName();
                if (team.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                    this.teams.remove(name);
                } else {
                    SeenTeam current = this.teams.get(name);
                    Set<String> members = new java.util.TreeSet<>(current == null ? Set.of() : current.members());
                    Component prefix = current == null ? Component.empty() : current.prefix();
                    Component suffix = current == null ? Component.empty() : current.suffix();
                    String color = current == null ? "none" : current.color();
                    byte options = current == null ? 0 : current.options();
                    if (team.getParameters().isPresent()) {
                        ClientboundSetPlayerTeamPacket.Parameters parameters = team.getParameters().get();
                        prefix = parameters.playerPrefix();
                        suffix = parameters.playerSuffix();
                        color = parameters.color().map(c -> c.getSerializedName()).orElse("none");
                        options = parameters.options();
                    }
                    if (team.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                        members.addAll(team.getPlayers());
                    } else if (team.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                        members.removeAll(team.getPlayers());
                    }
                    this.teams.put(name, new SeenTeam(name, prefix, suffix, color, options, Set.copyOf(members)));
                }
            }
            case ClientboundBossEventPacket boss -> {
                this.bossBarPackets.incrementAndGet();
                boss.dispatch(new ClientboundBossEventPacket.Handler() {
                    @Override
                    public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
                                    boolean darken, boolean music, boolean fog) {
                        Bot.this.bossBars.put(id, new SeenBossBar(id, name, progress, color.name(), overlay.name()));
                    }

                    @Override
                    public void remove(UUID id) {
                        Bot.this.bossBars.remove(id);
                    }

                    @Override
                    public void updateProgress(UUID id, float progress) {
                        Bot.this.bossBars.computeIfPresent(id, (key, bar) -> new SeenBossBar(id, bar.text(), progress, bar.color(), bar.overlay()));
                    }

                    @Override
                    public void updateName(UUID id, Component name) {
                        Bot.this.bossBars.computeIfPresent(id, (key, bar) -> new SeenBossBar(id, name, bar.progress(), bar.color(), bar.overlay()));
                    }

                    @Override
                    public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
                        Bot.this.bossBars.computeIfPresent(id, (key, bar) -> new SeenBossBar(id, bar.text(), bar.progress(), color.name(),
                            overlay.name()));
                    }
                });
            }
            case ClientboundTabListPacket tab -> {
                this.tabListPackets.incrementAndGet();
                this.tabHeader = tab.header();
                this.tabFooter = tab.footer();
            }
            case ClientboundPlayerInfoRemovePacket remove -> remove.profileIds().forEach(this.playerInfo::remove);
            default -> {
                if (System.getProperty("sift.e2e.trace") != null) {
                    LOG.info("[" + this.name + "] packet " + packet.getClass().getSimpleName());
                }
            }
        }
    }

    /** Answers a death screen with one respawn, however many packets announced it. */
    private void deathScreen() {
        long now = System.currentTimeMillis();
        if (now - this.lastDeathScreen > 2_000 && !this.exec.isShutdown()) {
            this.lastDeathScreen = now;
            this.exec.execute(this::respawn);
        }
    }

    private static SeenDialog parse(Dialog dialog) {
        List<Button> buttons = new ArrayList<>();
        String type;
        net.minecraft.server.dialog.CommonDialogData common;
        switch (dialog) {
            case NoticeDialog notice -> {
                type = "notice";
                common = notice.common();
                buttons.add(button(notice.action()));
            }
            case ConfirmationDialog confirm -> {
                type = "confirm";
                common = confirm.common();
                buttons.add(button(confirm.yesButton()));
                buttons.add(button(confirm.noButton()));
            }
            case MultiActionDialog multi -> {
                type = "multi";
                common = multi.common();
                for (ActionButton action : multi.actions()) {
                    buttons.add(button(action));
                }
                multi.exitAction().ifPresent(exit -> buttons.add(button(exit)));
            }
            default -> {
                return new SeenDialog(dialog.getClass().getSimpleName(), "", List.of(), List.of(), Map.of(), System.currentTimeMillis(), "",
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
            }
        }
        List<String> body = new ArrayList<>();
        for (var element : common.body()) {
            if (element instanceof PlainMessage message) {
                body.add(message.contents().getString());
            } else if (element instanceof net.minecraft.server.dialog.body.ItemBody item) {
                item.description().ifPresent(description -> body.add(description.contents().getString()));
            }
        }
        Map<String, String> inputs = new java.util.LinkedHashMap<>();
        Map<String, String> initial = new java.util.LinkedHashMap<>();
        Map<String, Boolean> initials = new java.util.LinkedHashMap<>();
        Map<String, List<String>> options = new java.util.LinkedHashMap<>();
        Map<String, String> choiceInitial = new java.util.LinkedHashMap<>();
        Map<String, List<String>> optionLabels = new java.util.LinkedHashMap<>();
        Map<String, RangeSeen> ranges = new java.util.LinkedHashMap<>();
        for (Input input : common.inputs()) {
            if (input.control() instanceof BooleanInput toggle) {
                initials.put(input.key(), toggle.initial());
            }
            if (input.control() instanceof SingleOptionInput single) {
                List<String> ids = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                String start = null;
                for (SingleOptionInput.Entry entry : single.entries()) {
                    ids.add(entry.id());
                    labels.add(entry.displayOrDefault().getString());
                    if (entry.initial() && start == null) {
                        start = entry.id();
                    }
                }
                options.put(input.key(), List.copyOf(ids));
                optionLabels.put(input.key(), List.copyOf(labels));
                // Like the client: the flagged entry, else the first.
                choiceInitial.put(input.key(), start != null ? start : ids.isEmpty() ? "" : ids.getFirst());
            }
            if (input.control() instanceof NumberRangeInput slider) {
                NumberRangeInput.RangeInfo info = slider.rangeInfo();
                ranges.put(input.key(), new RangeSeen(info.start(), info.end(), info.step().orElse(null),
                    info.initial().orElse((info.start() + info.end()) / 2f), slider.labelFormat(), slider.label().getString()));
            }
            if (input.control() instanceof TextInput text) {
                initial.put(input.key(), text.initial());
            }
            inputs.put(input.key(), switch (input.control()) {
                case TextInput t -> "text";
                case BooleanInput b -> "toggle";
                case SingleOptionInput s -> "choice";
                case NumberRangeInput n -> "range";
                default -> "unknown";
            });
        }
        return new SeenDialog(type, common.title().getString(), body, buttons, inputs, System.currentTimeMillis(),
            common.afterAction().getSerializedName(), initial, initials, options, choiceInitial, optionLabels, ranges);
    }

    /** A sound's id as the client knows it. */
    private static String soundId(net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound) {
        try {
            return sound.value().location().toString();
        } catch (RuntimeException e) {
            return sound.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
        }
    }

    /** The dialog a chat component opens when clicked (its own or a child's show_dialog click event), or null. */
    private static SeenDialog embeddedDialog(Component component) {
        if (component.getStyle().getClickEvent() instanceof ClickEvent.ShowDialog show) {
            return parse(show.dialog().value());
        }
        for (Component sibling : component.getSiblings()) {
            SeenDialog found = embeddedDialog(sibling);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Button button(ActionButton action) {
        String label = action.button().label().getString();
        if (action.action().isPresent() && action.action().get() instanceof CustomAll custom) {
            return new Button(label, custom.id().toString(), custom.additions().orElse(null),
                action.button().tooltip().map(Component::getString).orElse(null), action.button().label());
        }
        return new Button(label, null, null, action.button().tooltip().map(Component::getString).orElse(null), action.button().label());
    }

    @Override
    public String toString() {
        return this.name;
    }
}
