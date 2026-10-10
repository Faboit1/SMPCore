# Track C: dependency plugins and server tuning (Canvas 26.2 build 962, Java 25)

Research date 2026-10-07. Every claim marked **verified** was checked in this session: the jar was downloaded and hashed, the
plugin booted on a copy of the local test server (`scratchpad/srv-C`, port 25613, offline mode), or code was compiled
and run against the jar. Canvas internals were read from the server jar (`versions/26.2/canvas-26.2.jar`) with Vineflower 1.12.0.
Nothing here touched the live server or the Pterodactyl panel.

## TL;DR

* **Install these four.** Each one declares `folia-supported: true`, and all four were booted together on Canvas 962 with no plugin warnings or errors:
  LuckPerms **5.5.87**, PlaceholderAPI **2.12.3**, VaultUnlocked **2.20.3** (its plugin name is `Vault`) and ViaVersion **5.12.0**.
  Chunky **1.5.3** is also recommended, for pre-generating the world inside the border.
* **ViaVersion 5.12.0 accepts 26.3 clients** (protocol 777) on the 26.2 server (protocol 776). Three checks confirm it: a status
  ping, a raw login that gets as far as `Login Success`, and the ViaVersion API reporting `[26.2 (776), 26.3 (777)]`.
* **PlaceholderAPI needs no Folia setting.** It detects Folia on its own. It calls expansions **on whatever thread asks**,
  so SiftCore's expansion must be thread-safe.
* **VaultUnlocked does not bridge the legacy and modern economy APIs.** SiftCore must register
  `net.milkbowl.vault.economy.Economy` itself, and also `net.milkbowl.vault2.economy.Economy` if it wants modern consumers to find it.
* **Canvas difference that matters for dupe safety:** `Block#getState()` and `Inventory#getHolder()` return **live, non-snapshot**
  states by default (`tile-entity-snapshot-creation: false`). This was verified at runtime. SiftCore should always call `getState(boolean)` and
  `getHolder(boolean)` explicitly.
* **Threads on 3 cores:** if left unset, a 3-CPU container gets **1** tick thread, 1 chunk worker and 1 I/O thread (verified). Set
  `threaded-regions.threads: 2`, `scheduler: AFFINITY`, `chunk-system.worker-threads: 2` and `io-threads: 1`.
* **SIMD warning:** the `--add-modules=jdk.incubator.vector` warning cannot be fixed from the client API. It only speeds up `org.bukkit.map.MapPalette`
  (rendering images onto maps), so leaving the flag off costs this server almost nothing.

---

## Part 1: Dependencies

### 1.1 Selected releases (all downloaded, hashed and booted)

| Plugin | Version | Published | Download URL (what was used) | Size (bytes) | SHA-256 |
|---|---|---|---|---|---|
| LuckPerms (Bukkit) | 5.5.87 (CI build 1674) | 2026-10-04 | `https://download.luckperms.net/1674/bukkit/loader/LuckPerms-Bukkit-5.5.87.jar` | 1,509,479 | `09d07b68965717976d2bab2f8d10436676f7c68d90bf6d24628cc0e5228bf406` |
| PlaceholderAPI | 2.12.3 | 2026-07-03 | `https://cdn.modrinth.com/data/lKEzGugV/versions/pIvQcXW8/PlaceholderAPI-2.12.3.jar` | 1,160,690 | `fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437` |
| VaultUnlocked | 2.20.3 | 2026-09-16 | `https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar` | 134,932 | `fbc6651aca11e13376c115df66eeffa79592e79517be398299aa6beb49c22695` |
| ViaVersion | 5.12.0 (release) | 2026-09-18 | `https://cdn.modrinth.com/data/P1OZGk5p/versions/FaishMnD/ViaVersion-5.12.0.jar` | 6,503,775 | `c4d512fa9760fa41d17abaedde12aa1f4c9bde920d0a992fe0fc016962f126be` |
| Chunky (optional, pre-generation) | 1.5.3 | 2026-05-04 | `https://cdn.modrinth.com/data/fALzjamp/versions/MdY6JATr/Chunky-Bukkit-1.5.3.jar` | 304,616 | `530d2c7430a96a39957391b7088be144daa3108f7665896d1c23aa8dd4af32f3` |

`plugin.yml` facts, read from each jar:

| Jar | `name:` | `folia-supported` | `api-version` | Notes |
|---|---|---|---|---|
| LuckPerms | `LuckPerms` | `true` | `1.13` | `load: STARTUP`, `loadbefore: [Vault]`, `softdepend: [LilyPad-Connect, ViaVersion]` |
| PlaceholderAPI | `PlaceholderAPI` | `true` | `"1.13"` | command `placeholderapi` (alias `papi`) |
| VaultUnlocked | **`Vault`** | `true` | `1.13` | `main: net.milkbowl.vault.Vault`, `load: STARTUP`, `softdepend: [PlaceholderAPI]`, commands `vault-info` and `vault-convert` |
| ViaVersion | `ViaVersion` | `true` | `1.13` | `loadbefore: [ProtocolLib]` |
| Chunky | `Chunky` | `true` | `1.13` | `load: POSTWORLD` |

How each build was found and cross-checked:
* Discovery used the Modrinth API with filters, for example
  `https://api.modrinth.com/v2/project/<slug>/version?loaders=["folia"]&game_versions=["26.2"]` for the slugs `luckperms`, `placeholderapi`,
  `vaultunlocked`, `viaversion` and `chunky`.
  Every Modrinth file matched its published `sha512`.
* LuckPerms on Modrinth stops at **5.5.71** (2026-08-06, sha256 `49cecb66fa1fd22a133039a490e9c1e5095a238e7cd66eb9d2a16fe6c897550d`).
  The official download site and CI have **5.5.87**. Both URLs serve a byte-identical jar:
  `https://ci.lucko.me/job/LuckPerms/1674/artifact/bukkit/loader/build/libs/LuckPerms-Bukkit-5.5.87.jar`. The "Update to Minecraft 26.3"
  change arrived in 5.5.85 (2026-09-19). Release metadata is at `https://metadata.luckperms.net/data/all`.
* PlaceholderAPI 2.12.3 on Hangar (`https://hangarcdn.papermc.io/plugins/HelpChat/PlaceholderAPI/versions/2.12.3/PAPER/PlaceholderAPI-2.12.3.jar`)
  has the same SHA-256 as the Modrinth file.
* VaultUnlocked 2.20.3 is only on Modrinth. Hangar's newest is 2.20.2. Do not use the `2.20.3-java8` variant (`VaultUnlockedJava8-2.20.3.jar`).
* The newest ViaVersion builds are `5.12.1-SNAPSHOT+NNNN` betas, also on Hangar. **5.12.0 is the newest release**, and its changelog opens with "Added 26.3 client support!".

### 1.2 Boot test with all four plugins (verified)

Setup: a copy of `local` at `scratchpad/srv-C` on port 25613, JDK 25.0.4.1 with `-Xmx2G`, and the four jars in `plugins/`. Log excerpt, with unrelated lines removed:

