# Canvas 26.2 runtime research: plugin lifecycle, threading, events (Track A)

Verified on 2026-10-07 against **Canvas 26.2-962-HEAD@2a3bf65** ("Implementing API version 26.2.build.962-stable"), Minecraft 26.2 (protocol 776, data pack format 107.1), Temurin 25.0.4.1.

**Method.** I built a probe plugin, `SiftProbe`, with `javac --release 25` against `paper-api-26.2.build.132-stable.jar` and booted it six times on a copy of the local server (`scratchpad/srv-A`, port 25611).

Player-bound behaviour was tested with **a real protocol client**. `LoopbackBot` runs inside the probe and connects to `127.0.0.1:25611` using the server's own `net.minecraft.network` codecs. It goes through handshake, login, configuration and play, then:
- moves, chats and runs commands
- sends custom click actions and dialog callbacks
- clicks its inventory and breaks blocks
- gets killed, kicked, and disconnects

Results marked **[src]** come from Vineflower 1.12.0 decompiles of `versions/26.2/canvas-26.2.jar`. Everything else was observed in the probe logs (`scratchpad/research/runtime/run1..run6-boot.log`).

Thread names on this box: the global region and every region tick share one pool, `Folia Region Scheduler Thread #N`, which had a single thread here. So **the thread name tells you nothing**. Use `Bukkit.isGlobalTickThread()` and `Bukkit.isOwnedByCurrentRegion(..)`; those are what the tables below report.

---

## 0. Rules for SiftCore (summary)

1. **`paper-plugin.yml` must declare `folia-supported: true`.** Without it the plugin is refused. Quote the api-version: `api-version: '26.2'`.
2. **Never call `Bukkit.getScheduler()`.** Every scheduling method throws `UnsupportedOperationException: Unsupported in region threading`, from every thread including `onEnable`. Use the four Folia schedulers instead (§5.1).
3. **Global-region-thread only.** Each of these throws `IllegalStateException` from any other thread:
   - `World#setGameRule`, `World#setTime`, `World#setChunkForceLoaded`
   - `Bukkit.dispatchCommand`
   - `ScoreboardManager#getNewScoreboard`
   - every structural or metadata scoreboard change: objectives, display slots, teams, team prefix/suffix/colour, objective name and number format

   Before the region scheduler starts, `onEnable` (startup "Server thread") is also allowed for scoreboards.
4. **Owning-region thread only.** Use `entity.getScheduler()` (or `RegionScheduler` for blocks) for:
   - entity and world state, block reads and writes
   - `Player#setScoreboard`, `openInventory`, `setGameMode`, potion effects
   - `World#getNearbyEntities`, `getHighestBlockYAt`
5. **Safe from any thread.** These are pure packet sends or thread-safe lookups:
   - `sendMessage`, `showDialog`, `closeDialog`, `sendPlayerListHeaderAndFooter`, `sendLinks`
   - `kick`, `teleportAsync`
   - `Bukkit.getOnlinePlayers()`, `Bukkit.getWorlds()`, `World#getSpawnLocation`, `getGameRuleValue`, `getServerLinks()`
6. **No reload exists on this build.** `/reload`, `/minecraft:reload` and `/datapack enable|disable` are not registered. `/bukkit:reload confirm`, `Server#reload()` and `Server#reloadData()` throw. So `LifecycleEvents.COMMANDS` and `TAGS` fire **once** (`cause=INITIAL`). SiftCore's `/sift reload` must reload its own config only; the command trees must be static.
7. **Never trust custom-click payloads.** The client can send any `custom_click_action` id with any NBT up to the decoder limit (§9). Paper hands you the payload unvalidated. Dialog callbacks are single-use by default, keyed by a global UUID, and not bound to a player.
8. **Save everything in `onDisable`.** `onDisable` runs on the `Region shutdown thread` *after* the region scheduler halts and *before* players are removed. Plugins never get `PlayerQuitEvent` for players still online at shutdown. Flush all online-player data synchronously in `onDisable`; scheduled tasks will not run any more.
9. **Wrong-thread calls can half-apply.** Canvas `region-scheduler.guard-severity` defaults to `THROW`, and a wrong-thread call can fail *after* partial side effects. Example: `Player#openInventory` off-thread sent `OpenScreen` to the client and then threw "Cannot init menu async".

---

## 1. API jars and Canvas-specific API

- At runtime the server bundles `io/canvasmc/canvas/canvas-api/26.2.build.962-stable`. This is a superset of `paper-api 26.2.build.132-stable`, adding 28 class files under `io.canvasmc.canvas.*`.
  - **Signatures that differ from Paper:**
    - `Server#isOwnedByCurrentRegion(Location, Vector, int)` and `(World, BoundingBox)`
    - `Server/Bukkit#getRegionTPS(Chunk|Location|World,int,int)` and `getRegionAverageTickTimes(..)`
    - `Server#getRegionThreadingTickRateManager()`
    - `Server#unloadWorldAsync(World|String, boolean, Consumer<io.canvasmc.canvas.WorldUnloadResult>)`
    - `Server#createRegionizedData(..)` / `getLocalRegionizedData(..)`
    - `World#getRegionizer()`
    - `PluginMeta#isFoliaSupported()`
    - `PluginDescriptionFile` keys `folia-supported` / `canvas-supported`
  - **Extra events:** `io.canvasmc.canvas.event.*`, e.g. `EntityTeleportAsyncEvent`, `PlayerRespawnAsyncEvent`, `PlayerPostRespawnAsyncEvent`, `WorldUnloadAsyncEvent`.
- **Canvas marks these `@io.canvasmc.canvas.Unsupported`** (runtime annotation, listed by reflection):
  - all of `BukkitScheduler`
  - all of `ServerTickManager`
  - `Server#reload()`, `#reloadData()`, `#restart()`, `#unloadWorld(..)`
  - `Datapack#setEnabled`
  - `World#getTickableTileEntityCount()`
  - every synchronous `Entity#teleport(..)` overload
