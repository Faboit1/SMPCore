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
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
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

    /** A dialog as the client received it. */
    public record SeenDialog(String type, String title, List<String> body, List<Button> buttons, Map<String, String> inputs, long at) {
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

    /** A dialog button and the custom click it sends. */
    public record Button(String label, String actionId, CompoundTag additions) {
    }

    /** An open container screen. */
    public record Screen(int containerId, String title, long at) {
    }

    /** A chat message that opens a dialog when clicked (a show_dialog click event), and that dialog. */
    public record ChatDialog(String text, SeenDialog dialog) {
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
    private final Map<Integer, ItemStack> screenItems = new ConcurrentHashMap<>();
    private final List<String> chat = new CopyOnWriteArrayList<>();
    private final List<String> actionBar = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final List<SeenDialog> dialogs = new CopyOnWriteArrayList<>();
    private final List<ChatDialog> chatDialogs = new CopyOnWriteArrayList<>();
    private final Map<Integer, SeenEntity> entities = new ConcurrentHashMap<>();
    private final AtomicInteger deaths = new AtomicInteger();
    private final AtomicInteger dialogsCleared = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Set<UUID> playerInfo = ConcurrentHashMap.newKeySet();
    private final Set<UUID> everPlayerInfo = ConcurrentHashMap.newKeySet();

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

    public SeenDialog dialog() {
        return this.dialog;
    }

    public List<SeenDialog> dialogs() {
        return List.copyOf(this.dialogs);
    }

    public Screen screen() {
        return this.screen;
    }

    public Map<Integer, ItemStack> screenItems() {
        return Map.copyOf(this.screenItems);
    }

    public List<String> chat() {
        return List.copyOf(this.chat);
    }

    public List<String> actionBar() {
        return List.copyOf(this.actionBar);
    }

    public List<String> titles() {
        return List.copyOf(this.titles);
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

    /** Forgets everything received so far (start of a step). */
    public void clearLogs() {
        this.chat.clear();
        this.actionBar.clear();
        this.titles.clear();
        this.dialogs.clear();
        this.chatDialogs.clear();
        this.dialog = null;
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

    public void respawn() {
        send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    /** Right-clicks an entity with the main hand, like the vanilla client does. */
    public void interact(int entityId) {
        send(new ServerboundInteractPacket(entityId, InteractionHand.MAIN_HAND, Vec3.ZERO, false));
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

    public void closeScreen() {
        Screen current = this.screen;
        if (current != null) {
            send(new ServerboundContainerClosePacket(current.containerId()));
            this.screen = null;
            this.screenItems.clear();
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
                this.disconnectReason = ((DisconnectionDetails) args[0]).reason().getString();
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
                this.screenItems.clear();
                this.screen = new Screen(os.getContainerId(), os.getTitle().getString(), System.currentTimeMillis());
            }
            case ClientboundContainerClosePacket cc -> {
                this.screen = null;
                this.screenItems.clear();
            }
            case ClientboundContainerSetContentPacket cc -> {
                if (cc.containerId() == 0) {
                    this.inventoryStateId = cc.stateId();
                } else {
                    this.screenStateId = cc.stateId();
                    this.screenItems.clear();
                    List<ItemStack> items = cc.items();
                    for (int i = 0; i < items.size(); i++) {
                        if (!items.get(i).isEmpty()) {
                            this.screenItems.put(i, items.get(i));
                        }
                    }
                }
            }
            case ClientboundContainerSetSlotPacket slot -> {
                if (slot.getContainerId() != 0) {
                    if (slot.getItem().isEmpty()) {
                        this.screenItems.remove(slot.getSlot());
                    } else {
                        this.screenItems.put(slot.getSlot(), slot.getItem());
                    }
                }
            }
            case ClientboundSystemChatPacket sc -> {
                (sc.overlay() ? this.actionBar : this.chat).add(sc.content().getString());
                SeenDialog embedded = sc.overlay() ? null : embeddedDialog(sc.content());
                if (embedded != null) {
                    this.chatDialogs.add(new ChatDialog(sc.content().getString(), embedded));
                }
            }
            case net.minecraft.network.protocol.game.ClientboundPlayerChatPacket pc ->
                this.chat.add(pc.unsignedContent() != null ? pc.unsignedContent().getString() : pc.body().content());
            case net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket dc -> this.chat.add(dc.message().getString());
            case ClientboundSetActionBarTextPacket ab -> this.actionBar.add(ab.text().getString());
            case ClientboundSetTitleTextPacket t -> this.titles.add(t.text().getString());
            case ClientboundPlayerCombatKillPacket ck -> {
                this.deaths.incrementAndGet();
                this.exec.execute(this::respawn);
            }
            case ClientboundAddEntityPacket add -> this.entities.put(add.getId(), new SeenEntity(add.getId(), add.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(add.getType()).toString(), add.getX(), add.getY(), add.getZ(), new ConcurrentHashMap<>()));
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
            }
            case ClientboundPlayerInfoRemovePacket remove -> remove.profileIds().forEach(this.playerInfo::remove);
            default -> {
                if (System.getProperty("sift.e2e.trace") != null) {
                    LOG.info("[" + this.name + "] packet " + packet.getClass().getSimpleName());
                }
            }
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
                return new SeenDialog(dialog.getClass().getSimpleName(), "", List.of(), List.of(), Map.of(), System.currentTimeMillis());
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
        for (Input input : common.inputs()) {
            inputs.put(input.key(), switch (input.control()) {
                case TextInput t -> "text";
                case BooleanInput b -> "toggle";
                case SingleOptionInput s -> "choice";
                case NumberRangeInput n -> "range";
                default -> "unknown";
            });
        }
        return new SeenDialog(type, common.title().getString(), body, buttons, inputs, System.currentTimeMillis());
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
            return new Button(label, custom.id().toString(), custom.additions().orElse(null));
        }
        return new Button(label, null, null);
    }

    @Override
    public String toString() {
        return this.name;
    }
}