```
[22:04:04 INFO]: [bootstrap] Loading Canvas 26.2-962-HEAD@2a3bf65 (2026-09-29T22:07:12Z) for Minecraft 26.2
[22:04:04 INFO]: [PluginInitializerManager] Bukkit plugins (4):
 - LuckPerms (5.5.87), PlaceholderAPI (2.12.3), Vault (2.20.3), ViaVersion (5.12.0)
[22:04:12 INFO]: [ViaVersion] ViaVersion 5.12.0 is now loaded. Registering protocol transformers and injecting...
[22:04:17 INFO]: [LuckPerms] Enabling LuckPerms v5.5.87
[22:04:17 INFO]: [LuckPerms] Loading storage provider... [H2]
[22:04:18 INFO]: [LuckPerms] Successfully enabled. (took 997ms)
[22:04:18 INFO]: [Vault] Enabling Vault v2.20.3
[22:04:18 INFO]: [Vault] Enabled Version 2.20.3
[22:04:18 INFO]: [LuckPerms] Registered Vault permission & chat hook.
[22:04:18 INFO]: [ViaVersion] Enabling ViaVersion v5.12.0
[22:04:18 INFO]: [PlaceholderAPI] Enabling PlaceholderAPI v2.12.3
[22:04:19 INFO]: [PlaceholderAPI] Successfully registered internal expansion: vaultunlocked [2.13.1]
[22:04:19 INFO]: [ViaVersion] ViaVersion detected server version: 26.2 (776)
[22:04:19 INFO]: [ViaVersion] Finished mapping loading, shutting down loader executor.
[22:04:19 INFO]: Done (15.775s)! For help, type "help"
```

`/plugins` listed all four in green. `/vault-info` printed:
```
[Vault] Economy Legacy: None   [Vault] Economy Modern: None
[Vault] Permission Legacy: LuckPerms [LuckPerms]   [Vault] Chat Legacy: LuckPerms [LuckPerms]
```
Shutdown was also clean (`[LuckPerms] Goodbye!`, `[ViaVersion] ViaVersion has been disabled; uninjected the platform and shut down the scheduler.`).

**None of the WARN lines in the log came from a plugin.** The only WARNs were:
* Canvas's SIMD warning (see 2.10).
* The JDK `sun.misc.Unsafe` notice from `org.joml` (a server library).
* The root-user and offline-mode notices, which are sandbox-only.
* `[TickRegionScheduler] Region profiling not supported in this environment`. This is printed by the EDF scheduler and disappears once the scheduler is switched to AFFINITY (verified).

There was one INFO line on the first player login, worth knowing about (see 1.6):
`[HorriblePlayerLoginEventHack] You have plugins listening to the PlayerLoginEvent, this will cause re-configuration APIs to be unavailable: [LuckPerms]`.

The plugin also booted under the tuned configuration from Part 2 with 3 CPUs emulated (`-XX:ActiveProcessorCount=3`). Again there were no plugin WARNs.

### 1.3 ViaVersion: 26.3 clients on the 26.2 server (verified)

Protocol numbers: 26.2 = **776** and 26.3 = **777**. Sources: `version.json` in the 26.2 and 26.3 client jars (`"protocol_version": 777`, `"java_version": 25`),
and the ViaVersion bytecode (`ProtocolVersion.v26_3 = register(777, "26.3")`).

**Raw protocol probe** (`research/C-scripts/mcprobe.py`, a handshake plus status or login):

| Client protocol | Status ping `version` reply | Login result |
|---|---|---|
| 776 (26.2) | `{'name': 'Canvas 26.2', 'protocol': 776}` | `Set Compression threshold=256`, then `LOGIN SUCCESS` |
| **777 (26.3)** | `{'name': 'Canvas 26.2', 'protocol': 777}`: Via rewrites the reply so the client shows the server as compatible | `Set Compression threshold=256`, then **`LOGIN SUCCESS`** |
| 778 (unknown) | `protocol: 776` | `LOGIN DISCONNECT reason="Outdated server! I'm still on 26.2"` |
| 774 / 767 (older clients) | `protocol: 776` | not supported. Older clients would need ViaBackwards, which was not requested. |

**API probe** (a Paper plugin compiled against the ViaVersion jar):
`Via.getAPI().getSupportedProtocolVersions()` returned **`[26.2 (776), 26.3 (777)]`**. `getFullSupportedProtocolVersions()` returned the same.
Timing caveat: called during `onEnable` the set is **empty**, because Via fills it after `ViaVersion detected server version` is logged. Query it from a delayed task.
Relevant API signatures:
`com.viaversion.viaversion.api.Via#getAPI()`, `ViaAPI#getPlayerProtocolVersion(UUID)` returning `ProtocolVersion`, and
`ViaAPI#getSupportedProtocolVersions()` returning `SortedSet<ProtocolVersion>`.
If SiftCore ever needs it, the API artifact is `com.viaversion:viaversion-api:5.12.0` from `https://repo.viaversion.com/` (the release exists).

ViaVersion `config.yml` keys worth knowing: `check-for-updates: true` (set to false to avoid update pings),
`block-versions: []`, `packet-limiter.max-per-second: 800`, and `send-player-details` / `send-server-details` (metrics).

### 1.3b ViaBackwards: older clients (verified 2026-10-08)

ViaBackwards **5.12.0** (Modrinth, released with ViaVersion 5.12.0; `folia-supported: true`, `depend: [ViaVersion]`;
1,430,897 bytes, sha256 `f902f7da7eb99e8bfaf461f80283c4e2750b7d9727e6b508ea4bb9163f55b1db`) was booted with ViaVersion
5.12.0 and SiftCore on a copy of the local server. There were no plugin warnings.

Logins (`mcprobe` with the login packet each version expects): every protocol from **107 (1.9) to 777 (26.3)** reached
`LOGIN SUCCESS`, including 770 (1.21.5), 767 (1.21), 763 (1.20.1), 754 (1.16.5), 340 (1.12.2) and 110 (1.9.4).
Protocol 47 (1.8) is refused with "Outdated client! Please use 26.2"; it would need ViaRewind.

Dialogs on old clients: clients before 1.21.6 have no dialog screen. ViaBackwards' `dialogs-via-chests: true` (the
default) renders each dialog as a 27-slot chest:
- widgets go in slots 0-17, and the body text is merged into one item;
- the exit or action button goes in slot 18 (13 when there are no widgets);
- confirmation yes/no go in slots 20 and 24;
- slot 26 is page navigation.

Clicking a button sends the button's custom click to the server, exactly like a modern client
(decompiled `ChestDialogViewProvider`). An end-to-end check with a raw 1.21.5 client (`tools/e2e/legacy_client.py`) got
`/menu` as a chest titled "SiftVanilla"; clicking slot 1 reached SiftCore's dialog router, which validated the token and
opened the "Money" dialog (shown as a second chest). The `dialog-style` labels default to blue, gold and bold, so the
live config restyles them to white and gray.

### 1.4 PlaceholderAPI on Folia