- **Recommendation: compile SiftCore against `paper-api` 26.2.build.132.** Everything in this document compiles against it. Reach the Canvas-only extras (e.g. `getRegionTPS`) reflectively, if at all.

---

## 2. `paper-plugin.yml` schema (verified on this build)

Parser: `io.papermc.paper.plugin.provider.configuration.PaperPluginMeta` (Configurate, dashed keys) **[src]**.

| key | required | type / values | verified behaviour |
|---|---|---|---|
| `name` | yes | `^[A-Za-z\d _.-]+$`, no space; not `bukkit`/`minecraft`/`mojang`/`spigot`/`paper` | `name: Bad Name` → `Restricted name, cannot use 0x20 (space character) in a plugin name.` |
| `version` | yes | string | |
| `main` | yes | class (namespace-checked) | |
| `bootstrapper` | no | `io.papermc.paper.plugin.bootstrap.PluginBootstrap` impl | works (§3) |
| `loader` | no | `io.papermc.paper.plugin.loader.PluginLoader` impl | works; runs first, on `ServerMain` |
| `api-version` | **yes** | `"major.minor"` or `"major.minor.patch"`, must be ≥ 1.19 | `'26.2'` ✓, `'26.2.0'` ✓, `26.2` unquoted ✓, `'1.21.11'` ✓. `'26.3'` (newer than the server) ✓ *for Paper plugins* (no upper bound; Spigot `plugin.yml` plugins do check). `'1.18'` → `1.18.0 is too old for a paper plugin!`. `'26'` → format error. Missing → `A value is required for this field`. **Always quote** (an unquoted `1.20` becomes `1.2`). |
| `folia-supported` | effectively yes | bool | missing → `Could not load plugin 'X v1' as it is not marked as supporting Folia!` (same for Spigot `plugin.yml`). Canvas also accepts `canvas-supported: true` (non-portable). |
| `load` | no | `STARTUP` \| `POSTWORLD` (default) | `STARTUP` plugin enabled before worlds load (`worlds=0`) |
| `description`, `authors` (+`author`), `contributors`, `website`, `prefix` | no | | |
| `provides` | no | list | |
| `has-open-classloader` | no | bool, default false | |
| `dependencies.bootstrap.<Name>` / `dependencies.server.<Name>` | no | `load: BEFORE\|AFTER\|OMIT` (null = no ordering), `required` (default **true**), `join-classpath` (default **true**) | `load: BEFORE` = the other plugin loads first and we load after it. `required: true` + missing → `org.bukkit.plugin.UnknownDependencyException: Unknown/missing dependency plugins: [NoSuchDep]` (plugin shown red in `/plugins`). `required: false` + missing → loads fine. `join-classpath: true` → can see the dependency's classes; `false` → `ClassNotFoundException`. |
| `permissions` | no | same map format as `plugin.yml` (`description`, `default: op\|true\|false\|not op`, `children`) | **Supported.** Registered before `onEnable`; defaults and children applied (`siftprobe.secret default=false children={siftprobe.use=true}`). |
| `defaultPerm` | no | `PermissionDefault` | **camelCase key** (read literally by `PermissionConfigurationSerializer`). `defaultPerm: TRUE` applied; `default-perm: TRUE` silently ignored (stayed `op`). |

Working file (verbatim `resources/paper-plugin.yml` of the probe, loaded in every run):

```yaml
name: SiftProbe
version: '1.0.0'
main: dev.siftvanilla.probe.ProbePlugin
bootstrapper: dev.siftvanilla.probe.ProbeBootstrap
loader: dev.siftvanilla.probe.ProbeLoader
api-version: '26.2'
folia-supported: true
description: Canvas runtime probe for SiftCore research
authors: [SiftVanilla]
website: https://example.invalid
prefix: SiftProbe
load: POSTWORLD
dependencies:
  bootstrap:
    NoSuchBootstrapDep:
      load: BEFORE
      required: false
      join-classpath: false
  server:
    NoSuchServerDep:
      load: BEFORE
      required: false
      join-classpath: true
defaultPerm: OP
permissions:
  siftprobe.use:
    description: Use /siftprobe
    default: op
  siftprobe.secret:
    description: Secret subcommand
    default: false
    children:
      siftprobe.use: true
  siftprobe.everyone:
    description: Granted to everyone
    default: true
```

**Console permissions** (`config/paper-global.yml` `console.has-all-permissions: false`, the default): the console has op-default nodes but **not** `default: false` nodes. Evidence: `console hasPermission(siftprobe.use/secret/everyone) => true/false/true`.

**Loader and libraries.** `MavenLibraryResolver` with `MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR` (= `https://maven-central.storage-download.googleapis.com/maven2`) resolved `com.zaxxer:HikariCP:7.0.2` and its `slf4j-api` into `<server>/libraries/` at boot. The plugin then loaded `com.zaxxer.hikari.HikariConfig`.

Already visible to plugins from the server classpath (no shading needed):
- `org.sqlite.JDBC` (sqlite-jdbc 3.49.1.0)
- `com.mysql.cj.jdbc.Driver` (mysql-connector-j 9.2.0)
- snakeyaml 2.6, configurate-yaml 4.2, gson 2.14, fastutil 8.5.19

Adding Maven Central itself (`repo1.maven.org` / `repo.maven.apache.org`) as a repository logs a ToS warning **[src]** `MavenLibraryResolver#addRepository`; use the constant.

---

## 3. Bootstrapper: dialog registration, dialog tags, data directory

Order and threads observed:
1. `loader.classloader` runs on `ServerMain`.
2. `bootstrap()` runs on `ServerMain`, before worlds and before `CraftServer` exists. `Bukkit.getServer()` is `null` there, so make no server-dependent Bukkit calls. Pure utility classes such as `org.bukkit.configuration.file.YamlConfiguration` work.
3. Registry `compose` / `entryAdd`, `TAGS.preFlatten` / `postFlatten` and the bootstrap `COMMANDS` handler run during datapack load on `Worker-Main-1`.
4. `createPlugin`, `onLoad` and `onEnable` run on `Server thread`.

