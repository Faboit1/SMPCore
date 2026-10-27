package net.siftvanilla.siftcore.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.config.TestSettings;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The commands.yml cooldown applies to every command, through the gate {@link CommandService} puts in front of each
 * node, and a feature that also checks it while its command runs is not charged twice.
 */
class CommandCooldownTest {

    /** A player built as a proxy: permissions are configurable, action bar lines are recorded. */
    private static final class FakePlayer {
        final UUID id = UUID.randomUUID();
        final List<String> actionBar = new ArrayList<>();
        boolean bypass;
        final Player player = (Player) Proxy.newProxyInstance(CommandCooldownTest.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> this.id;
                case "getName" -> "Tester";
                case "hasPermission" -> "siftcore.bypass.cooldown".equals(args[0]) && this.bypass;
                case "sendActionBar" -> {
                    this.actionBar.add(PlainTextComponentSerializer.plainText().serialize((Component) args[0]));
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakePlayer";
                default -> null;
            });
    }

    private static CommandSourceStack source(CommandSender sender) {
        return (CommandSourceStack) Proxy.newProxyInstance(CommandCooldownTest.class.getClassLoader(), new Class<?>[] {CommandSourceStack.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getSender" -> sender;
                case "getExecutor" -> sender instanceof Player ? sender : null;
                default -> null;
            });
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(CommandCooldownTest.class.getClassLoader(), new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> true;
                case "getName" -> "CONSOLE";
                default -> null;
            });
    }

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = CommandCooldownTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private Setting<CommandSettings> settings;
    private CommandSupport support;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private final AtomicInteger runs = new AtomicInteger();
    private final List<Boolean> innerChecks = new ArrayList<>();

    private static CommandSettings cooldowns(Map<String, String> byCommand) {
        YamlConfiguration yaml = new YamlConfiguration();
        byCommand.forEach((command, cooldown) -> yaml.set("commands." + command + ".cooldown", cooldown));
        return CommandSettings.parse(new ConfigReader("commands.yml", yaml));
    }

    @BeforeEach
    void setUp() throws Exception {
        Icons icons = new Icons(Icons.readIndex(getClass().getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(CoreMessages.class);
        YamlConfiguration core = yaml("lang/core.yml");
        lang.load(core, core, "lang/core.yml");
        this.settings = TestSettings.of(cooldowns(Map.of("pay", "30s")));
        this.support = new CommandSupport(new Messenger(lang, new Sounds()), null, new Cooldowns(), this.settings, MoneyFormat::defaults);
        this.dispatcher = new CommandDispatcher<>();
        for (String name : List.of("pay", "menu")) {
            LiteralCommandNode<CommandSourceStack> tree = LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .executes(ctx -> body(ctx.getSource(), name))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("sub").executes(ctx -> body(ctx.getSource(), name)))
                .build();
            this.dispatcher.getRoot().addChild(CommandTrees.wrapCommands(tree, body -> this.support.withCooldown(name, body)));
        }
    }

    /** A command body that, like orders or the auction house, also checks its commands.yml cooldown itself. */
    private int body(CommandSourceStack source, String name) {
        this.runs.incrementAndGet();
        if (source.getSender() instanceof Player player) {
            this.innerChecks.add(this.support.cooldown(player, name));
        }
        return CommandSupport.OK;
    }

    private void run(CommandSender sender, String command) throws CommandSyntaxException {
        this.dispatcher.execute(command, source(sender));
    }

    @Test
    void aConfiguredCooldownHoldsTheNextRunOfAnyCommand() throws Exception {
        FakePlayer fake = new FakePlayer();
        run(fake.player, "pay");
        assertEquals(1, this.runs.get());
        run(fake.player, "pay sub");
        assertEquals(1, this.runs.get(), "the subcommand waits for the cooldown too");
        assertEquals(1, fake.actionBar.size(), "one cooldown message: " + fake.actionBar);
        assertTrue(fake.actionBar.getFirst().matches("Wait (29|30)s before doing that again\\."), fake.actionBar.getFirst());
    }

    @Test
    void aFeatureThatChecksItAgainIsNotChargedTwice() throws Exception {
        FakePlayer fake = new FakePlayer();
        run(fake.player, "pay");
        assertEquals(List.of(true), this.innerChecks, "the feature's own check passes during the run that was charged");
        assertTrue(fake.actionBar.isEmpty());
        assertFalse(this.support.cooldown(fake.player, "pay"), "outside the run, the cooldown is running");
    }

    @Test
    void anActionOnTheCommandsScreensIsNotHeldByOpeningThem() throws Exception {
        TestSettings.reload(this.settings, cooldowns(Map.of("friend", "30s")));
        List<Boolean> requests = new ArrayList<>();
        this.dispatcher.getRoot().addChild(CommandTrees.wrapCommands(LiteralArgumentBuilder.<CommandSourceStack>literal("friend")
            .executes(ctx -> CommandSupport.OK)
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("add").executes(ctx -> {
                requests.add(this.support.cooldown((Player) ctx.getSource().getSender(), "friend", "request"));
                return CommandSupport.OK;
            }))
            .build(), body -> this.support.withCooldown("friend", body)));

        FakePlayer opener = new FakePlayer();
        run(opener.player, "friend");
        assertFalse(this.support.cooldown(opener.player, "friend"), "the command's own key is held after opening /friend");
        opener.actionBar.clear();
        assertTrue(this.support.cooldown(opener.player, "friend", "request"), "a request from the screens still goes out");
        assertEquals(List.of(), opener.actionBar);
        assertFalse(this.support.cooldown(opener.player, "friend", "request"), "the next request waits");
        assertEquals(1, opener.actionBar.size(), "one cooldown message: " + opener.actionBar);

        FakePlayer adder = new FakePlayer();
        run(adder.player, "friend add");
        assertEquals(List.of(true), requests, "a request by command charges the request once");
        assertFalse(this.support.cooldown(adder.player, "friend", "request"), "a request from the screens right after waits");

        FakePlayer staff = new FakePlayer();
        staff.bypass = true;
        assertTrue(this.support.cooldown(staff.player, "friend", "request") && this.support.cooldown(staff.player, "friend", "request"),
            "the bypass skips it");
        TestSettings.reload(this.settings, cooldowns(Map.of()));
        assertTrue(this.support.cooldown(adder.player, "friend", "request"), "without a configured cooldown nothing waits");
    }

    @Test
    void commandsWithoutACooldownAndTheBypassAreNeverHeld() throws Exception {
        FakePlayer fake = new FakePlayer();
        run(fake.player, "menu");
        run(fake.player, "menu sub");
        assertEquals(2, this.runs.get());
        FakePlayer staff = new FakePlayer();
        staff.bypass = true;
        run(staff.player, "pay");
        run(staff.player, "pay");
        assertEquals(4, this.runs.get());
        assertTrue(staff.actionBar.isEmpty() && fake.actionBar.isEmpty());
    }

    @Test
    void theConsoleIsNeverHeld() throws Exception {
        CommandSender console = console();
        run(console, "pay");
        run(console, "pay");
        assertEquals(2, this.runs.get());
    }

    @Test
    void aReloadedCooldownAppliesWithoutReRegistering() throws Exception {
        FakePlayer fake = new FakePlayer();
        run(fake.player, "menu");
        run(fake.player, "menu");
        assertEquals(2, this.runs.get());
        TestSettings.reload(this.settings, cooldowns(Map.of("menu", "5s")));
        run(fake.player, "menu");
        run(fake.player, "menu");
        assertEquals(3, this.runs.get());
    }

    @Test
    void wrappingKeepsTheTree() throws Exception {
        AtomicInteger wrapped = new AtomicInteger();
        LiteralCommandNode<Object> tree = LiteralArgumentBuilder.<Object>literal("sell")
            .executes(ctx -> 1)
            .then(LiteralArgumentBuilder.<Object>literal("hidden").requires(source -> false).executes(ctx -> 5))
            .then(RequiredArgumentBuilder.<Object, Integer>argument("amount", IntegerArgumentType.integer(1, 64))
                .suggests((ctx, builder) -> builder.suggest("32").buildFuture())
                .executes(ctx -> 2 + IntegerArgumentType.getInteger(ctx, "amount"))
                .then(LiteralArgumentBuilder.<Object>literal("confirm").executes(ctx -> 100)))
            .build();
        CommandDispatcher<Object> plain = new CommandDispatcher<>();
        plain.getRoot().addChild(CommandTrees.wrapCommands(tree, body -> ctx -> {
            wrapped.incrementAndGet();
            return body.run(ctx);
        }));
        Object source = new Object();
        assertEquals(1, plain.execute("sell", source));
        assertEquals(7, plain.execute("sell 5", source));
        assertEquals(100, plain.execute("sell 5 confirm", source));
        assertEquals(3, wrapped.get(), "every executed node went through the wrapper");
        assertThrows(CommandSyntaxException.class, () -> plain.execute("sell hidden", source), "requirements are kept");
        assertThrows(CommandSyntaxException.class, () -> plain.execute("sell 65", source), "argument types are kept");
        List<String> suggested = plain.getCompletionSuggestions(plain.parse("sell ", source)).get().getList().stream()
            .map(Suggestion::getText).toList();
        assertTrue(suggested.contains("32"), "custom suggestions are kept: " + suggested);
        assertEquals(Duration.ofSeconds(30), this.settings.get().get("pay").cooldown());
    }
}