* **No Folia-specific setting is needed.** PAPI 2.12.3 ships its own scheduler abstraction, `me.clip.placeholderapi.scheduler.UniversalScheduler`, with
  `scheduler.folia.FoliaScheduler`, `scheduler.paper.PaperScheduler` and `scheduler.bukkit.BukkitScheduler`, and picks Folia automatically.
* The generated `plugins/PlaceholderAPI/config.yml` has no Folia keys. Its keys are: `check_updates: true`, `cloud_enabled: true`, `cloud_sorting: "name"`,
  `boolean: {'true': 'yes', 'false': 'no'}`, `date_format`, `detect_malicious_expansions: true`, `use_adventure_provided_replacer: false`
  and `debug: false`.
  Production recommendation: set `check_updates: false`, keep `detect_malicious_expansions: true`, and set `cloud_enabled: false` unless an eCloud
  expansion is actually needed. Most third-party expansions are not written to be thread-safe.
* **Thread behaviour (verified).** `PlaceholderAPI.setPlaceholders` runs the expansion on the **calling thread**. The probe's expansion returned
  `Thread.currentThread().getName()`:
  `Server thread` (called from onEnable), `Folia Async Scheduler Thread #1` (async scheduler) and `Folia Region Scheduler Thread #0` (global region).
  So SiftCore's `PlaceholderExpansion#onRequest(OfflinePlayer, String)` must be thread-safe. It should read from concurrent caches and must not touch
  world or entity state directly. Make the expansion `persist() = true`.
* Component API (present in 2.12.3): `me.clip.placeholderapi.PAPIComponents.setPlaceholders(OfflinePlayer, net.kyori.adventure.text.Component)` and its overloads,
  plus `setBracketPlaceholders` and `setRelationalPlaceholders(Player, Player, Component)`. The String API is `me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(OfflinePlayer, String)`.
  To build an expansion, extend `me.clip.placeholderapi.expansion.PlaceholderExpansion`. Its abstract methods are `getIdentifier()`, `getAuthor()` and `getVersion()`;
  override `onRequest(OfflinePlayer, String)` and `persist()`.

### 1.5 VaultUnlocked notes (verified)

* **The plugin name is `Vault`.** Any `depend` or `softdepend` entry, or a paper-plugin.yml `dependencies.server` entry, must use `Vault`.
* **There is no bridge between the legacy and modern APIs.** This was verified twice. First by decompiling `net.milkbowl.vault.Vault`: `/vault-info` simply lists
  `getRegistrations(net.milkbowl.vault.economy.Economy.class)` and `getRegistrations(net.milkbowl.vault2.economy.Economy.class)` side by side.
  Second at runtime: after the probe registered only a legacy provider at `ServicePriority.Highest`, the output was
  ```
  [Vault] Economy Legacy: SiftProbeEco [SiftProbeEco]
  [Vault] Economy Modern: None
  ```
  Consumers asking for `net.milkbowl.vault2.economy.Economy` (VaultUnlocked "modern") will **not** see a legacy-only provider.
  Register both if modern consumers matter. Most shop and auction plugins use the legacy interface.
* **Built-in PAPI expansion `vaultunlocked` (v2.13.1).** If a modern provider exists it is used first. Otherwise it falls back to the legacy one, which serves
  `%vaultunlocked_eco_balance%`, `%vaultunlocked_eco_balance_fixed%`, `%vaultunlocked_eco_balance_formatted%`,
  `%vaultunlocked_eco_balance_commas%` and `%vaultunlocked_eco_balance_<N>dp%`.
  Verified with the probe's legacy provider: `12345.678 | $12,345.68 | 12,345.68`.
* Metrics: VaultUnlocked uses FastStats (`plugins/faststats/config.properties`: `enabled=true`) as well as bStats. To opt out, set `enabled=false`.
  `plugins/Vault/config.yml` contains only `update-check: true`.

### 1.6 LuckPerms notes (verified)

* The default storage is H2 (`plugins/LuckPerms/luckperms-h2-v2.mv.db`). The API reports `plugin=5.5.87 api=5.5`. LuckPerms registers Vault `Permission` and `Chat`.
* LuckPerms listens to the deprecated `PlayerLoginEvent`. Decompiled `io.papermc.paper.connection.HorriblePlayerLoginEventHack` shows that when *any*
  plugin listens to it, `io.papermc.paper.connection.PlayerGameConnection#reenterConfiguration()` stops working: it logs a large WARNING and does nothing.
  **SiftCore must not depend on re-entering the configuration phase.** Use in-game APIs such as dialogs or resource packs in the play state instead.

### 1.7 Paper-plugin wiring as SiftCore uses it (verified)

SiftCore's `paper-plugin.yml` lists each Bukkit dependency under `dependencies.server` with `load: BEFORE`, `required: false`
and `join-classpath: true`. A probe Paper plugin with exactly that block saw every class:

```
DEPPROBE VISIBLE net.milkbowl.vault.economy.Economy loader=PluginClassLoader{plugin=Vault v2.20.3, ..., url=plugins/VaultUnlocked-2.20.3.jar}
DEPPROBE VISIBLE net.milkbowl.vault2.economy.Economy loader=PluginClassLoader{plugin=Vault v2.20.3, ...}
DEPPROBE VISIBLE net.luckperms.api.LuckPerms loader=PluginClassLoader{plugin=LuckPerms v5.5.87, ...}
DEPPROBE VISIBLE me.clip.placeholderapi.PlaceholderAPI loader=PluginClassLoader{plugin=PlaceholderAPI v2.12.3, ...}
DEPPROBE VISIBLE com.viaversion.viaversion.api.Via loader=PluginClassLoader{plugin=ViaVersion v5.12.0, ...}
DEPPROBE LuckPerms plugin=5.5.87 api=5.5 servicesManager=true
DEPPROBE Vault Permission provider=LuckPerms by LuckPerms
DEPPROBE Vault legacy Economy provider=SiftProbeEco modern(vault2) provider=null
```
On Canvas, `onEnable` runs on the thread named `Server thread`.

### 1.8 Geyser-Spigot and Floodgate (investigated only, not planned for install)

| Jar | Version | Published | URL | Size | SHA-256 | `folia-supported` |
|---|---|---|---|---|---|---|
| Geyser-Spigot | 2.11.3-b1249 (`git-master-f66329d`) | 2026-10-06 | `https://download.geysermc.org/v2/projects/geyser/versions/2.11.3/builds/1249/downloads/spigot` (identical to Modrinth `geyser` 2.11.3-b1249, loaders `paper` and `spigot`) | 47,170,037 | `39d8a45eca9f2413080b9597c67d1b2bf3bc9b62e8577f46eb83c9c8abfc4499` | `true` (`softdepend: ["ViaVersion", "floodgate"]`) |
| Floodgate-Spigot | 2.2.5-SNAPSHOT b141 (`81b65cc`, "Update to 26.3 (#686)") | 2026-09-17 | `https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141/downloads/spigot` | 11,561,096 | `21570aff9ce17d6983928e8552777760e1ede5050026b04c686b0ae112e6fd7e` | `true` (`name: floodgate`) |