### 3.1 Working code (`ProbeBootstrap`)

```java
public final class ProbeBootstrap implements PluginBootstrap {
    public static final Key MENU_KEY = Key.key("siftprobe", "menu");
    public static final TypedKey<Dialog> MENU = TypedKey.create(RegistryKey.DIALOG, MENU_KEY);

    @Override public void bootstrap(final BootstrapContext ctx) {
        final var log = ctx.getLogger();
        // Data dir is usable here: ctx.getDataDirectory() == Path "plugins/SiftProbe" (relative to server root).
        try {
            final Path dir = ctx.getDataDirectory();
            Files.createDirectories(dir);
            final Path lang = dir.resolve("lang.yml");
            if (Files.exists(lang)) {
                final YamlConfiguration yc = YamlConfiguration.loadConfiguration(lang.toFile()); // Bukkit config API works here
                log.info("greeting = {}", yc.getString("greeting"));
            }
            Files.writeString(dir.resolve("bootstrap-marker.txt"), "written by bootstrapper");
        } catch (final IOException e) { log.error("data dir", e); }

        // Register a dialog (registry entry) - compose is the event for ADDING entries.
        ctx.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose().newHandler(event ->
            event.registry().register(MENU, b -> b
                .base(DialogBase.builder(Component.text("SiftProbe Menu"))
                    .externalTitle(Component.text("SiftProbe"))      // label of the pause-menu / quick-actions button
                    .canCloseWithEscape(true).pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(List.of(DialogBody.plainMessage(Component.text("Registered from the bootstrapper"))))
                    .build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.builder(Component.text("Custom click"))
                            .action(DialogAction.customClick(Key.key("siftprobe", "menu/custom"), null)).build(),
                        ActionButton.builder(Component.text("Run /siftprobe ping"))
                            .action(DialogAction.staticAction(ClickEvent.runCommand("/siftprobe ping"))).build()))
                    .exitAction(ActionButton.builder(Component.text("Close")).build())
                    .columns(2).build()))));

        // entryAdd fires for every entry (vanilla + plugin) - use it to MODIFY existing entries; filter by key.
        ctx.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.entryAdd()
            .newHandler(e -> log.info("entryAdd {}", e.key()))
            .filter(k -> k.namespace().equals("siftprobe")));

        // Tags: pause_screen_additions via preFlatten (TagEntry), quick_actions via postFlatten (TypedKey).
        ctx.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.preFlatten(RegistryKey.DIALOG), event ->
            event.registrar().addToTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS, List.of(TagEntry.valueEntry(MENU))));
        ctx.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG), event ->
            event.registrar().addToTag(DialogTagKeys.QUICK_ACTIONS, List.of(MENU)));
    }

    @Override public JavaPlugin createPlugin(final PluginProviderContext context) { return new ProbePlugin(); }
}
```

Imports used:
- `io.papermc.paper.plugin.bootstrap.{BootstrapContext,PluginBootstrap,PluginProviderContext}`
- `io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents`
- `io.papermc.paper.registry.{RegistryKey,TypedKey}`
- `io.papermc.paper.registry.event.RegistryEvents`
- `io.papermc.paper.registry.keys.tags.DialogTagKeys`
- `io.papermc.paper.registry.data.dialog.{ActionButton,DialogBase}`, `.action.DialogAction`, `.body.DialogBody`, `.type.DialogType`
- `io.papermc.paper.tag.TagEntry`
- `io.papermc.paper.dialog.Dialog`
- `net.kyori.adventure.{key.Key,text.Component,text.event.ClickEvent}`

### 3.2 Runtime check (from `onEnable`; also identical from the global thread and a command after startup)

```java
final Registry<Dialog> reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.DIALOG);
final Dialog d = reg.get(ProbeBootstrap.MENU_KEY);                         // non-null
final boolean has = reg.hasTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS);       // true
final Tag<Dialog> tag = reg.getTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS);   // io.papermc.paper.registry.tag.Tag
tag.values();                    // Collection<TypedKey<Dialog>>
tag.contains(ProbeBootstrap.MENU);
reg.getTagValues(DialogTagKeys.QUICK_ACTIONS); // Collection<Dialog>
```

Evidence:

```
PROBE DIALOG entryAdd key=...minecraft:server_links... thread=Worker-Main-1
PROBE DIALOG compose fired thread=Worker-Main-1
PROBE TAGS preFlatten(DIALOG) cause=INITIAL thread=Worker-Main-1 hasPause=true pauseBefore=[]
PROBE TAGS postFlatten(DIALOG) cause=INITIAL thread=Worker-Main-1 hasQuick=true quickBefore=[] allTags=[#minecraft:pause_screen_additions, #minecraft:quick_actions]
PROBE DIALOGCHECK [onEnable] registry size=4 siftprobe:menu present=true
PROBE DIALOGCHECK [onEnable] keys=[siftprobe:menu, minecraft:custom_options, minecraft:server_links, minecraft:quick_actions]
PROBE DIALOGCHECK [onEnable] tag minecraft:pause_screen_additions hasTag=true values=[siftprobe:menu] contains(menu)=true getTagValues=1
PROBE DIALOGCHECK [onEnable] tag minecraft:quick_actions hasTag=true values=[siftprobe:menu] contains(menu)=true getTagValues=1
# what the joining client received (loopback client, configuration phase):
PROBE BOT registry data minecraft:dialog entries=[minecraft:custom_options(known), minecraft:quick_actions(known), minecraft:server_links(known), siftprobe:menu(+data)]
PROBE BOT dialog tags (network ids) = {minecraft:quick_actions=[3], minecraft:pause_screen_additions=[3]}
```