* Both SHA-256 values match the GeyserMC API (`https://download.geysermc.org/v2/projects/<geyser|floodgate>/versions/latest/builds/latest`).
  Modrinth carries no Spigot Floodgate (only Fabric and NeoForge). Hangar's entries just link to the GeyserMC download API.
* **Booted together with the other four plugins on Canvas 962 (verified).** Results:
  * Floodgate printed `Took 570ms to boot Floodgate`. Geyser printed `Loading Geyser version 2.11.3-b1249 (git-master-f66329d)`, `Started Geyser on UDP port 19132` and `Done (2.794s)!`.
  * A Bedrock RakNet ping to UDP 19132 answered `MCPE;"A Minecraft Server";2193;26.52;0;20;...`.
  * Geyser's Java codec is **776 (26.2)**, matching the server natively. It supports Bedrock 26.30 through 26.52.
  * On first start Geyser downloads the vanilla client jar into `plugins/Geyser-Spigot/cache` (`Downloading Minecraft JAR to extract required files`).
  * By default Floodgate generates `plugins/floodgate/key.pem` and Geyser listens on `bedrock.port: 19132`. The file keeps `java.auth-type: online`, but decompiled
    `GeyserImpl` switches to `AuthType.FLOODGATE` in memory whenever Floodgate is installed (debug log: `Auto-setting to Floodgate authentication.`).
* **One WARN was a false positive:** `[Geyser-Spigot] You are using an outdated version of ViaVersion ...`. Decompiled
  `org.geysermc.geyser.platform.spigot.GeyserSpigotVersionChecker` checks `Via.getAPI().getSupportedVersions()` inside `Via.getPlatform().runSync(...)`.
  On Canvas this ran before ViaVersion had filled its supported set: the warning appeared at 22:16:29, before `ViaVersion detected server version` in the same second,
  and section 1.3 showed the set is empty at that stage. Because Geyser speaks 776 natively, it does not need ViaVersion at all.
* Cost on 3 cores: each Bedrock player adds translation CPU on top of a normal player. The Geyser jar alone is 47 MB.

### 1.9 Compile-time API coordinates (all resolved with curl)

| API | Maven coordinates (scope `provided`) | Repository | Verification |
|---|---|---|---|
| LuckPerms | `net.luckperms:api:5.5` | Maven Central | pom and jar resolve (no dependencies). **Compiled and run:** `LuckPermsProvider.get().getPluginMetadata()` gives `5.5.87` / `5.5`. Java 8 bytecode. |
| PlaceholderAPI | `me.clip:placeholderapi:2.12.3` | **`https://repo.extendedclip.com/releases/`** (works directly). The old `https://repo.extendedclip.com/content/repositories/placeholderapi/` path returns **301** to `https://repo.helpch.at/releases/`, which serves an identical pom. | pom has no dependencies. **Compiled and run** (expansion registered, parsing verified). |
| Vault, classic `Economy` (**chosen**) | `net.milkbowl.vault:VaultUnlockedAPI:2.20` | `https://repo.codemc.io/repository/creatorfromhell/` | pom and Gradle module list **no dependencies**, Java 8. Contains both `net.milkbowl.vault.*` (legacy) and `net.milkbowl.vault2.*` (modern). **The API classes are byte-for-byte the same signatures as those inside the installed VaultUnlocked 2.20.3 jar.** **Compiled and run** (legacy provider registered and seen by `/vault-info`). |
| Vault, alternative | `com.github.MilkBowl:VaultAPI:1.7.1` | `https://jitpack.io` | pom and jar resolve. Its only dependencies are `org.bukkit:bukkit` (provided, not transitive) and junit (test). Its `net.milkbowl.vault.economy.Economy`, `EconomyResponse` and `ResponseType` have **identical javap signatures** to VaultUnlockedAPI 2.20. Legacy API only. |
| Floodgate API | `org.geysermc.floodgate:api:2.2.5-SNAPSHOT` (resolves to `2.2.5-20260917.145236-21`) | `https://repo.opencollab.dev/main/` | Depends on `org.geysermc.cumulus:cumulus:1.1.2`, `org.geysermc.geyser:common:2.2.1-SNAPSHOT` and `org.geysermc.event:events:1.1-SNAPSHOT`, which all resolve. **Compiled** a sample using `FloodgateApi.getInstance().isFloodgatePlayer(UUID)`, `sendForm(UUID, Form)` and `FloodgatePlayer#sendForm`. |
| Cumulus | `org.geysermc.cumulus:cumulus:1.1.2` | `https://repo.opencollab.dev/main/` | **Use 1.1.2, not the newest `2.0.0-SNAPSHOT`.** 1.1.2 is what Floodgate 2.2.5 depends on, and Floodgate-Spigot bundles `org.geysermc.cumulus` unrelocated. Compiled `SimpleForm.builder().title(..).content(..).button(..).validResultHandler(..)`, `ModalForm` and `CustomForm`. |
| HikariCP | `com.zaxxer:HikariCP:7.1.0` (latest; Central `lastUpdated` 2026-06-14) | Maven Central | Java 11 bytecode. Its only non-optional dependency is `org.slf4j:slf4j-api`, and the server ships `slf4j-api 2.0.18`. HikariCP is **not** on the server classpath (`local/libraries` has none), so the repo's current plan (shade, relocate to `net.siftvanilla.siftcore.lib.hikari`, exclude slf4j) is correct. |

Do **not** use `net.cfh.vault:VaultUnlocked:2.20.1` from the same codemc repo. It is the whole plugin, with runtime dependencies on the API and FastStats.

Maven snippet:
```xml
<repository><id>extendedclip</id><url>https://repo.extendedclip.com/releases/</url></repository>
<repository><id>codemc-creatorfromhell</id><url>https://repo.codemc.io/repository/creatorfromhell/</url></repository>
<repository><id>opencollab</id><url>https://repo.opencollab.dev/main/</url></repository>

<dependency><groupId>net.luckperms</groupId><artifactId>api</artifactId><version>5.5</version><scope>provided</scope></dependency>
<dependency><groupId>me.clip</groupId><artifactId>placeholderapi</artifactId><version>2.12.3</version><scope>provided</scope></dependency>
<dependency><groupId>net.milkbowl.vault</groupId><artifactId>VaultUnlockedAPI</artifactId><version>2.20</version><scope>provided</scope></dependency>
<dependency><groupId>org.geysermc.floodgate</groupId><artifactId>api</artifactId><version>2.2.5-SNAPSHOT</version><scope>provided</scope></dependency>
<dependency><groupId>org.geysermc.cumulus</groupId><artifactId>cumulus</artifactId><version>1.1.2</version><scope>provided</scope></dependency>
```
Compiler note: in VaultUnlockedAPI the legacy `net.milkbowl.vault.economy.Economy`, `EconomyResponse` and `net.milkbowl.vault.permission.Permission` are
**marked deprecated**. The class Javadoc says "in lieu of the modern Vault2". With `-Xlint:all` this produces `[deprecation]` warnings, but not errors.
Put `@SuppressWarnings("deprecation")` on the provider class. jitpack's VaultAPI 1.7.1 does not deprecate the interface, if warning-free compiles matter more.

**`net.milkbowl.vault.economy.Economy` (identical in both artifacts): 43 abstract methods.** The String-name overloads are `@Deprecated`.
```
boolean isEnabled(); String getName(); boolean hasBankSupport(); int fractionalDigits(); String format(double);
String currencyNamePlural(); String currencyNameSingular();
boolean hasAccount(String) [dep]; boolean hasAccount(OfflinePlayer); boolean hasAccount(String,String) [dep]; boolean hasAccount(OfflinePlayer,String);
double getBalance(String) [dep]; double getBalance(OfflinePlayer); double getBalance(String,String) [dep]; double getBalance(OfflinePlayer,String);
boolean has(String,double) [dep]; boolean has(OfflinePlayer,double); boolean has(String,String,double) [dep]; boolean has(OfflinePlayer,String,double);
EconomyResponse withdrawPlayer(String,double) [dep]; EconomyResponse withdrawPlayer(OfflinePlayer,double);
EconomyResponse withdrawPlayer(String,String,double) [dep]; EconomyResponse withdrawPlayer(OfflinePlayer,String,double);
EconomyResponse depositPlayer(String,double) [dep]; EconomyResponse depositPlayer(OfflinePlayer,double);
EconomyResponse depositPlayer(String,String,double) [dep]; EconomyResponse depositPlayer(OfflinePlayer,String,double);
EconomyResponse createBank(String,String) [dep]; EconomyResponse createBank(String,OfflinePlayer); EconomyResponse deleteBank(String);
EconomyResponse bankBalance(String); EconomyResponse bankHas(String,double); EconomyResponse bankWithdraw(String,double); EconomyResponse bankDeposit(String,double);
EconomyResponse isBankOwner(String,String) [dep]; EconomyResponse isBankOwner(String,OfflinePlayer);
EconomyResponse isBankMember(String,String) [dep]; EconomyResponse isBankMember(String,OfflinePlayer);
List<String> getBanks();
boolean createPlayerAccount(String) [dep]; boolean createPlayerAccount(OfflinePlayer);
boolean createPlayerAccount(String,String) [dep]; boolean createPlayerAccount(OfflinePlayer,String);
```
**`net.milkbowl.vault.economy.EconomyResponse`** has public final fields `double amount`, `double balance`, `ResponseType type` and `String errorMessage`;
the constructor `EconomyResponse(double amount, double balance, ResponseType type, String errorMessage)`; and `boolean transactionSuccess()`.
`EconomyResponse.ResponseType` has the values `SUCCESS`, `FAILURE` and `NOT_IMPLEMENTED`.
`net.milkbowl.vault.economy.AbstractEconomy` also exists in both artifacts.

The modern **`net.milkbowl.vault2.economy.Economy`** (in VaultUnlockedAPI 2.20) has 48 abstract and 26 default methods. It is UUID- and `BigDecimal`-based and
supports multiple currencies and shared accounts. Examples: `getBalance(String pluginName, UUID)`, `withdraw(String pluginName, UUID, BigDecimal)`, `deposit(...)`,
`createAccount(UUID, String)`, `hasAccount(UUID)`, `fractionalDigits(String)` and `format(BigDecimal)`, plus defaults such as `balance(..)`, `set(..)`, `transfer(..)`,
`canWithdraw(..)` and `supportsAsync()`. Its `EconomyResponse` carries `BigDecimal amount` and `BigDecimal balance`.

---

## Part 2: Server tuning (Canvas 26.2, Java 25, 10 GB heap, 3-core quota, 100+ players)

### 2.0 What the JVM and Canvas actually see on a 300% CPU quota (verified)

* The JDK honours the cgroup CFS quota: `Runtime.availableProcessors()` = ceil(300000/100000) = **3**.
* Default thread formulas, from the decompiled code:
  * `ca.spottedleaf.moonrise.common.util.MoonriseCommon#adjustWorkerThreads`:
    `cores = OSNuma.getNativeInstance().getTotalCores()`, then `def = cores/2`; if `def <= 4`, it becomes 1 when `def <= 3` and 2 otherwise; else `def/2`.
    `io = max(1, chunk-system.io-threads)`.
  * `io.papermc.paper.threadedregions.TickRegions#getTickThreads`: when `threads <= 0`, `t = cores/2`, then `t <= 4 ? 1 : t/4`.
  * `getTotalCores()` comes from `ca.spottedleaf.concurrentutil.numa.OSNuma`, inside the `leafpile-1.2.0` library. If JNA can load `libnuma`, it is
    `numa_num_possible_cpus()`, which is **every CPU on the host** and ignores the container quota. Otherwise it is `availableProcessors()`.
    In the sandbox the libnuma path was not active: `-XX:ActiveProcessorCount=16` changed the result to 4 workers and 2 tick threads.
  * **Measured with `-XX:ActiveProcessorCount=3` and stock configs:**
    `Canvas is using 1 worker threads, 1 I/O threads` and `Initialised EDF Folia scheduler with initial 1 target thread(s)`.
    So by default all regions would tick on a single thread. If the panel image does ship `libnuma.so`, the defaults would instead scale with the host's core count.
    **Either way, set the values explicitly.**
* JVM ergonomics for `-Xms10G -Xmx10G` on 3 CPUs (`-XX:+PrintFlagsFinal`): G1, `ParallelGCThreads=3`, `ConcGCThreads=1`,
  `G1HeapRegionSize=8M`, `MaxGCPauseMillis=200`, `CICompilerCount=2`. A 10 GB heap plus roughly 1.5 to 2.5 GB of off-heap memory (metaspace, code cache, Netty direct buffers, G1 structures) fits the 14 GB limit.
* `spigot.yml settings.netty-threads: 4` logs `Using 4 threads for Netty based IO`. Netty threads mostly sit idle, so keep 4.

### 2.1 Region threads and chunk system (`config/paper-global.yml`)