Notes:
- Both vanilla dialog tags exist and are **empty** before plugins add to them.
- Expected vanilla client behaviour (from the vanilla design; not verified with a real client): with exactly one dialog in `pause_screen_additions`, the pause menu shows one button labelled with its `external_title`; with more than one, it shows a list via `minecraft:custom_options`. `quick_actions` works the same way, behind the "Quick Actions" keybind.
- Do not put `DialogAction.customClick(DialogActionCallback, Options)` in registry dialogs. Callbacks live in a global map with a use count (default 1) and a lifetime (default 12 h), so a permanent registry dialog would stop working after its first use or expiry (§9). Registry dialogs should use `customClick(Key, BinaryTagHolder)` and handle `PlayerCustomClickEvent`.
- Runtime dialogs: `Dialog.create(f -> f.empty().base(..).type(..))` gives a `PaperDialog{holder=Direct{...}}`, which can be shown with `Audience#showDialog`.

---

## 4. Brigadier commands

### 4.1 Working code (`onEnable`)

```java
this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
    final Commands commands = event.registrar();          // event.cause() == INITIAL (only cause seen on Canvas)
    final LiteralCommandNode<CommandSourceStack> root = Commands.literal("siftprobe")
        .requires(src -> src.getSender().hasPermission("siftprobe.use"))
        .then(Commands.literal("ping").executes(c -> { c.getSource().getSender().sendPlainMessage("pong"); return Command.SINGLE_SUCCESS; }))
        .then(Commands.literal("who").then(Commands.argument("target", ArgumentTypes.player()).executes(c -> {
            final PlayerSelectorArgumentResolver r = c.getArgument("target", PlayerSelectorArgumentResolver.class);
            final List<Player> players = r.resolve(c.getSource());   // throws CommandSyntaxException "No player was found"
            c.getSource().getSender().sendPlainMessage("who -> " + players.getFirst().getName());
            return Command.SINGLE_SUCCESS; })))
        .then(Commands.literal("secret").requires(src -> src.getSender().hasPermission("siftprobe.secret"))
            .executes(c -> Command.SINGLE_SUCCESS))
        .build();
    final Set<String> labels = commands.register(root, "SiftProbe test command", List.of("sp", "sprobe"));
});
```

The same `LifecycleEvents.COMMANDS` handler can also be registered from the bootstrapper (`ctx.getLifecycleManager()`). It then fires earlier, on `Worker-Main-1`; both kinds coexist.

### 4.2 Results

- **Registered labels** are `[sprobe, siftprobe:siftprobe, siftprobe:sprobe, siftprobe, siftprobe:sp, sp]`. Bootstrap-registered: `[probeboot, siftprobe:pboot, pboot, siftprobe:probeboot]`. The namespace is the plugin name lowercased.
- **Command map:** `Bukkit.getCommandMap().getCommand("siftprobe")` returns `io.papermc.paper.command.brigadier.PluginVanillaCommandWrapper` with `name=siftprobe`, `aliases=[sp, siftprobe:sp, sprobe, siftprobe:sprobe]`, `desc=SiftProbe test command`, and **`perm=null`**: the `requires` predicate is not exposed as a Bukkit permission.
- **Console** (FIFO stdin and `Bukkit.dispatchCommand`): `siftprobe ping`, `sp ping`, `sprobe ping`, `siftprobe:siftprobe ping` and `siftprobe:sp ping` all executed on the **global region thread** (`global=true`), `sender=CONSOLE`, `executor=null`.
- **Player resolver:** `siftprobe who Nobody` printed `No player was found`.
- **`requires` failure:**
  - Console without `siftprobe.secret` (a `default: false` node): `Incorrect argument for command siftprobe secret<--[HERE]`.
  - A non-op player gets `Unknown or incomplete command`: the node is hidden from the client tree.
  - Recommendation (not tested): after changing a player's permissions at runtime, call `Player#updateCommands()` so the client tree is rebuilt.
- **`Bukkit.dispatchCommand` threading:**
  - works on the global thread only
  - from a region thread → `IllegalStateException: Dispatching command async`
  - from async → `IllegalStateException: Asynchronous Command Dispatched Async: ...`
  - from `onEnable` → `Dispatching command async`
- **Parse failures throw.** Via `dispatchCommand`, a brigadier *parse* failure (unknown subcommand or hidden literal) is **thrown** as `org.bukkit.command.CommandException: Unhandled exception executing '...' in PluginVanillaCommandWrapper(...)` (cause `CommandSyntaxException`). It is not printed. Wrap reward/console dispatches in try/catch.
- **`Bukkit.createCommandSender(Consumer<? super Component>)`** returns a `FeedbackForwardingSender` with `isOp()=true` that has every permission, including `random.node`. It captures feedback (`pong`, `No player was found`), which is handy for audited console actions.
- **Player commands:** `PlayerCommandPreprocessEvent` and the command body run on the **player's owning region thread**.
- **Reload:**
  - `/minecraft:reload` → `Unknown or incomplete command` (vanilla `ReloadCommand` is not registered **[src]** `net.minecraft.commands.Commands`).
  - `/reload` → unknown.
  - `/bukkit:reload confirm` → `UnsupportedOperationException: Unsupported in region threading`.
  - `/datapack` only has `list` / `list enabled` **[src]** `DataPackCommand.register`.
  - `Server#reloadData()` → `UnsupportedOperationException`.
  - **So the COMMANDS and TAGS handlers fired exactly once, `cause=INITIAL`. There is no RELOAD path on Canvas 962.** Commands and tags remained intact for the whole session.

---

## 5. Threading matrix (empirical, `ThreadMatrix.run`)

Contexts:
- **startup** = `onEnable` (`Server thread`; `isPrimaryThread=true`, `isGlobalTickThread=false`, owns nothing)
- **global** = `GlobalRegionScheduler`
- **own** = `RegionScheduler` / `EntityScheduler` owning the spawn chunk or the entity
- **other** = a region thread for a far-away chunk (20000, 20000)
- **async** = `AsyncScheduler`