| Key | Default | **Recommended** | Why |
|---|---|---|---|
| `threaded-regions.threads` | `-1` (gives 1 on 3 CPUs) | **`2`** | Two regions can tick in parallel, and about one core is left for chunk workers, Netty, G1 and async plugin I/O. Under a hard CFS quota, letting tick threads consume all 3 cores causes throttling stalls. Canvas region profiling also needs at least 2 threads. Try 3 only if spark shows tick threads saturated while the quota is not being hit. |
| `threaded-regions.scheduler` | `EDF` | **`AFFINITY`** | This is Canvas's own scheduler and the one its docs recommend. In build 962, work stealing and mid-tick task execution are **always on**: decompiled `io.canvasmc.canvas.threadedregions.scheduler.AffinitySchedulerThreadPool` has no enable flags. The old docs' `enableWorkStealing` and `enableMidTickTasks` keys no longer exist. Canvas deletes unknown keys; verified with: `[CanvasMC] Server-wide configuration option "region-scheduler.affinity-scheduler.enable-work-stealing" no longer exists and is now removed.` AFFINITY also enables `/spark profiler start --region <x> <z>` and `--global-tick`. |
| `threaded-regions.grid-exponent` | `4` | `4` (keep) | Sections of 16×16 chunks. With only 2 tick threads there is nothing to gain from changing it. |
| `chunk-system.worker-threads` | `-1` (gives 1) | **`2`** | Bursts of chunk loading after RTP. When the world is pre-generated, this work is decompress and deserialize, not generation. Drop to 1 if spark shows workers stealing time from tick threads. |
| `chunk-system.io-threads` | `-1` (gives 1) | **`1`** (explicit) | I/O-bound. |
| `chunk-loading-basic.player-max-chunk-generate-rate` | `-1.0` | **`25.0`** | Safety net so one player cannot drive mass generation, for example at the edge of the border. |
| `chunk-loading-basic.player-max-chunk-send-rate` / `player-max-chunk-load-rate` | `75.0` / `100.0` | keep | |
| `chunk-loading-advanced.auto-config-send-distance` | `true` | keep | |
| `misc.max-joins-per-tick` | `5` | keep | |

Validation boot with `-XX:ActiveProcessorCount=3` (verified):
```
[MoonriseCommon] Canvas is using 2 worker threads, 1 I/O threads
[TickRegions] Initialised AFFINITY Folia scheduler with initial 2 target thread(s)
[TickRegionScheduler] Starting AFFINITY region scheduler
/tps -> Server Health Report: Scheduler Type: AFFINITY ... Utilisation: 0.5% / 200.0%
```

**Keep AFFINITY CPU pinning off** (`region-scheduler.affinity-scheduler.enable-affinity-scheduler-cpu-affinity: false`, `tick-region-affinity: []`).
A Pterodactyl CPU limit is a CFS *quota*, not a set of dedicated cores. Pinning threads to host CPU ids the container does not own hurts performance, and
Canvas's docs say to leave at least half of the logical CPUs unpinned. Keep `steal-threshold-millis: 3` and `run-tasks-buffer-millis: 0.08` at their defaults.

### 2.2 Distances (`server.properties`)

| Key | Default | **Recommended** |
|---|---|---|
| `view-distance` | 10 | **`6`** (the local copy already uses 6). This is 169 chunks per player at 96 blocks. |
| `simulation-distance` | 10 | **`4`** (ticking radius of 64 blocks) |
| `network-compression-threshold` | 256 | **`256`** (keep. Chunk packets are compressed either way, and raising the threshold saves very little CPU) |
| `sync-chunk-writes` | true | **`false`** (stops region writes waiting on disk sync. The risk is only on OS crash or power loss, not a JVM crash) |
| `max-players` | 20 | **`150`** (Canvas `/setmaxplayers <n> [persist]` also works) |
| `entity-broadcast-range-percentage` | 100 | keep (ranges are set explicitly in spigot.yml) |
| `region-file-compression` | `deflate` | keep. `lz4` uses less CPU for chunk save and load but roughly doubles region-file and backup size. It only applies to newly written chunks. |

The mob spawn radius is clamped in code to `min(spigot mob-spawn-range, player view distance, 8)` (`ServerChunkCache`), so set it to match the simulation distance (see 2.3).
Canvas `/worlddistance <simulation|view> <world> set <n>` adds persistent per-world overrides, for example a smaller nether.

### 2.3 Entities (`spigot.yml`, `bukkit.yml`, `config/paper-world-defaults.yml`)

`spigot.yml`, under `world-settings.default.`:

| Key | Default | **Recommended** |
|---|---|---|
| `entity-activation-range.animals / monsters / raiders / misc / water / villagers / flying-monsters` | 32/32/64/16/16/32/32 | **16 / 24 / 48 / 8 / 8 / 16 / 48** |
| `entity-activation-range.tick-inactive-villagers` | true | **false** |
| `entity-tracking-range.players / animals / monsters / misc / display / other` | 128/96/96/96/128/64 | **64 / 48 / 48 / 32 / 64 / 64** (with view distance 6, players beyond 64 blocks are not rendered; raise `players` for long-range bow PvP) |
| `mob-spawn-range` | 8 | **4** (equal to the simulation distance) |
| `merge-radius.item` / `merge-radius.exp` | 0.5 / -1.0 | **2.5 / 3.0** (exp has no effect while Canvas `fast-orbs` is on) |
| `arrow-despawn-rate` | 1200 | **300** (keep `trident-despawn-rate: 1200`) |
| `nerf-spawner-mobs` | false | **true**, *only if vanilla spawners spawn real mobs*. Spawner mobs then have no AI but still fall and drop loot. |
| `item-despawn-rate` | 6000 | keep (items are valuable on an economy server) |
| `ticks-per.hopper-transfer` / `hopper-check` / `hopper-amount` | 8 / 1 / 1 | keep (vanilla hopper throughput matters for farms) |

`settings.save-user-cache-on-stop-only`: **true**.

`bukkit.yml` caps apply per player, since `entities.spawning.per-player-mob-spawns: true` is the default:

| Key | Default | **Recommended** |
|---|---|---|
| `spawn-limits.monsters / animals / water-animals / water-ambient / water-underground-creature / axolotls / ambient` | 70/10/5/20/5/5/15 | **50 / 8 / 3 / 3 / 3 / 3 / 1** |
| `ticks-per.monster-spawns` | 1 | **5** (lower to 2 or 3 if mob farms feel slow) |
| `ticks-per.water-spawns / water-ambient-spawns / water-underground-creature-spawns / axolotl-spawns / ambient-spawns` | 1 | **400** |
| `ticks-per.animal-spawns` / `ticks-per.autosave` | 400 / 6000 | keep |

`config/paper-world-defaults.yml`:

| Key | Default | **Recommended** |
|---|---|---|
| `entities.spawning.despawn-ranges.monster.soft / .hard` | default (32/128) | **30 / 56** (keeps the hard range under the simulation distance of 64 blocks) |
| the same keys for `ambient`, `water_ambient`, `water_creature`, `underground_water_creature` and `axolotls` | default | **30 / 56** (leave `creature` and `misc` at default) |
| `entities.spawning.non-player-arrow-despawn-rate` / `creative-arrow-despawn-rate` | default | **60 / 60** |
| `entities.spawning.alt-item-despawn-rate.enabled` | false | **true**, with `items:` `cobblestone`, `cobbled_deepslate`, `netherrack`, `dirt`, `tuff`, `granite`, `diorite`, `andesite` each set to `300`. Leave farm products out, because players sell them. |
| `collisions.max-entity-collisions` | 8 | **2** (cramming damage still follows the `maxEntityCramming` gamerule) |
| `entities.armor-stands.do-collision-entity-lookups` / `entities.armor-stands.tick` | true / true | **false / false** (player armor stands then ignore gravity and pistons) |
| `entities.markers.tick` | true | **false** |
| `chunks.entity-per-chunk-save-limit.arrow / experience_orb / fireball / small_fireball / snowball` | -1 | **16 / 16 / 8 / 8 / 8**. Keep `ender_pearl: -1`, otherwise pearl stasis chambers break. |
| `fixes.fix-items-merging-through-walls` | false | **true** (needed with the larger merge radius) |
| `misc.update-pathfinding-on-block-update` | true | **false** |
| `tick-rates.behavior.villager.validatenearbypoi` | -1 | **60** |
| `tick-rates.behavior.villager.acquirepoi` (new key) | | **120** |
| `tick-rates.sensor.villager.secondarypoisensor` | 40 | **80** |
| `tick-rates.sensor.villager.nearestbedsensor` (new key) | | **80** |

### 2.4 Chunks, autosave and pre-generation

* `chunks.max-auto-save-chunks-per-tick: 24` → **`8`**. On Folia this limit applies **per region per tick**. Decompiled
  `ChunkHolderManager#autoSave()` loops over the current region's `autoSaveQueue` up to `configMaxAutoSavePerTick`. A lower value spreads the
  save work out with fewer spikes, and each chunk is still saved every `chunks.auto-save-interval` (the default is bukkit `ticks-per.autosave: 6000`, which is 5 minutes).
* Canvas `autosave.autosave-frequency: "5m"` controls players, maps, pearls, scoreboards and similar data. Keep it.
* **Pre-generate the area inside the world border. This is the most important tuning step for an RTP server.** Use Chunky 1.5.3, which is verified on Canvas.
  Commands: `chunky world world`, `chunky worldborder` (copies the centre and radius from the vanilla border), then
  `chunky radius <border radius + (view-distance + 1) × 16>` (an extra 112 blocks at view distance 6, because chunks just outside the border are still sent),
  then `chunky start`. Repeat for the nether.
  * Measured in the sandbox with `worker-threads: 2` (the sandbox CPU differs from the live host): 2,601 chunks in 1 minute 39 seconds,
    which is **about 26 chunks per second**. `/tps` showed `Gen rate: 47.60`.
  * At that rate a ±5,000-block border (about 390k chunks) takes about 4 hours. Run it before launch or during a quiet window.
* SiftCore's RTP should never generate chunks. Choose targets inside the pre-generated border and load with
  `World#getChunkAtAsync(int x, int z, boolean gen)` using `gen=false`.

### 2.5 Redstone, hoppers and other world settings (`paper-world-defaults.yml`)

| Key | Default | **Recommended** | Note |
|---|---|---|---|
| `misc.redstone-implementation` | `VANILLA` | **`ALTERNATE_CURRENT`** | Folia keeps one `alternate.current.wire.WireHandler` (and one `RedstoneWireTurbo`) **per region** in `RegionizedWorldData`, so it is safe on Canvas. |
| `hopper.ignore-occluding-blocks` | false | **true** | |
| `hopper.cooldown-when-full` | true | keep | |
| `hopper.disable-move-event` | false | keep, **and do not let SiftCore listen to `InventoryMoveItemEvent`** | `RegionizedWorldData` computes `skipHopperEvents = disableMoveEvent || InventoryMoveItemEvent has 0 listeners`. One listener anywhere disables the fast path for every hopper. The same applies to `BlockPhysicsEvent`, `EntityMoveEvent`, `HopperInventorySearchEvent` and `InventoryPickupItemEvent`. |
| `environment.optimize-explosions` | false | **true** | |
| `environment.treasure-maps.find-already-discovered.loot-tables` / `villager-trade` | default / false | **true / true** | Avoids expensive structure searches. Canvas `chunk-system.optimize-treasure-map-locating: true` adds to this. |
| `tick-rates.grass-spread` | 1 | **4** | |
| `tick-rates.mob-spawner` | 1 | **2** | |

### 2.6 Anti-xray on Canvas

* **It works on Canvas.** `Level` builds a `ChunkPacketBlockControllerAntiXray` when `anticheat.anti-xray.enabled` is set, and the controller is
  region-aware. `modifyBlocks` checks `TickThread.isTickThreadFor(level, x, z)` and otherwise requeues itself with
  `RegionizedServer.getInstance().taskQueue.queueChunkTask(...)`. The obfuscation then runs on the level's background executor, not on the tick thread.
  Engine modes are `1 = HIDE`, `2 = OBFUSCATE` and `3 = OBFUSCATE_LAYER` (`io.papermc.paper.configuration.type.EngineMode`).
* **Recommendation for 3 cores: engine-mode 1, enabled per world on the overworld and nether only.** Mode 1 is the cheapest. Modes 2 and 3 cost more
  CPU and make chunk packets larger and less compressible. Keep it disabled in `paper-world-defaults.yml` and use these per-world files. The 26.x layout is
  `world/dimensions/minecraft/<dim>/paper-world.yml`. The boot was verified clean with these files:
  ```yaml
  # world/dimensions/minecraft/overworld/paper-world.yml (hidden-blocks inherits the default ore list)
  anticheat:
    anti-xray:
      enabled: true
      engine-mode: 1
      max-block-height: 64
      update-radius: 2
      lava-obscures: false
      use-permission: false
  # world/dimensions/minecraft/the_nether/paper-world.yml
  anticheat:
    anti-xray:
      enabled: true
      engine-mode: 1
      max-block-height: 128
      update-radius: 2
      lava-obscures: false
      use-permission: false
      hidden-blocks: [ancient_debris, nether_gold_ore, nether_quartz_ore, gilded_blackstone]
  ```
  Limitation: anti-xray only hides blocks that are fully enclosed. Chests in an air-filled base are still visible to x-ray, so it protects ores, not bases.

### 2.7 Canvas-specific settings worth enabling (all verified to load without warnings)

`config/canvas-server.yml`:

| Key | Default | **Recommended** | Notes |
|---|---|---|---|
| `networking.filter-velocity-packet` | false | **true** | Decompiled `ServerEntity`: only the motion packets sent *to other players tracking an entity* are filtered (items, ender eyes, squid, shulker bullets and the dragon are exempt). Knockback (`hurtMarked`, which calls `sendToTrackingPlayersAndSelf`) is a separate path, **so PvP knockback is unaffected**. |
| `networking.filter-move-packets` | false | **true** | Drops relative-move packets whose delta is zero. |
| `networking.alternative-player-list-tick` | false | **true** | Spreads tab-list latency updates across buckets (`player-info-send-interval: 600`). |
| `networking.purpur-alternative-keepalive` | false | **true** | Sends a keepalive every second and only kicks after 30 seconds with no reply. |
| `chunk-system.fluid-post-processing-algorithm` | `"VANILLA"` | **`"FILTERED"`** | C2ME's filter. Never use `DISABLED`, which breaks worldgen fluids. |
| `chunk-system.optimize-treasure-map-locating` | false | **true** | |
| `cache-minecraft2-bukkit-entity-type-conversion` | false | **true** | Backed by a `ConcurrentHashMap`, so thread-safe. |
| `vanilla-fixes.mc298464` | false | **true** | Fixes the Hoglin `CHANGED_DIMENSION` memory leak. The other `mcNNNNNN` fixes are cosmetic and optional. |
| `logs.enable-log-cleaner` / `logs.cleaner-time-span` | false / `"30d"` | **true / `"14d"`** | |
| `disable-locator-bar-in-all-worlds` | false | **true** (a gameplay decision) | On a PvP or raiding SMP the locator bar leaks player directions, and turning it off also saves the waypoint tracking work. |
| `region-scheduler.guard-severity` | `"THROW"` | keep | Catches thread-check violations. |
| `tile-entity-snapshot-creation` | false | keep **false**, but see the warning below | |