✔ works, ✘ throws (message), ⚠ works but has no thread check (data race; don't).

| call | startup | global | own region | other region | async |
|---|---|---|---|---|---|
| `Bukkit.getScheduler().runTask/runTaskAsynchronously` | ✘ `UnsupportedOperationException: Unsupported in region threading` | ✘ | ✘ | ✘ | ✘ |
| `getGlobalRegionScheduler().run` / `getRegionScheduler().run` / `getAsyncScheduler().runNow` / `entity.getScheduler().run` | ✔ (tasks from startup run after "Done") | ✔ | ✔ | ✔ | ✔ |
| `Bukkit.isGlobalTickThread()` | false | true | false | false | false |
| `Bukkit.isPrimaryThread()` | true | true | true | true | false |
| `Bukkit.isOwnedByCurrentRegion(loc/entity)` | false | false | true | false | false |
| `ScoreboardManager#getScoreboardManager/getMainScoreboard` | ✔ | ✔ | ✔ | ✔ | ✔ |
| `getNewScoreboard()` | ✔ | ✔ | ✘ `Cannot create new scoreboard async` | ✘ | ✘ |
| `registerNewObjective` + `setDisplaySlot(SIDEBAR)` + `numberFormat(NumberFormat.blank())` + `registerNewTeam` + `prefix` | ✔ | ✔ | ✘ `Cannot register new objective async` | ✘ | ✘ |
| `World#getGameRuleValue(GameRules.KEEP_INVENTORY)` | ✔ | ✔ | ✔ | ✔ | ✔ |
| `World#setGameRule(..)` | ✘ `Cannot modify server settings off of the global region` | ✔ | ✘ | ✘ | ✘ |
| `World#getTime()` / `setTime()` | ✔ / ✘ `Cannot modify time off of the global region` | ✔ / ✔ | ✔ / ✘ | ✔ / ✘ | ✔ / ✘ |
| `WorldBorder#getSize/setSize/setCenter` | ✔ | ✔ | ⚠ | ⚠ | ⚠ (no check **[src]** `CraftWorldBorder`; border ticks in the global tick, so use **global**) |
| `World#getSpawnLocation` / `setSpawnLocation` | ✔ / ⚠ | ✔ / ✔ | ✔ / ⚠ | ✔ / ⚠ | ✔ / ⚠ (no check; fires `SpawnChangeEvent` on the caller's thread, so use **global**) |
| `World#setChunkForceLoaded` | — | ✔ | ✘ `Cannot modify force-loaded chunks off global region` | ✘ | — |
| `World#addPluginChunkTicket` | ✔ | ✔ | ✔ | ✔ | ✔ |
| `World#getBlockAt(..).getType()` / `Block#setType` | ✘ `Thread failed main thread check: Cannot read/modify world asynchronously` | ✘ | ✔ | ✘ | ✘ |
| `World#getHighestBlockYAt` | ✘ `Cannot retrieve chunk asynchronously` | ✘ | ✔ | ✘ | ✘ |
| `World#getNearbyEntities` | ✘ `Cannot getEntities asynchronously` | ✘ | ✔ | ✘ | ✘ `Asynchronous getNearbyEntities!` |
| `World#getEntities()` | ✔ (returned 0) | ✔ (1) | ✔ (2, later 6) | ✔ (6) | ✘ `Asynchronous Chunk getEntities call!`. Counts differ by calling context, so this is not a reliable global view; don't use it for world-wide scans. |
| `World#getChunkAtAsync(..)` | ✔ (future completes on the **owning region thread**) | ✔ | ✔ | ✔ | ✔ |
| `Bukkit.getWorlds()`, `getOnlinePlayers()`, `World#getPlayers()` | ✔ | ✔ | ✔ | ✔ | ✔ |
| `Bukkit.getCurrentTick()` | ✘ `No currently ticking region` | ✘ | ✔ (region tick) | ✔ | ✘ |
| `Bukkit.getTPS()` | ✘ `UnsupportedOperationException: Not on any region` | ✔ (global region's TPS) | ✔ (that region's TPS) | ✔ | ✘ |
| `Bukkit.getServerTickManager().*` | ✘ `Unsupported in region threading` | ✘ | ✘ | ✘ | ✘ |
| `Server#getServerLinks()` / `addLink` / `removeLink` | ✔ | ✔ | ✔ | ✔ | ✔ (sent to clients in the configuration phase; online players need `Player#sendLinks`) |
| `Bukkit.broadcast(Component)` | ✔ | ✔ | ✔ | ✔ | ✔ |
| `Bukkit.dispatchCommand(console, ..)` | ✘ | ✔ | ✘ | ✘ | ✘ |
| non-player `Entity#customName(..)` (any `getHandle()`) | | ✘ `Accessing entity state off owning region's thread` | ✔ | ✘ | ✘ |
| `Entity#getLocation()` | | ⚠ | ✔ | ⚠ | ⚠ |
| `Entity#teleportAsync(..)` | | ✔ | ✔ | ✔ | ✔ (completes on the **destination** region thread) |
| `Entity#teleport(..)` (sync) | | ✘ `UnsupportedOperationException: Must use teleportAsync while in region threading` | ✘ | ✘ | ✘ |

### 5.1 Scheduler signatures (`io.papermc.paper.threadedregions.scheduler.*`)

- `GlobalRegionScheduler`: `execute(Plugin, Runnable)`, `run(Plugin, Consumer<ScheduledTask>)`, `runDelayed(Plugin, Consumer<ScheduledTask>, long delayTicks)`, `runAtFixedRate(Plugin, Consumer<ScheduledTask>, long initialDelayTicks, long periodTicks)`, `cancelTasks(Plugin)`.
- `RegionScheduler`: `execute(Plugin, World, int chunkX, int chunkZ, Runnable)` / `execute(Plugin, Location, Runnable)`, `run(..)`, `runDelayed(.., long delayTicks)`, `runAtFixedRate(.., long initialDelayTicks, long periodTicks)` (each with `Location` or `World,chunkX,chunkZ` overloads). There is no cancel-all.
- `AsyncScheduler`: `runNow(Plugin, Consumer<ScheduledTask>)`, `runDelayed(Plugin, Consumer<ScheduledTask>, long, TimeUnit)`, `runAtFixedRate(Plugin, Consumer<ScheduledTask>, long, long, TimeUnit)`, `cancelTasks(Plugin)`.
- `EntityScheduler` (`entity.getScheduler()`):
  - Methods: `execute(Plugin, Runnable run, Runnable retired, long delay)`, `run(Plugin, Consumer<ScheduledTask>, Runnable retired)`, `runDelayed(.., long delayTicks)`, `runAtFixedRate(.., long initialDelayTicks, long periodTicks)`.
  - The delay is at least 1 tick when it is not already on the owning thread.
  - `run` returns `null` once the entity is retired.
  - `retired` runs when the entity is removed (death/unload/quit) before the task executes **[src]** `io.papermc.paper.threadedregions.EntityScheduler#retire`.
  - Tasks follow the entity across regions.

---

## 6. Player-bound APIs (empirical, real connection via LoopbackBot)

From `async` (AsyncScheduler), `global` (GlobalRegionScheduler) and `own` (`player.getScheduler()`):

| call | async | global | own | client observed |
|---|---|---|---|---|
| `Player#sendPlayerListHeaderAndFooter(Component, Component)` | ✔ | ✔ | ✔ | `tab list header=H footer=F` ×3 |
| `Player#showDialog(DialogLike)` (registry or runtime dialog) | ✔ | ✔ | ✔ | `ShowDialog` ×3 (`CraftPlayer#showDialog` only sends `ClientboundShowDialogPacket`) |
| `Player#sendMessage(Component)` | ✔ | ✔ | ✔ | system chat ×3 |
| `Player#kick(Component)` | ✔ | | | disconnect packet; `PlayerQuitEvent reason=KICKED` on the player's region thread |
| `Player#teleportAsync(..)` | ✔ | ✔ | ✔ | completion callback ran on the player's region thread |
| `Player#getInventory().getItem(0)` | ⚠ (no check) | ⚠ | ✔ | |
| `Player#openInventory(Inventory)` | ✘ `Cannot init menu async`, **after** the `OpenScreen` packet was already sent | ✘ (same) | ✔ | |
| `Player#setScoreboard(Scoreboard)` | ✘ **[src]** (same check; the async probe failed earlier, at `getNewScoreboard`) | ✘ `Thread failed main thread check: Cannot set player board async` | ✔ | see §7 |
| `Player#setGameMode` (from `PlayerJoinEvent`), `setHealth(0)` | — | — | ✔ (`setHealth(0)` produced `PlayerDeathEvent` on the region thread) | game mode creative confirmed (instant block break) |
| `Player#addPotionEffect` **[src]** | ✘ | ✘ | ✔ | Canvas `TickGuard.guard(entity, "Cannot add potion effect async")` |

Why players differ from other entities **[src]**: `CraftPlayer#getHandle()` overrides the Folia-checked `CraftEntity#getHandle()` **without** the ownership check. Packet-only player methods are therefore safe anywhere, while state mutators are only protected where an explicit `TickThread.ensureTickThread` or Canvas `TickGuard.guard` exists:
- `ensureTickThread`: `setScoreboard`, `loadData` / `saveData`
- `TickGuard.guard`: `initMenu` / `openMenu` / `closeContainer`, `setGameMode`, `addPotionEffect`, `die`, `getEnderPearls`, merchant screens, block placement, entity tracking

Everything else on a player (inventory, health, location setters) has **no** check: always use `player.getScheduler()`.

---

## 7. Scoreboards and per-player sidebars on Canvas

**Per-player sidebars work.** Evidence from the loopback client, which received every packet:

```
[global] sidebar step1: create board+objective+lines => OK created
[entity] sidebar step2: Player#setScoreboard(board) => OK true
BOT set objective name=sift method=0 / display objective slot=SIDEBAR name=sift / set score line-a=2, line-b=1 / set team sift_nametag players=[SiftBot] params=[VIP] |
[global] sidebar step3: update score+prefix from global => OK   -> BOT set score line-a=5 ; set team ... params=[MVP] |
[entity] team.suffix(..) from the player's region thread => THROWS IllegalStateException: Cannot set player suffix for team async
```

Rules **[src]**:
- `net.minecraft.world.scores.Scoreboard#ensureMainThread` calls `RegionizedServer.ensureGlobalTickThread` once `TickRegions.hasStarted()`.
- The following are **global-thread only**: add/remove objective, set display slot, add/remove team, objective display name / render type / number format / auto-update, and every `PlayerTeam` property (display name, prefix, suffix, colour, friendly fire, name-tag/death-message visibility, collision).
- `CraftScoreboardManager#getNewScoreboard` uses the same global check.
- `CraftScoreboardManager#setPlayerBoard` (`Player#setScoreboard`) calls `TickThread.ensureTickThread(player, "Cannot set player board async")`, i.e. it needs the **owning region thread**.
- Score values (`Score#setScore`) and team membership (`Team#addEntry`) have **no** check: they worked from async and region threads, but are unsynchronized. Do them on the global thread too.
- `ServerScoreboard#broadcastAll` sends only to players whose current board is that board.
- Team membership updates call the vanilla `ServerWaypointManager#remakeConnections`, which is a no-op on Canvas (Canvas has its own `io.canvasmc.canvas.threadedregions.waypoints.RegionizedWaypointManager`). So `Team#addEntry(playerName)` from the global thread does not touch player state.
- `paper-global.yml` `scoreboards.track-plugin-scoreboards: false` (default): plugin boards are not tracked for vanilla criteria updates.

Recommended SiftCore pattern:

```java
// 1) build/update on the GLOBAL region thread
Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
    Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
    Objective o = sb.registerNewObjective("sift", Criteria.DUMMY, title);
    o.setDisplaySlot(DisplaySlot.SIDEBAR);
    o.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
    o.getScore("line-a").setScore(2); /* customName(Component) for the visible text */
    Team team = sb.registerNewTeam("nametag"); team.prefix(prefix); team.addEntry(player.getName());
    boards.put(player.getUniqueId(), sb);
    // 2) assign on the PLAYER's thread
    player.getScheduler().run(plugin, e -> player.setScoreboard(sb), null);
});
// 3) periodic refresh: one GlobalRegionScheduler.runAtFixedRate task updates all boards' scores/teams.
```

---

## 8. Event threads (empirical unless marked)

| event | thread | `isAsynchronous()` | source |
|---|---|---|---|
| `AsyncPlayerPreLoginEvent` | `User Authenticator #N` | true | `ServerLoginPacketListenerImpl` |
| `PlayerConnectionInitialConfigureEvent`, `PlayerLinksSendEvent` **[src]** | global region | false | `ServerConfigurationPacketListenerImpl#startConfiguration` (login-ack packet scheduled to global) |
| `AsyncPlayerConnectionConfigureEvent` | `Configuration Thread #N` | true | `PaperConfigurationTask` |
| `PlayerCustomClickEvent` (configuration phase) | **global region** (`isGlobalTickThread=true`); `getCommonConnection()` is `PaperPlayerConfigurationConnection` | false | `ServerCommonPacketListenerImpl#handleCustomClickAction` → `PacketUtils.ensureRunningOnSameThread` → `RegionizedServer#schedulePacket` |
| `PlayerJoinEvent` | **player's owning region** (spawn chunk) | false | `PrepareSpawnTask#spawnPlayer` queued with `RegionizedServer.taskQueue.queueTickTaskQueue(world, chunkX, chunkZ, ..)` → `PlayerList#placeNewPlayer` |
| `PlayerMoveEvent` | player's region | false | `ServerGamePacketListenerImpl#handleMovePlayer` (`ensureRunningOnSameThread` → `CraftPlayer#schedulePacket`) |
| `AsyncChatEvent` | `Async Chat Thread - #N` (`MinecraftServer#chatExecutor`) | true | `handleChat` (netty) → chat chain → `ChatProcessor` |
| `PlayerCommandPreprocessEvent` + command execution | player's region | false | `handleChatCommand` → `taskScheduler.scheduleOrExecute` |
| `PlayerCustomClickEvent` (play phase) | **player's region**; `PaperPlayerGameConnection` | false | as above (→ `CraftPlayer#schedulePacket`) |
| Dialog `ClickCallback` (`DialogAction.customClick(callback, options)`) | same thread, immediately after `PlayerCustomClickEvent`. Play phase: player's region, audience = `CraftPlayer` (verified). Configuration phase **[src]**: global region, audience = `PaperPlayerConfigurationConnection#getAudience()` | — | `ServerCommonPacketListenerImpl#handleCustomClickAction` → `ClickCallbackProviderImpl.DIALOG_CLICK_MANAGER.tryRunCallback` |
| `InventoryClickEvent` | player's region | false | `handleContainerClick` |
| `BlockBreakEvent` | player's region (owns the block too) | false | `handlePlayerAction` → `ServerPlayerGameMode#destroyBlock` |
| `EntityDamageByEntityEvent` | victim's owning region | false | `CraftEventFactory#callEntityDamageEvent` |
| `EntityDeathEvent` / `PlayerDeathEvent` | entity's / player's owning region | false | `LivingEntity#die` (`TickGuard`), `CraftEventFactory` |
| `SpawnerSpawnEvent` | spawner chunk's region | false | `BaseSpawner#serverTick` |
| `PlayerQuitEvent` (disconnect, kick) | player's owning region (`reason=DISCONNECTED` / `KICKED` / `ERRONEOUS_STATE`) | false | `RegionizedWorldData#tickConnections` → `Connection#handleDisconnection` → `PlayerList#remove` |
| `PlayerQuitEvent` at shutdown | **not delivered to plugins** (plugins are disabled before `savePlayers()` removes players) | | `RegionShutdownThread#run`: halt scheduler → `stopServer()` (`disablePlugins`) → `savePlayers()` |
| `ServerLoadEvent(STARTUP)` | `Server thread` | false | |

Spawner note: Canvas `BaseSpawner#isNearPlayer` → `canvas$hasNearbyAlivePlayerThatAffectsSpawningForSpawner`. A negative `requiredPlayerRange` does **not** disable the player check.

Tunables in `config/canvas-worlds.yml` (`blocks.spawner.*`): `min-spawn-delay`, `max-spawn-delay`, `spawn-count`, `max-nearby-entities`, `required-player-range`, `spawn-range`, `disable-max-nearby-entities-check`, `spawned-entities-have-no-collision`. They apply to newly created spawners only.

---

## 9. `custom_click_action` validation (security)

**Decoder** **[src]**: `net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(Identifier id, Optional<Tag> payload)`, accepted in **both** configuration and play.
- `id`: any syntactically valid `Identifier`.
- `payload`: `ByteBufCodecs.optionalTagCodec(() -> new NbtAccounter(32768L, 16))` inside `lengthPrefixed(65536)`. That is ≤ 64 KiB on the wire, ≤ 32 KiB NBT-accounted (strings count about 2 bytes per char plus overhead), and ≤ 16 nesting depth.
- Exceeding a limit fails decoding and **disconnects** the client: `Internal Exception: io.netty.handler.codec.DecoderException: Failed to decode packet 'serverbound/minecraft:custom_click_action'`, `PlayerQuitEvent reason=ERRONEOUS_STATE`. Observed with a 20 000-char string.

**The client can send arbitrary ids and payloads.** The loopback client sent `evil:arbitrary/path {x:"anything",n:[I;1,2,3]}` and `PlayerCustomClickEvent` fired with it. No server check ties the id to a dialog the player was shown:
- `MinecraftServer#handleCustomClickAction` only logs at debug.
- `PaperPlayerCustomClickEvent#getDialogResponseView()` wraps *any* compound payload with `PaperDialogResponseView.createUnvalidatedResponse`. `getText` / `getBoolean` / `getFloat` just read keys, with no enforcement of input `max_length`, number range or option lists.

**Paper callbacks** **[src]** `io.papermc.paper.adventure.providers.ClickCallbackProviderImpl`:
- Ids: dialog callbacks use `paper:dialog_click_callback`, `ClickEvent.callback` uses `paper:click_callback`. The payload is `{id:[I;..]}`, a random UUID.
  - Example: `PROBE dialog callback action id=paper:dialog_click_callback additions={id:[I;385038516,91703338,-1232416306,-154007808]}`.
- Storage is a single **global** `ConcurrentHashMap<UUID, StoredCallback>`. It is not bound to the player it was shown to: any connection presenting the UUID triggers it, with that connection's audience.
- `ClickCallback.Options.builder()` defaults: `uses = 1`, `lifetime = ClickCallback.DEFAULT_LIFETIME = 12 h`. `UNLIMITED_USES = -1`. Expired callbacks are swept every 100 global ticks.
  - Verified: the second click with the same UUID fired `PlayerCustomClickEvent` again but **not** the callback.
- `PlayerCustomClickEvent` fires **before** the callback, for callback ids too.

SiftCore must:
1. Namespace its ids (`siftcore:<menu>/<action>`) and ignore anything else.
2. Keep a per-player "currently shown dialog + nonce" server-side and reject clicks that don't match.
3. Clamp and validate every input (`getText` length and charset, numbers to range, options to the allowed set).
4. Make every action idempotent / rate-limited, and re-check permissions, balance and state at execution time on the player's thread.
5. For callbacks, create them per player and check `audience == expectedPlayer` inside.
6. Keep dialog text inputs small (`max_length` ≤ a few hundred) so legitimate responses stay far below the 32 KiB accounter.

---

## 10. Lifecycle, shutdown and Canvas config

**Startup:**
1. loader (`ServerMain`)
2. bootstrap (`ServerMain`)
3. Canvas config load
4. datapack/registry load: registry compose/entryAdd, TAGS, bootstrap COMMANDS (`Worker-Main-1`)
5. `createPlugin` → `onLoad` (`Server thread`)
6. `STARTUP` plugins enable
7. worlds
8. `POSTWORLD` `onEnable` (`Server thread`, not global, owns nothing)
9. plugin COMMANDS handler (`Server thread`, INITIAL)
10. `ServerLoadEvent(STARTUP)`
11. `[TickRegionScheduler] Starting EDF region scheduler`
12. `Done`
13. tasks scheduled during `onEnable` start running

**Shutdown** **[src]** `io.papermc.paper.threadedregions.RegionShutdownThread#run`, confirmed by the log order:
1. `Awaiting scheduler termination` → `Scheduler halted`
2. `Stopping server` → plugins disabled: `onDisable` on thread **`Region shutdown thread`** (`isGlobalTickThread=false`, `isPrimaryThread=true`)
3. `Removing players`
4. world/chunk/player saves

`TickThread.isTickThreadFor(entity)` returns true on the shutdown thread, so `onDisable` may read every online player's state directly. Do all saving synchronously there; schedulers no longer run.

**Canvas `config/canvas-server.yml` knobs that matter:**
- `region-scheduler.guard-severity: THROW|LOG|SILENT` (default `THROW`) controls Canvas's extra `TickGuard` checks.
- `autosave.*` (`autosave-frequency: 5m`, Canvas restores Folia's missing autosaves).
- `chat.disable-chat-reporting`.
- `networking.purpur-alternative-keepalive`.

`config/paper-global.yml`: `threaded-regions.threads: -1` (auto), `scheduler: EDF`, `grid-exponent: 4`.

---

## 11. Probe sources and how to re-run

`scratchpad` = `/tmp/claude-0/-home-user-SMPCore/a42bd3dd-dc84-5302-8b35-c93e1919b1c9/scratchpad`. The test server copy `scratchpad/srv-A` is kept without its world.

- Sources: `scratchpad/research/runtime-scripts/probe/`
  - `src/dev/siftvanilla/probe/{ProbeLoader,ProbeBootstrap,ProbePlugin,ProbeListener,ThreadMatrix,Probe,LoopbackBot}.java`
  - `resources/paper-plugin.yml`
  - `build.sh`, which compiles against paper-api, plus the server jar and libraries for the bot only
- Variant `paper-plugin.yml` tests: `scratchpad/research/runtime-scripts/variants/` (`yml/*.yml`, `build.sh`).
- Server control: `scratchpad/research/runtime-scripts/srv.sh start|cmd|wait|stop`. It uses a FIFO on stdin and port 25611.
- Decompiled server: `scratchpad/research/runtime/decomp/` (Vineflower 1.12.0).
- Logs: `scratchpad/research/runtime/run1..run6-boot.log`:
  - run1: plugin.yml variants, threading matrix, console and reload commands
  - run2/run3: loopback client events and player APIs
  - run4: registry/tag sync, links, `reloadData`
  - run5: datapack reload attempt
  - run6: library and permission visibility
- `LoopbackBot` is **probe-only** test tooling: it uses NMS and must never ship. If SiftCore needs automated join tests later, it is a ready base. It covers login/config/play, keepalive, teleport-accept, `PlayerLoaded`, chat, commands, custom clicks, container clicks and block breaking.