`config/canvas-worlds.yml` (per-world overrides go in `world/dimensions/minecraft/<dim>/canvas-patch.yml`):

| Key | Default | **Recommended** | Notes |
|---|---|---|---|
| `entities.fast-orbs` | false | **true** | XP orbs merge into one orb of unlimited value and the pickup delay is removed. Good for XP farms. |
| `entities.villagers.villager-acquire-poi-tasks-load-chunks` | true | **false** | Stops villagers from loading chunks synchronously. |
| `cactus-check-survival-before-growth` | false | **true** | Decompiled `CactusBlock`: instead of placing a block and then breaking it, it pops the drop directly at the growth position. Farm output is unchanged, but **no `BlockGrowEvent` fires in that case**, so SiftCore must not count cactus output through that event. |
| `enable-suffocation-optimization` | false | **true** | Suffocation is checked every 10 ticks and only when the entity could be hurt. Damage may start up to 0.5 seconds late. |
| `visuals.particles.disable-sprint-particles` / `disable-effect-particles` | false | **true** | Saves server-side work only. Leave `disable-fall-particles` and `disable-new-combat-particles` off, because they change visuals. |
| `entities.item-entities.item-entities-wait-two-seconds-for-merge-check-always` | false | **keep false** | When true, moving items (water streams) check for merges every 40 ticks instead of every 2, which means *more* item entities in farms. |

> **Warning for SiftCore code: `tile-entity-snapshot-creation: false` (the Canvas default) changes `Block#getState()` semantics.** Verified at runtime on a chest:
> ```
> getState().isSnapshot()=false getState(true).isSnapshot()=true getState(false).isSnapshot()=false
> after adding 5 diamonds via live inventory: getState() view slot0=ItemStack{DIAMOND x 5} | getState(true) view slot0=null
> Inventory#getHolder() isSnapshot=false getHolder(true) isSnapshot=true
> ```
> With `true` set, `getState()` and `getHolder()` return snapshots, the same as Paper. Its Javadoc says "The returned object will never be updated".
> Keep the faster default, but **SiftCore must always call `Block#getState(boolean useSnapshot)` and `Inventory#getHolder(boolean useSnapshot)`
> explicitly** and must never rely on snapshot isolation implicitly. Live states write through immediately, which is a dupe or item-loss risk.

### 2.8 Risky options to avoid

* `threaded-regions.scheduler: WORK_STEALING` (Canvas docs report task loss) and the removed "beta" scheduler.
* `region-scheduler.affinity-scheduler.enable-affinity-scheduler-cpu-affinity: true` in a quota-limited container (see 2.1).
* `chunk-system.fluid-post-processing-algorithm: "DISABLED"`.
* `region-scheduler.default-tick-rate` set to anything other than `20.0`. Also `guard-severity: "SILENT"`, and `disable-criterion-trigger: true` (breaks advancements).
* `entities.entity-collision-mode` other than `VANILLA`, and the `combat.*` legacy toggles. These are gameplay changes.
* `blacklist-non-player-entities-from-entering-*-portals: true` breaks portal-based item and mob transport, such as gold farms. Treat it as a gameplay decision.
* `/canvas reload` in production. Canvas's own config header says to restart instead. `/reload` is disabled on Folia.
* `-DCanvas.AggressiveChunkSystem=true` (switches to an experimental "LS" chunk system). It is a JVM flag, so it cannot be set here anyway.
* Raising `view-distance` or `simulation-distance` before measuring. Chunk send and tick cost grow with the square of the distance.

### 2.9 Startup network calls (for reference)

Canvas queries its build API at startup (`Util.CANVAS_CLIENT.getBuild(buildNum)`). If the call fails it broadcasts "Running unknown build channel, proceed with caution",
which does no harm. PAPI fetches eCloud metadata ("Fetching available expansion information...") unless `cloud_enabled: false`.

### 2.10 JVM-flag limitation (cannot be changed from the client API)

* The startup command belongs to the egg. The Pterodactyl client API can only edit egg **variables**, so `--add-modules=jdk.incubator.vector`, Aikar-style G1 flags,
  `-XX:+UseCompactObjectHeaders` (a product flag in JDK 25) and `-XX:+AlwaysPreTouch` **cannot be added** unless the egg exposes a variable that is placed before `-jar`.
* Impact of the missing vector module: it is **negligible for this server**. `SIMDDetection` is only used by `io.canvasmc.canvas.simd.VectorMapPalette`,
  which backs `org.bukkit.map.MapPalette`, the code that converts images for plugin-rendered maps. Verified by scanning the server and canvas-api jars.
  With the flag added, the log shows `WARNING: Using incubator modules: jdk.incubator.vector`,
  `[CanvasMC] Max SIMD vector size on this system is 512 bits (int)` and `[CanvasMC] SIMD operations detected as functional.`
  Without it, the four-line SIMD WARN appears on every boot. **It is safe to ignore.**
* GC: the defaults are acceptable (G1, 8 MB regions, 3 parallel and 1 concurrent thread). Do **not** raise `-Xmx` above 10G inside the 14 GB container.

### 2.11 Validation boot with the full recommended set (verified)

The changes listed in this part were applied to `srv-C`. The patch tool is `research/C-scripts/yamlpatch.py` with `tuning-spec.json`. The boot used `-XX:ActiveProcessorCount=3`.

Results:
* Every value survived the server re-saving its configs. Canvas deletes unknown keys with
  `Server-wide configuration option "<x>" no longer exists and is now removed.`, and no such line appeared.
* No WARN appeared apart from the SIMD notice and the sandbox-only root and offline notices.
* All plugins enabled.
* `/tps` printed the `AFFINITY` health report at `x% / 200.0%`.
* A Chunky run and the probe ran without errors.

Copies of the configs before and after are in `scratchpad/research/C/cfg-before` and `cfg-after`, and the logs are in `scratchpad/research/C/logs/`.

### 2.12 After rollout: what to watch

* `/tps`: Folia's health report shows utilisation out of 200%, lowest, median and highest TPS, and the busiest regions.
  `/regionbar tps_bar` shows the TPS of the region a player is in.
* `/spark profiler start --timeout 300`. With AFFINITY and 2 or more threads, also try `--region <x> <z>` or `--global-tick`.
  Canvas removes Folia's built-in profiler and the vanilla profiler.
* If utilisation stays near 200% while TPS drops, the server is CPU-bound at 3 cores. Lower `simulation-distance` before adding threads.
