# SiftCore API reference: paper-api 26.2 + Adventure 5.2 on Canvas 962 (Track D)

This is a lookup table for code that runs on **Canvas 26.2-962** ("Implementing API version 26.2.build.962-stable"). It covers:
- `paper-api 26.2.build.132-stable`, which matches the API that Canvas 962 bundles
- Adventure **5.2.0**
- Java 25

Threading rules are in `runtime.md` (Track A); this file only adds threading notes it measured itself.

**Evidence markers**

| marker | meaning |
|---|---|
| **[RT]** | Observed at runtime on a copy of the local Canvas 962 server (`srv-D`, port 25614) with probe plugin `SiftApiProbe` and two in-JVM protocol bots. Logs: `research/D/run1..run5-boot.log`. |
| **[C]** | Compiles with `javac --release 25` against the paper-api jar alone (`research/D-scripts/sigcheck/src/sig/SigCheck.java`) or in the probe. |
| **[SRC]** | Read from the API sources, or from the Vineflower decompile of the Canvas server jar (`research/runtime/decomp`). |

**Status markers**

| marker | annotation |
|---|---|
| **[DFR]** | `@Deprecated(forRemoval = true)` |
| **[DEP]** | `@Deprecated`, not for removal |
| **[EXP]** | `@ApiStatus.Experimental` |
| **[OBS]** | `@ApiStatus.Obsolete` |
| **[MVD]** | `@io.papermc.paper.annotation.MinecraftVersionDependent`: tracks vanilla data and may change in any MC update |

Signatures drop `final`, nullness annotations and `@Contract`. `@Nullable` is kept where it matters.

---

## 0. Things that will bite you (read first)

1. **`org.bukkit.event.player.PlayerRespawnEvent` never fires on Canvas 962 [RT].** Paper's `com.destroystokyo.paper.event.player.PlayerPostRespawnEvent` does not fire either [RT]. Respawn runs through Folia's async path, which fires Canvas-only events instead (§6.3). Two ways to choose where a player respawns:
   - **Portable:** call `player.setRespawnLocation(loc, true)` inside `PlayerDeathEvent` [RT].
   - **Canvas-only:** set it in `io.canvasmc.canvas.event.PlayerRespawnAsyncEvent`, registered by reflection [RT].
2. **`PlayerTeleportEvent` does not fire for `Entity#teleportAsync` (any cause) or for ender-pearl teleports [RT].** Canvas fires `io.canvasmc.canvas.event.EntityTeleportAsyncEvent` instead (§5.1). To block pearls, cancel `PlayerLaunchProjectileEvent` or `ProjectileLaunchEvent`.
3. **Spawner items lose their mob type when a non-op survival player places them [RT].**
   - Vanilla only applies `block_entity_data` to `MOB_SPAWNER` for ops, or for creative players with the `minecraft.nbt.place` permission [SRC].
   - Fix: keep the type in the item PDC and set it in `BlockPlaceEvent` [RT] (§7.2).
4. **Entity loot needs an entity.**
   - `LootTable#populateLoot` with no `lootedEntity` throws `IllegalArgumentException: Missing required parameter: <parameter minecraft:this_entity>` [RT].
   - Fix: pass an unspawned entity from `World#createEntity(Location, Class)`, on the region thread that owns the location [RT].
   - Drops gated on `killed_by_player` (blaze rods, breeze rods, wither skulls…) only appear with `.killer(onlinePlayer)` [RT] (§7.5).
5. **Default item text styling** [RT]:
   - Lore lines are filled with `dark_purple` + italic wherever the line does not set them.
   - `CUSTOM_NAME` is wrapped in italic + rarity colour.
   - `ITEM_NAME` gets the rarity colour only, no italic.
   - Fix: set colour **and** `italic=false` on the root component of every line (§1.4).
6. **Item byte serialization limits** [RT]:
   - `serializeAsBytes()` throws for empty stacks and for any amount above 99.
   - `serializeItemsAsBytes` writes `null`/empty entries as empty, and they read back as `ItemStack.empty()` (AIR), never `null`.
7. **Entity scheduler delays must be at least 1** (`IllegalArgumentException: Delay ticks may not be <= 0`) [RT].
8. **Game rules are snake_case registry keys.**
   - Constants live in `org.bukkit.GameRules`; every `GameRule.*` constant is [DFR].
   - `World#isGameRule("keepInventory")` returns `false` [RT].
   - `World#setGameRule` throws off the global region thread [RT].
9. **`WorldBorder#setSize(double, long seconds)` is [DFR].** Use `changeSize(double, long ticks)`.
10. **Inventory close reasons:**
    - With a GUI open, quitting fires `InventoryCloseEvent(DISCONNECT)` *before* `PlayerQuitEvent`; dying fires `DEATH` [SRC].
    - Then `UNLOADED` fires for the player's own inventory view (type `CRAFTING`) on quit and on respawn [RT].
    - Teleporting no longer closes inventories (`Reason.TELEPORT` is [DEP] since 1.21.10).
11. **Chat renderers do not change the signed body.**
    - The `AsyncChatEvent` renderer output reaches clients as the *unsigned content* of a player-chat packet with chat type `paper:raw`; the body stays the original text [RT].
    - Vanilla clients with "Only Show Secure Chat" drop unsigned content and show the body. That is vanilla client behaviour; it was not tested here.
12. **`org.bukkit.Sound#key()` / `getKey()` are [DFR]**, and `org.bukkit.Sound` is an `OldEnum`, scheduled for removal in "1.22". Build sounds from `Key.key("ui.button.click")`.
13. **Damage right after a respawn is dropped silently.** For about 60 ticks after respawning, damage does nothing and fires no `EntityDamageEvent` [RT].
14. **`Block#getState()` returns a live block-entity state on Canvas.** With the default `tile-entity-snapshot-creation: false`, setters write straight to the block; use `getState(true)` for a copy (§7.4).
15. **Server links:**
    - They are sent during the configuration phase, so configure them before players join.
    - `PlayerLinksSendEvent` runs on the global region thread with a `PlayerConfigurationConnection` (there is no `Player` yet) [RT].
16. **Experimental API you might touch:**
    - `LocationInventoryViewBuilder` (what `MenuType.X.builder()` returns for most menus)
    - `io.papermc.paper.registry.tag.Tag`
    - the `io.papermc.paper.registry.set` package (`RegistryKeySet`, `RegistrySet`)
    - `io.papermc.paper.math.Position`
    - `ItemType#typed()`, `ItemType#getDefaultData(..)`
    - `Entity#copy()`, `Entity#createSnapshot()`
    - `LifecycleEvents.TAGS` / `DATAPACK_DISCOVERY`

---

## 1. Items

### 1.1 Creating stacks
| signature | notes |
|---|---|
| `static ItemStack ItemStack.of(Material type)` | amount 1. `ItemStack.of(Material.AIR).isEmpty() == true` [RT] |
| `static ItemStack ItemStack.of(Material type, int amount)` | `amount` must be > 0: `of(STONE, 0)` → `IllegalArgumentException: amount must be greater than 0`. Values above the max stack size are accepted in memory (`of(STONE, 200).getAmount() == 200`) [RT] |
| `ItemStack ItemType#createItemStack()` / `createItemStack(int amount)` | e.g. `ItemType.DIAMOND.createItemStack()` → DIAMOND x1 [RT] |
| `static ItemStack ItemStack.empty()`, `boolean isEmpty()` | |
| `new ItemStack(Material)`, `new ItemStack(Material,int)`, `new ItemStack(ItemStack)` | [OBS since 1.21]. Prefer `of(..)` |
| `new ItemStack(Material,int,short)` [DEP]; `new ItemStack(Material,int,short,Byte)` [DFR] | |
| `ItemType.Typed<M>#createItemStack(@Nullable Consumer<? super M>)`, `(int, Consumer)`; `ItemType#typed()` | [EXP] |
| `ItemStack withType(Material)` | `setType(Material)` is [DEP] |
| `asOne()`, `asQuantity(int)`, `add()`, `add(int)`, `subtract()`, `subtract(int)`, `clone()`, `setAmount(int)` | `setAmount(100)` is not range-checked [RT], but such a stack cannot be serialized (§1.5) |

### 1.2 Data-component accessors (`ItemStack` implements `io.papermc.paper.datacomponent.DataComponentHolder`) [C][RT]
```java
<T> @Nullable T getData(DataComponentType.Valued<T> type)        // EFFECTIVE value: prototype default or patched [RT]
<T> @Nullable T getDataOrDefault(DataComponentType.Valued<? extends T> type, @Nullable T fallback)
boolean hasData(DataComponentType type)
Set<DataComponentType> getDataTypes()                              // includes prototype components (sword: 18 defaults + patches) [RT]
<T> void setData(DataComponentType.Valued<T> type, T value)
<T> void setData(DataComponentType.Valued<T> type, DataComponentBuilder<T> valueBuilder)   // pass a builder directly
void setData(DataComponentType.NonValued type)                      // e.g. UNBREAKABLE, GLIDER
void unsetData(DataComponentType type)       // removes even a prototype default; overridden=true afterwards
void resetData(DataComponentType type)       // back to the prototype default
boolean isDataOverridden(DataComponentType type)
void copyDataFrom(ItemStack source, Predicate<DataComponentType> filter)
boolean matchesWithoutData(ItemStack item, Set<DataComponentType> excludeTypes)
boolean matchesWithoutData(ItemStack item, Set<DataComponentType> excludeTypes, boolean ignoreCount)
List<Component> computeTooltipLines(io.papermc.paper.inventory.tooltip.TooltipContext ctx, @Nullable Player player)
// TooltipContext.create() / create(boolean advanced, boolean creative)
```
Observed on stock 26.2 [RT]:
- **Plain `DIAMOND`:** `getData(RARITY)=COMMON`, `getData(MAX_STACK_SIZE)=64`, `getData(ITEM_NAME)` = `{"translate":"item.minecraft.diamond"}`.
- **`unsetData(MAX_STACK_SIZE)`:** `hasData=false`, but `getMaxStackSize()` becomes **1**. Call `resetData(..)` to restore 64.
- **`ENCHANTED_GOLDEN_APPLE`:** `getData(RARITY)=RARE`, `ENCHANTMENT_GLINT_OVERRIDE=true` by default.
- **`MAX_STACK_SIZE` range:** 99 is accepted. 100 throws `IllegalArgumentException: Failed to encode data component ... (Value must be within range [1;99]: 100)`.
- **Swords:** `MAX_STACK_SIZE=16` is accepted with no validation, even though the javadoc says values above 1 are mutually exclusive with `MAX_DAMAGE`. That stack serializes, and the client is not kicked.
- **`TooltipDisplay.hideTooltip(true)`:** `computeTooltipLines(..)` returns 0 lines.
- **`ITEM_NAME = Component.empty()`:** one empty white line remains. Use `hideTooltip` for filler panes.

`DataComponentTypes` is a `final class` marked [MVD]. `DataComponentType extends Keyed` has `boolean isPersistent()`, with nested `Valued<T>` and `NonValued`.

### 1.3 `io.papermc.paper.datacomponent.DataComponentTypes` (requested ones; exact keys)
| constant | vanilla key | value type | factory / builder (all `@ApiStatus.NonExtendable`) |
|---|---|---|---|
| `CUSTOM_NAME` | `custom_name` | `net.kyori.adventure.text.Component` | — (anvil-renamable, rendered italic) |
| `ITEM_NAME` | `item_name` | `Component` | — (not italic, no anvil change) |
| `LORE` | `lore` | `io.papermc.paper.datacomponent.item.ItemLore` | `ItemLore.lore(List<? extends ComponentLike>)` → `ItemLore`; `ItemLore.lore()` → `ItemLore.Builder`: `lines(List<? extends ComponentLike>)`, `addLine(ComponentLike)`, `addLines(List<? extends ComponentLike>)`, `build()`. Getters `List<Component> lines()`, `List<Component> styledLines()` (styled = with the dark_purple/italic fallback applied) |
| `ENCHANTMENT_GLINT_OVERRIDE` | `enchantment_glint_override` | `Boolean` | — |
| `TOOLTIP_DISPLAY` | `tooltip_display` | `TooltipDisplay` | `TooltipDisplay.tooltipDisplay()` → `Builder`: `hideTooltip(boolean)`, `addHiddenComponents(DataComponentType...)`, `hiddenComponents(Set<DataComponentType>)`. Getters `hideTooltip()`, `hiddenComponents()` |
| `MAX_STACK_SIZE` | `max_stack_size` | `Integer` (1..99) | — |
| `CUSTOM_MODEL_DATA` | `custom_model_data` | `CustomModelData` | `CustomModelData.customModelData()` → `Builder`: `addFloat(float)`, `addFloats(List<Float>)`, `addFlag(boolean)`, `addFlags(List<Boolean>)`, `addString(String)`, `addStrings(List<String>)`, `addColor(org.bukkit.Color)`, `addColors(List<Color>)`. Getters `floats()/flags()/strings()/colors()` |
| `PROFILE` | `profile` | `ResolvableProfile` | `ResolvableProfile.resolvableProfile(com.destroystokyo.paper.profile.PlayerProfile)`; `ResolvableProfile.resolvableProfile()` → `Builder`: `name(@Nullable String)`, `uuid(@Nullable UUID)`, `addProperty(ProfileProperty)`, `addProperties(Collection<ProfileProperty>)`, `skinPatch(SkinPatch)`, `skinPatch(Consumer<SkinPatchBuilder>)`. Getters `uuid()`, `name()`, `properties()`, `boolean dynamic()`, `CompletableFuture<PlayerProfile> resolve()`, `skinPatch()` |
| `DYED_COLOR` | `dyed_color` | `DyedItemColor` | `DyedItemColor.dyedItemColor(Color)`; `dyedItemColor()` → `Builder.color(Color)`; getter `color()` |
| `RARITY` | `rarity` | `org.bukkit.inventory.ItemRarity` | enum `COMMON(WHITE)`, `UNCOMMON(YELLOW)`, `RARE(AQUA)`, `EPIC(LIGHT_PURPLE)`; `TextColor color()` |
| `UNBREAKABLE` | `unbreakable` | NonValued | `setData(UNBREAKABLE)` |
| `ITEM_MODEL` / `TOOLTIP_STYLE` | `item_model` / `tooltip_style` | `net.kyori.adventure.key.Key` | — |
| `ENCHANTMENTS` / `STORED_ENCHANTMENTS` | `enchantments` / `stored_enchantments` | `ItemEnchantments` | `itemEnchantments(Map<Enchantment,Integer>)`; builder `add(Enchantment,int)`, `addAll(Map)` |
| `CONTAINER` | `container` | `ItemContainerContents` | `containerContents(List<ItemStack>)`; builder `add(ItemStack)`, `addAll(List)` |
| `BLOCK_DATA` | **`block_state`** | `BlockItemDataProperties` | block *state properties* only (`createBlockData(BlockType)`, `applyTo(BlockData)`), **not** block-entity data |
| `DAMAGE`, `MAX_DAMAGE` | `damage`, `max_damage` | `Integer` | — |

**Not exposed as data components [SRC]:**
- `custom_data`: use the PDC (§1.6).
- `block_entity_data`: reach it through `BlockStateMeta` (§7.2). It shows up in `getDataTypes()` as `block_entity_data` once set [RT].
- `entity_data`, `bucket_entity_data`, `debug_stick_state`, `lock`, `creative_slot_lock`, `bees`.

The remaining constants are `USE_EFFECTS`, `MINIMUM_ATTACK_CHARGE`, `DAMAGE_TYPE`, `CAN_PLACE_ON`, `CAN_BREAK`, `ATTRIBUTE_MODIFIERS`, `REPAIR_COST`, `INTANGIBLE_PROJECTILE`, `FOOD`, `CONSUMABLE`, `USE_REMAINDER`, `USE_COOLDOWN`, `DAMAGE_RESISTANT`, `TOOL`, `WEAPON`, `ENCHANTABLE`, `EQUIPPABLE`, `REPAIRABLE`, `GLIDER`, `DEATH_PROTECTION`, `BLOCKS_ATTACKS`, `PIERCING_WEAPON`, `KINETIC_WEAPON`, `ATTACK_RANGE`, `SWING_ANIMATION`, `DYE`, `MAP_*`, `CHARGED_PROJECTILES`, `BUNDLE_CONTENTS`, `POTION_CONTENTS`, `POTION_DURATION_SCALE`, `SUSPICIOUS_STEW_EFFECTS`, `WRITABLE_BOOK_CONTENT`, `WRITTEN_BOOK_CONTENT`, `TRIM`, `INSTRUMENT`, `PROVIDES_TRIM_MATERIAL`, `OMINOUS_BOTTLE_AMPLIFIER`, `JUKEBOX_PLAYABLE`, `PROVIDES_BANNER_PATTERNS`, `RECIPES`, `LODESTONE_TRACKER`, `FIREWORK_EXPLOSION`, `FIREWORKS`, `NOTE_BLOCK_SOUND`, `BANNER_PATTERNS`, `BASE_COLOR`, `POT_DECORATIONS`, `SULFUR_CUBE_CONTENT`, `CONTAINER_LOOT`, `BREAK_SOUND`, plus the `*_VARIANT`/`*_COLOR` entity-variant types.

**Heads** [RT]:
- `resolvableProfile().name("Notch").build()` → `dynamic()=true`: the client resolves the skin itself.
- `resolvableProfile(profileWithTexturesProperty)` → `dynamic()=false`, the properties are embedded, and the skin is fixed.
- The `ResolvableProfile#resolve()` javadoc example uses `Bukkit.getScheduler()`, which **throws on Canvas**. Continue on an entity or region scheduler instead.

### 1.4 Text styling on items (lore/name defaults) [RT]
Vanilla `ItemLore` sets `LORE_STYLE = Style.EMPTY.withColor(DARK_PURPLE).withItalic(true)`. Each line is merged with `ComponentUtils.mergeStyles(line, LORE_STYLE)`, so the line's own explicit values win and only unset fields fall back [SRC]. Server-side `computeTooltipLines` output for a sword with rarity EPIC:

| what you set | rendered (gson) |
|---|---|
| `CUSTOM_NAME = text("Custom Name")` | `{"italic":true,"color":"light_purple","extra":["Custom Name"],"text":""}` (rarity colour + italic wrapper) |
| `CUSTOM_NAME = text("Named", AQUA).decoration(ITALIC,false)` | wrapper `{"italic":true,"color":"white",...}` but the child carries `"italic":false,"color":"aqua"`, so it renders **non-italic aqua** |
| `ITEM_NAME = text("ItemNamed")` (COMMON) | `{"color":"white","extra":["ItemNamed"]}`: no italic |
| lore `text("plain line")` | `{"italic":true,"color":"dark_purple","text":"plain line"}` |
| lore `text("x", GRAY).decoration(ITALIC,false)` | `{"italic":false,"color":"gray"}` |
| lore `text().append(text("child")).color(GOLD)` (root has no italic) | `{"italic":true,"color":"gold",...}` (italic leaks in) |
| lore `text("", WHITE).decoration(ITALIC,false).append(text("child inherits"))` | `{"italic":false,"color":"white",...}`: children inherit |
| lore `text("x").applyFallbackStyle(Style.style(GRAY, ITALIC.withState(false)))` | `{"italic":false,"color":"gray"}` |

Helper (verified output above):
```java
static Component plain(ComponentLike c) {            // net.kyori.adventure.text.format.*
  return c.asComponent().applyFallbackStyle(Style.style(NamedTextColor.WHITE, TextDecoration.ITALIC.withState(false)));
}
// alternatives: .decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.GRAY)
//               .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
// MiniMessage: "<!italic><gray>..."
```
Signatures (Adventure 5.2, `Component`):
- `Component applyFallbackStyle(Style)` and `applyFallbackStyle(StyleBuilderApplicable...)`
- `colorIfAbsent(@Nullable TextColor)`
- `decorationIfAbsent(TextDecoration, TextDecoration.State)`
- `decoration(TextDecoration, boolean)`
- `TextDecoration.ITALIC.withState(boolean)`

Name getters on `ItemStack` [RT]:
- `Component effectiveName()`: the name shown in the inventory, with the italic and rarity wrapper applied.
- `Component displayName()`: the bracketed name **with a `show_item` hover event** (bracketed name, for chat).
- `HoverEvent<HoverEvent.ShowItem> asHoverEvent()`: the data-component map holds only the *patched* components, e.g. `custom_name`, `custom_model_data`, `rarity`, `enchantment_glint_override`, `lore`, `item_name`, `tooltip_display` [RT].

### 1.5 Byte serialization (DB storage) [RT][SRC]
```java
byte[] ItemStack#serializeAsBytes()                                  // throws IllegalArgumentException("Empty item cannot be serialized")
static ItemStack ItemStack.deserializeBytes(byte[] bytes)
static byte[] ItemStack.serializeItemsAsBytes(Collection<ItemStack> items)   // null/empty allowed
static byte[] ItemStack.serializeItemsAsBytes(@Nullable ItemStack[] items)
static ItemStack[] ItemStack.deserializeItemsFromBytes(byte[] bytes)
```
**Format:**
- A single item is the vanilla `ItemStack.CODEC` encoded to NBT, with the current `DataVersion` added, then GZIP-compressed (`NbtIo.writeCompressed`) [SRC].
- On read, it is upgraded through DataFixerUpper. Reading data from a *newer* `DataVersion` throws ("Server downgrades are not supported") [SRC].
- The bulk format is `byte version=1`, then `int count`, then per item `int len` followed by `len` bytes; `len=0` marks an empty slot [SRC].

**Observed** [RT]:
- **Round trip:** with a custom name, lore, CMD, rarity, glint and PDC, `serializeAsBytes` gave 416 bytes, and `equals` and `isSimilar` were both true after the round trip.
- **Bulk with gaps:** `serializeItemsAsBytes([sword, null, empty, diamond x5])` reads back as `[DIAMOND_SWORD x1, AIR(empty), AIR(empty), DIAMOND x5]`. `null` comes back as `ItemStack.empty()`.
- **Over-stacked items:** `ItemStack.of(STONE,200).serializeAsBytes()` throws `IllegalStateException: Value must be within range [1;99]: 200`. Virtual stacks (auction house, orders) must be stored as `(single item bytes, long amount)`.
- **Strings:** use `Base64.getEncoder()` if you need a string column.

### 1.6 Persistent data on items [RT]
- `PersistentDataContainerView ItemStack#getPersistentDataContainer()`: read-only view (`io.papermc.paper.persistence.PersistentDataContainerView`).
- `boolean ItemStack#editPersistentDataContainer(Consumer<PersistentDataContainer> consumer)`:
  - returns `true` even for a no-op edit
  - returns `false` on an empty stack, which cannot hold data
  - stores data in the vanilla `custom_data` component
- **View** methods:
  - `<P,C> boolean has(NamespacedKey, PersistentDataType<P,C>)`, `boolean has(NamespacedKey)`
  - `<P,C> @Nullable C get(NamespacedKey, PersistentDataType<P,C>)`
  - `<P,C> C getOrDefault(NamespacedKey, PersistentDataType<P,C>, C)`
  - `Set<NamespacedKey> getKeys()`, `boolean isEmpty()`, `int getSize()`
  - `void copyTo(PersistentDataContainer, boolean replace)`
  - `byte[] serializeToBytes()`, `PersistentDataAdapterContext getAdapterContext()`
- **Mutable `PersistentDataContainer`** adds:
  - `<P,C> void set(NamespacedKey, PersistentDataType<P,C>, C)`, `void remove(NamespacedKey)`
  - `void readFromBytes(byte[], boolean clear)`, `readFromBytes(byte[])`
- **`PersistentDataType` constants:**
  - `BYTE`, `SHORT`, `INTEGER`, `LONG`, `FLOAT`, `DOUBLE`
  - `BOOLEAN` (stored as a byte), `STRING`
  - `BYTE_ARRAY`, `INTEGER_ARRAY`, `LONG_ARRAY`
  - `TAG_CONTAINER`, `TAG_CONTAINER_ARRAY` [DEP]
  - `LIST`, e.g. `PersistentDataType.LIST.strings()`, `LIST.listTypeFrom(type)`
- **Similarity:**
  - Different PDC values make stacks not `isSimilar` [RT].
  - Removing the last key also removes `custom_data`, so the stack is `isSimilar` to a plain one again [RT].
- **Holders:** `TileState` (block entities, incl. `CreatureSpawner`) implements `PersistentDataHolder` → mutable `getPersistentDataContainer()`. Write the state back with `state.update(...)` [RT].

### 1.7 Equality
| method | behaviour [RT] |
|---|---|
| `boolean isSimilar(@Nullable ItemStack)` | Same type and same components; ignores amount. `a.isSimilar(a.asQuantity(3)) == true` |
| `boolean equals(Object)` | Also compares the amount: `a.equals(a.asQuantity(3)) == false` |
| `matchesWithoutData(other, Set.of(DataComponentTypes.CUSTOM_NAME))` | Compares while ignoring the given components; this case returned `true` when `isSimilar` was `false` |

### 1.8 Registries: `Material` vs `ItemType` / `BlockType`
- **`org.bukkit.Material`** is *not* deprecated. It is still what `ItemStack#getType()` and `Block#getType()` return. The `LEGACY_*` constants are [DFR].
  - `@Nullable ItemType asItemType()`, `@Nullable BlockType asBlockType()`
  - `isItem()`, `isBlock()`, `isAir()`, `isLegacy()`, `getMaxStackSize()`, `getKey()`
  - `getItemRarity()` [DFR], `getCreativeCategory()` [DFR], `isEmpty()` [DEP since 1.21.5]
  - `static @Nullable Material matchMaterial(String)`
- **`org.bukkit.inventory.ItemType`** (interface; not experimental as a type):
  - `ItemType.Typed<M>` constants, e.g. `ItemType.Typed<BlockStateMeta> SPAWNER`, `ItemType.Typed<SkullMeta> PLAYER_HEAD`
  - `createItemStack(..)`, `hasBlockType()`, `BlockType getBlockType()`
  - `getMaxStackSize()`, `getMaxDurability()`, `isEdible()`, `isFuel()`, `getBurnDuration()`, `isCompostable()`, `getCompostChance()`, `@Nullable getCraftingRemainingItem()`
  - `@Nullable ItemRarity getItemRarity()`
  - `<T> getDefaultData(Valued<T>)` [EXP], `hasDefaultData(DataComponentType)`, `getDefaultDataTypes()` [EXP]
  - `asMaterial()` [DEP], `getCreativeCategory()` [DFR], `isEnabledByFeature(World)` [DFR], `getTranslationKey()` [DFR]
- **Registry access** [RT]:
  - `Registry<ItemType> Registry.ITEM` is the **same instance** as `RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM)`.
  - Also `Registry.BLOCK` (`RegistryKey.BLOCK`), `Registry.ENTITY_TYPE`, `Registry.MENU`, `Registry.GAME_RULE`, `Registry.DATA_COMPONENT_TYPE`.
  - `Registry.SOUNDS` and `Registry.EFFECT` are [OBS since 1.21.4]; use `RegistryKey.SOUND_EVENT` / `MOB_EFFECT`.
  - `RegistryAccess#getRegistry(Class)` is [DFR].
- **`org.bukkit.Registry<T>` methods:**
  - lookup: `@Nullable T get(NamespacedKey)`, `get(Key)`, `get(TypedKey<T>)`, `T getOrThrow(Key | TypedKey | NamespacedKey)`
  - keys: `@Nullable NamespacedKey getKey(T)`, `NamespacedKey getKeyOrThrow(T)`
  - iteration: `Stream<T> stream()`, `Stream<NamespacedKey> keyStream()`, `int size()`, `Iterable<T>`
  - tags: `boolean hasTag(TagKey<T>)`, `io.papermc.paper.registry.tag.Tag<T> getTag(TagKey<T>)`, `Collection<T> getTagValues(TagKey<T>)`
  - `@Nullable T match(String)` [DFR]
- **Counts on 26.2** [RT]:
  - The registry has **1537** entries including `minecraft:air`; non-legacy `Material` with `isItem()` also counts 1537.
  - Iteration order is registry order, not alphabetical (the first entry is `minecraft:blue_harness`), so sort by key.
  - `world.isEnabled(itemType)` (`FeatureFlagSetHolder`, which `World` implements) was `true` for all 1537 on this world.
  - Rarity counts: COMMON 1422, UNCOMMON 78, RARE 18, EPIC 19. Samples: `NETHER_STAR`=RARE, `ENCHANTED_GOLDEN_APPLE`=RARE, `DRAGON_EGG`=EPIC, `TOTEM_OF_UNDYING`=UNCOMMON, `DIAMOND`=COMMON.
- **Rarity of a stack:** use `stack.getData(DataComponentTypes.RARITY)`, which gives the effective value. `ItemStack#getRarity()` is [DFR] and returns the [DFR] enum `io.papermc.paper.inventory.ItemRarity`.

Worth-table generation (pattern; uses only the APIs above):
```java
World world = Bukkit.getWorlds().getFirst();
List<ItemType> all = Registry.ITEM.stream()
    .filter(t -> t != ItemType.AIR && world.isEnabled(t))
    .sorted(Comparator.comparing(t -> t.getKey().asString()))
    .toList();
// There is no API for "operator/creative-only" items or creative tabs (getCreativeCategory is [DFR]).
// Keep an explicit exclusion set (all ids below verified to exist in 26.2), e.g.:
//   command_block, chain_command_block, repeating_command_block, command_block_minecart, structure_block,
//   structure_void, jigsaw, barrier, light,
//   debug_stick, knowledge_book, test_block, test_instance_block, bedrock, end_portal_frame,
//   reinforced_deepslate, budding_amethyst, spawner, trial_spawner, vault, petrified_oak_slab,
//   farmland, dirt_path, chorus_plant, frogspawn
// plus MaterialTags.SPAWN_EGGS (88 entries) and MaterialTags.INFESTED_BLOCKS.
```

### 1.9 Tags
- **Bukkit tags:**
  - `org.bukkit.Tag<T extends Keyed>`: `boolean isTagged(T)`, `Set<T> getValues()`.
  - Item tags are `Tag<Material>` constants named `ITEMS_*`, e.g. `Tag.ITEMS_SWORDS`, `ITEMS_AXES`, `ITEMS_PICKAXES`, `ITEMS_SHOVELS`, `ITEMS_HOES`, `ITEMS_SPEARS`, `ITEMS_HEAD_ARMOR`, `ITEMS_CHEST_ARMOR`, `ITEMS_LEG_ARMOR`, `ITEMS_FOOT_ARMOR`, `ITEMS_ENCHANTABLE_*`, `ITEMS_LOGS`, `ITEMS_PLANKS`, `ITEMS_WOOL`, `ITEMS_BEDS`… (all vanilla item tags).
  - Some block tags double as item tags: `Tag.SHULKER_BOXES`, `Tag.BEDS`.
  - `Tag.ITEMS_SWORDS.isTagged(Material.DIAMOND_SWORD)` → `true`, 7 values; `Tag.ITEMS_SPEARS` → 7 [RT].
- **Registry tags:**
  - `io.papermc.paper.registry.keys.tags.ItemTypeTagKeys.SWORDS` (`TagKey<ItemType>`); also `BlockTypeTagKeys`, `EntityTypeTagKeys`, …
  - `Registry.ITEM.getTag(ItemTypeTagKeys.SWORDS).contains(TypedKey.create(RegistryKey.ITEM, Key.key("minecraft:diamond_sword")))` → `true`; `getTagValues(..)` → 7 [RT].
  - `io.papermc.paper.registry.tag.Tag` is [EXP]. Its `RegistryKeySet` supertype is in the [EXP] `io.papermc.paper.registry.set` package.
  - Factories: `TagKey.create(RegistryKey<T>, Key | String)`, `RegistryKey#tagKey(String)`, `TypedKey.create(RegistryKey<T>, Key | String)`.
- **`com.destroystokyo.paper.MaterialTags`** (Paper; class not deprecated):
  - `MaterialSetTag` constants with `isTagged(Material | ItemStack | Block | BlockData | BlockState)`.
  - `SPAWN_EGGS` has 88 entries, `ORES` 19 [RT].
  - Deprecated since 1.21.8 and replaced by vanilla tags: `ARROWS`, `BEDS`, `COALS`, `CONCRETE_POWDER`, `DOORS`, `DYES`, `FENCE_GATES`, `FENCES`, `GLAZED_TERRACOTTA`, `PRESSURE_PLATES`, `SHULKER_BOXES`, `TRAPDOORS`, `WOODEN_DOORS`, `WOODEN_FENCES`, `WOODEN_TRAPDOORS`, `LANTERNS`, `RAILS`, `SWORDS`, `SHOVELS`, `PICKAXES`, `AXES`, `HOES`.
  - Still useful: `SPAWN_EGGS`, `ORES`, `RAW_ORES`, `DEEPSLATE_ORES`, `HELMETS`, `CHESTPLATES`, `LEGGINGS`, `BOOTS`, `ARMOR`, `BOWS`, `*_TOOLS` (wooden … netherite), `MUSIC_DISCS`, `INFESTED_BLOCKS`, `GLASS`, `STAINED_GLASS`, `SKULLS`, `ENCHANTABLE`, `COLORABLE`, `HORSE_ARMORS`, `GOLDEN_APPLES`, `TORCHES`, `SIGNS`, …

### 1.10 `ItemMeta` notes (still supported)
- **Names and lore:**
  - `customName(@Nullable Component)` / `Component customName()` / `hasCustomName()` are the new names.
  - `displayName(..)` / `hasDisplayName()` are [OBS since 1.21.4]; all `String` variants are [DEP].
  - Also `itemName(Component)`, `lore(List<? extends Component>)`.
- **Component setters:** `setEnchantmentGlintOverride(Boolean)`, `setHideTooltip(boolean)`, `setMaxStackSize(Integer)`, `setRarity(ItemRarity)`, `setItemModel(NamespacedKey)`, `setTooltipStyle(NamespacedKey)`, `setCustomModelDataComponent(..)`. `setCustomModelData(Integer)` is [DEP since 1.21.5].
- **`ItemStack#editMeta`:** `boolean editMeta(Consumer<? super ItemMeta>)` and `<M extends ItemMeta> boolean editMeta(Class<M>, Consumer<? super M>)`.
- **`SkullMeta`:** `setPlayerProfile(@Nullable com.destroystokyo.paper.profile.PlayerProfile)`, `getPlayerProfile()`, `setOwningPlayer(OfflinePlayer)`. `setOwner(String)` is [DEP]; `setOwnerProfile(org.bukkit.profile.PlayerProfile)` is [DEP].
- **`BlockStateMeta`:** see §7.2.

---

## 2. Inventories

### 2.1 Custom inventories with a holder [RT]
```java
static Inventory Bukkit.createInventory(@Nullable InventoryHolder owner, int size, Component title)   // size % 9 == 0, 9..54, else IAE
static Inventory Bukkit.createInventory(@Nullable InventoryHolder owner, InventoryType type, Component title)
// String-title overloads are [DEP]; createInventory(owner, size) / (owner, type) use the default title.
interface org.bukkit.inventory.InventoryHolder { Inventory getInventory(); }
@Nullable InventoryView HumanEntity#openInventory(Inventory)   // null if InventoryOpenEvent was cancelled
void HumanEntity#openInventory(InventoryView view)             // view must have been created for this player
```
- **Holder pattern:** make a holder class whose `getInventory()` returns the inventory it created. Check `view.getTopInventory().getHolder() instanceof MyHolder` in every listener [RT].
- **Opening** sent `OpenScreen` with type `minecraft:generic_9x3` and the given title [RT].
- **`InventoryOpenEvent`:**
  - `getPlayer()`, `getView()`, `getInventory()`, `setCancelled(boolean)`
  - `@Nullable Component titleOverride()` / `titleOverride(@Nullable Component)`
- **`InventoryView`:**
  - `getTopInventory()`, `getBottomInventory()`, `getPlayer()`, `getType()`, `getCursor()`, `setCursor(..)`
  - `@Nullable Inventory getInventory(int rawSlot)`, `int convertSlot(int rawSlot)`, `SlotType getSlotType(int)`, `countSlots()`
  - `void open()`, `void close()`, `default Component title()`, `@Nullable MenuType getMenuType()`
  - `int OUTSIDE = -999`
  - `getTitle()` [DEP], `getOriginalTitle()` / `setTitle(String)` [DEP since 1.21.1]; the `InventoryView.Property` enum is [DFR since 1.21]
- **Thread:** open and close on the player's region thread (`player.getScheduler()`). See `runtime.md` §6: `openInventory` off-thread half-applies.

### 2.2 `org.bukkit.inventory.MenuType` (not experimental as a type) [RT][SRC]
- **Constants and their typed views:** all are `MenuType.Typed<View, Builder>`.

  | builder kind | constants (view type) |
  |---|---|
  | `InventoryViewBuilder` | `GENERIC_9X1`, `GENERIC_9X2`, `GENERIC_9X4`, `GENERIC_9X5` (`InventoryView`) |
  | `LocationInventoryViewBuilder` [EXP] | `GENERIC_9X3`, `GENERIC_9X6`, `GENERIC_3X3`, `CRAFTING`, `GRINDSTONE`, `HOPPER`, `SHULKER_BOX`, `SMITHING`, `CARTOGRAPHY_TABLE` (`InventoryView`); `CRAFTER_3X3` (`CrafterView`); `ANVIL` (`AnvilView`); `BEACON` (`BeaconView`); `BLAST_FURNACE`, `FURNACE`, `SMOKER` (`FurnaceView`); `BREWING_STAND` (`BrewingStandView`); `ENCHANTMENT` (`EnchantmentView`); `LECTERN` (`LecternView`); `LOOM` (`LoomView`); `STONECUTTER` (`StonecutterView`) |
  | `MerchantInventoryViewBuilder` | `MERCHANT` (`MerchantView`) |
- **`MenuType.Typed<V,B>`:**
  - `default V create(HumanEntity player)`, `V create(HumanEntity player, @Nullable Component title)`
  - `B builder()`
  - `create(HumanEntity, String)` is [DEP]
- **`MenuType`:** `InventoryView create(HumanEntity, @Nullable Component)`, `typed()`, `typed(Class<V>)`, `Class<? extends InventoryView> getInventoryViewClass()`.
- **Builders:**
  - `InventoryViewBuilder<V>`: `copy()`, `title(@Nullable Component)`, `V build(HumanEntity)`.
  - `LocationInventoryViewBuilder<V>` [EXP] adds `checkReachable(boolean)` and `location(Location)`.
  - `MerchantInventoryViewBuilder<V>` adds `merchant(Merchant)` and `checkReachable(boolean)`.
- **`checkReachable` defaults to `false`** [SRC], so menus are not closed for distance [RT].
- **What each menu opens** (`create(player, title)` + `openInventory(view)`; still open after 4 ticks) [RT]:

| MenuType | `getType()` | view class | top-inventory holder |
|---|---|---|---|
| ANVIL | ANVIL | `AnvilView` | `CraftBlockInventoryHolder` |
| CRAFTING | WORKBENCH | `InventoryView` | **the `Player`** |
| SMITHING | SMITHING | `InventoryView` | block holder |
| STONECUTTER | STONECUTTER | `StonecutterView` | block holder |
| GRINDSTONE | GRINDSTONE | `InventoryView` | block holder |
| LOOM | LOOM | `LoomView` | block holder |
| CARTOGRAPHY_TABLE | CARTOGRAPHY | `InventoryView` | block holder |
| ENCHANTMENT | ENCHANTING | `EnchantmentView` | block holder |
| GENERIC_9X3 / 9X6 / HOPPER | CHEST / CHEST / HOPPER | `InventoryView` | `null` |

  `MenuType.ANVIL.builder().title(..).checkReachable(false).location(loc).build(player).open()` also works [RT].
- **Do not identify MenuType views by holder.** Keep the `InventoryView` reference, or use `Bukkit.createInventory(holder, …)` for chest GUIs.
- **Location gotcha:** these menus are backed by block entities: `GENERIC_9X3`, `GENERIC_3X3`, `CRAFTER_3X3`, `HOPPER`, `SHULKER_BOX`, `FURNACE`, `BLAST_FURNACE`, `SMOKER`, `BREWING_STAND`, `BEACON`, `LECTERN`. Built with `.location()` pointing at a real block entity of the same menu type, they open **that block's real inventory** (`CraftBlockEntityInventoryViewBuilder`) [SRC]. `GENERIC_9X6` uses a double-chest builder.
- **`AnvilView`:**
  - `AnvilInventory getTopInventory()`, `@Nullable String getRenameText()`
  - `getRepairCost()` / `setRepairCost(int)`, `getRepairItemCountCost()` / `setRepairItemCountCost(int)`, `getMaximumRepairCost()` / `setMaximumRepairCost(int)`
  - `bypassesEnchantmentLevelRestriction()` / `bypassEnchantmentLevelRestriction(boolean)`
- **Deprecated `HumanEntity` openers** [DEP since 1.21.4]:
  - `openWorkbench(@Nullable Location, boolean force)`, `openEnchanting`, `openAnvil`, `openCartographyTable`, `openGrindstone`, `openLoom`, `openSmithingTable`, `openStonecutter`, `openMerchant(..)`
  - They still work: `openWorkbench(null, true)` → WORKBENCH, title "Crafting" [RT].
- **Ender chest:** `player.openInventory(player.getEnderChest())` → type ENDER_CHEST, title "Ender Chest", 27 slots; the client sees `generic_9x3` [RT]. `Inventory HumanEntity#getEnderChest()`.

### 2.3 Click / drag / close events
**`InventoryClickEvent extends InventoryInteractEvent`.**
- Inherited methods:
  - `getWhoClicked()`, `setCancelled(boolean)`, `isCancelled()`, `setResult(Event.Result)`
  - `getView()`, `getInventory()` (the *top* inventory), `getViewers()`
- Own methods:
  - `@Nullable Inventory getClickedInventory()`, `int getRawSlot()`, `int getSlot()`
  - `SlotType getSlotType()`, `ClickType getClick()`, `InventoryAction getAction()`
  - `@Nullable ItemStack getCurrentItem()`, `setCurrentItem(..)`, `ItemStack getCursor()`
  - `int getHotbarButton()` (−1 unless NUMBER_KEY)
  - `isLeftClick()`, `isRightClick()`, `isShiftClick()`
  - `setCursor(..)` is [DEP]

Observed with a 27-slot holder GUI and a real protocol client [RT]:

| client packet | rawSlot | slot | ClickType | InventoryAction | clicked inventory | hotbar |
|---|---|---|---|---|---|---|
| PICKUP btn0 on top slot 13 (emeralds) | 13 | 13 | LEFT | PICKUP_ALL | top (CHEST) | −1 |
| QUICK_MOVE on raw 54 (player hotbar slot 0 below a 9x3) | 54 | **0** | SHIFT_LEFT | MOVE_TO_OTHER_INVENTORY | **bottom (PLAYER)** | −1 |
| SWAP btn2 on 13 | 13 | 13 | NUMBER_KEY | HOTBAR_SWAP | top | **2** |
| THROW btn0 on 13 | 13 | 13 | DROP | DROP_ONE_SLOT | top | −1 |
| THROW btn1 on 13 | 13 | 13 | CONTROL_DROP | DROP_ALL_SLOT | top | −1 |
| PICKUP at −999 | −999 | −999 | LEFT | NOTHING | `null`, SlotType OUTSIDE | −1 |
| PICKUP_ALL (double click) on 13, empty cursor | 13 | 13 | DOUBLE_CLICK | NOTHING | top | −1 |

**Raw slot layout for a 9xN chest view:**
- top `0..9N-1`
- player main inventory `9N..9N+26` (inventory slots 9–35)
- hotbar `9N+27..9N+35` (inventory slots 0–8)

For a 9x3 view the hotbar starts at raw 54. **Cancel every click when the top holder is yours**, including clicks in the bottom inventory: shift-click and double-click can pull items into the GUI.

**`ClickType`** values:
- `LEFT`, `SHIFT_LEFT`, `RIGHT`, `SHIFT_RIGHT`
- `WINDOW_BORDER_LEFT`, `WINDOW_BORDER_RIGHT`, `MIDDLE`, `NUMBER_KEY`, `DOUBLE_CLICK`
- `DROP`, `CONTROL_DROP`, `CREATIVE`, `SWAP_OFFHAND`, `UNKNOWN`

Helpers: `isKeyboardClick()`, `isMouseClick()`, `isCreativeAction()`, `isRightClick()`, `isLeftClick()`, `isShiftClick()`.

**`InventoryAction`** values:
- `NOTHING`
- pickup: `PICKUP_ALL`, `PICKUP_SOME`, `PICKUP_HALF`, `PICKUP_ONE`
- place: `PLACE_ALL`, `PLACE_SOME`, `PLACE_ONE`, `SWAP_WITH_CURSOR`
- drop: `DROP_ALL_CURSOR`, `DROP_ONE_CURSOR`, `DROP_ALL_SLOT`, `DROP_ONE_SLOT`
- `MOVE_TO_OTHER_INVENTORY`, `HOTBAR_MOVE_AND_READD` [DFR], `HOTBAR_SWAP`, `CLONE_STACK`, `COLLECT_TO_CURSOR`, `UNKNOWN`
- bundles: `PICKUP_FROM_BUNDLE`, `PICKUP_ALL_INTO_BUNDLE`, `PICKUP_SOME_INTO_BUNDLE`, `PLACE_FROM_BUNDLE`, `PLACE_ALL_INTO_BUNDLE`, `PLACE_SOME_INTO_BUNDLE`

**`InventoryDragEvent extends InventoryInteractEvent`:**
- `DragType getType()` (`SINGLE` | `EVEN`)
- `@Nullable ItemStack getCursor()`, `setCursor(..)`, `ItemStack getOldCursor()`
- `Map<Integer,ItemStack> getNewItems()`, `Set<Integer> getRawSlots()`, `Set<Integer> getInventorySlots()`
- Observed: dragging 4 dirt across raw 10 and 11 gave `EVEN rawSlots=[10,11] newItems={10=DIRT x2, 11=DIRT x2} oldCursor=DIRT x4` [RT]. After cancelling, the cursor still holds the items.

**`InventoryCloseEvent extends InventoryEvent`:** `getPlayer()`, `getReason()`. `Reason` values:
- `UNKNOWN`, `TELEPORT` [DEP since 1.21.10: inventories are no longer closed on teleport]
- `CANT_USE`, `UNLOADED`, `OPEN_NEW`, `PLAYER`, `DISCONNECT`, `DEATH`, `PLUGIN`

Observed [RT]:
- `player.closeInventory()` → `PLUGIN`
- client close → `PLAYER`
- quit or respawn → `UNLOADED` for the player's own inventory view (type `CRAFTING`)
- An open GUI at quit closes with `DISCONNECT` *before* `PlayerQuitEvent`; at death with `DEATH` [SRC].

Also: `HumanEntity#closeInventory()` and `closeInventory(InventoryCloseEvent.Reason)`.

### 2.4 Cursor and misc
- **Cursor:** `ItemStack HumanEntity#getItemOnCursor()`, `void setItemOnCursor(@Nullable ItemStack)` [RT].
- **Open view:** `InventoryView HumanEntity#getOpenInventory()`. When nothing is open it is the player's own view (`getType()==CRAFTING`).
- **`Player#updateInventory()`:** resends the inventory contents.

---

## 3. Chat and text (Adventure 5.2.0)

### 3.1 `io.papermc.paper.event.player.AsyncChatEvent` [RT]
- **Class:** `public final class AsyncChatEvent extends AbstractChatEvent`; `AbstractChatEvent extends PlayerEvent implements Cancellable`.
- **Methods:**
  - `Set<Audience> viewers()`: mutable; contains the console and players.
  - `void renderer(ChatRenderer)`, `ChatRenderer renderer()`
  - `Component message()`, `void message(Component)`, `Component originalMessage()`
  - `SignedMessage signedMessage()`
  - `isCancelled()`, `setCancelled(boolean)`
- **`io.papermc.paper.chat.ChatRenderer`:**
  - `Component render(Player source, Component sourceDisplayName, Component message, Audience viewer)`
  - `static ChatRenderer defaultRenderer()`
  - `static ChatRenderer viewerUnaware(ChatRenderer.ViewerUnaware)`, where `ViewerUnaware#render(Player, Component, Component)`
- **Observed:**
  - `isAsynchronous()=true`.
  - Viewers were `[TerminalConsoleCommandSender, CraftPlayer]`.
  - The bot's message had `signedMessage().signature()==null` (offline/unsigned).
  - With a `viewerUnaware` renderer, the client received `ClientboundPlayerChat`: chatType `paper:raw`, body `"hello from bot"`, unsignedContent `"[R] ApiBot: hello from bot"`.
- **Deprecated:** `io.papermc.paper.event.player.ChatEvent` is [DEP] (sync chat).
- **Other chat events:** `AsyncChatDecorateEvent` / `AsyncChatCommandDecorateEvent` (preview decoration).
- **Deleting messages:** `Audience#deleteMessage(SignedMessage)` / `deleteMessage(SignedMessage.Signature)`.
- **`net.kyori.adventure.chat.SignedMessage`:**
  - `timestamp()`, `salt()`, `@Nullable Signature signature()`, `@Nullable Component unsignedContent()`, `String message()`
  - `isSystem()`, `canDelete()`
  - `static SignedMessage system(String, @Nullable ComponentLike)`
- **`ChatType`:**
  - constants `CHAT`, `SAY_COMMAND`, `MSG_COMMAND_INCOMING`/`OUTGOING`, `TEAM_MSG_COMMAND_INCOMING`/`OUTGOING`, `EMOTE_COMMAND`
  - `bind(ComponentLike name[, target])` → `ChatType.Bound`
  - `Audience#sendMessage(SignedMessage, ChatType.Bound)`, `sendMessage(Component, ChatType.Bound)`

### 3.2 `net.kyori.adventure.audience.Audience` (5.2) [C]
- **Messages:** `sendMessage(ComponentLike | Component)`, `sendMessage(Component | ComponentLike, ChatType.Bound)`, `sendMessage(SignedMessage, ChatType.Bound)`, `deleteMessage(..)`. **There are no `Identity` overloads in 5.x.**
- **Action bar:** `sendActionBar(ComponentLike | Component)`.
- **Tab list:** `sendPlayerListHeader(..)`, `sendPlayerListFooter(..)`, `sendPlayerListHeaderAndFooter(ComponentLike|Component header, ComponentLike|Component footer)` [RT: tab list header/footer received].
- **Titles:** `showTitle(Title)`, `<T> sendTitlePart(TitlePart<T>, T)`, `clearTitle()`, `resetTitle()`.
- **Boss bars:** `showBossBar(BossBar)`, `hideBossBar(BossBar)`.
- **Sounds:** `playSound(Sound)`, `playSound(Sound, double x, double y, double z)`, `playSound(Sound, Sound.Emitter)`, `stopSound(Sound | SoundStop)`.
- **Books:** `openBook(Book | Book.Builder | BookLike)`.
- **Resource packs:** `sendResourcePacks(..)`, `removeResourcePacks(..)`, `clearResourcePacks()`.
- **Dialogs:** `showDialog(net.kyori.adventure.dialog.DialogLike)`, `closeDialog()`. These are packet sends and are safe from any thread (`runtime.md` §0).
- **Combinators:** `static Audience.audience(Audience...)`, `audience(Iterable)`, `Audience.empty()`, `toAudience()`, `filterAudience(Predicate)`, `forEachAudience(Consumer)`.

### 3.3 Titles: `net.kyori.adventure.title.Title` [RT]
```java
static Title title(Component title, Component subtitle)
static Title title(Component title, Component subtitle, @Nullable Title.Times times)
static Title title(Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks)   // new in 5.x
static Title.Times Title.Times.times(Duration fadeIn, Duration stay, Duration fadeOut)
Title.DEFAULT_TIMES = times(Ticks.duration(10), Ticks.duration(70), Ticks.duration(20))   // net.kyori.adventure.util.Ticks
TitlePart.TITLE / SUBTITLE / TIMES
```
`showTitle(title(text("TitleText"), text("SubText"), 5, 40, 10))` reached the client as three packets: times `in=5 stay=40 out=10`, subtitle, title [RT]. `sendActionBar` reached it as an action-bar packet [RT]. `Player#sendTitle(String,…)` and `showTitle(BaseComponent…)` are [DEP].

### 3.4 Sounds [RT]
- **`net.kyori.adventure.sound.Sound`** (`sealed`):
  - `static Sound sound(Key name, Sound.Source source, float volume, float pitch)`
  - also `sound(Sound.Type, Source, float, float)`, `sound(Supplier<? extends Type>, …)`, the `Source.Provider` variants, and `sound()` → `Builder` (`type`, `source`, `volume`, `pitch`, `seed`)
  - getters `name()`, `source()`, `volume()`, `pitch()`, `seed()`, `asStop()`
- **`Sound.Source`** values: `MASTER`, `MUSIC`, `RECORD`, `WEATHER`, `BLOCK`, `HOSTILE`, `NEUTRAL`, `PLAYER`, `AMBIENT`, `VOICE`, **`UI`**. `UI` arrives on the wire as `SoundSource.UI` [RT].
- **`Sound.Emitter.self()`:** `player.playSound(sound, Sound.Emitter.self())` sends an entity-bound sound packet [RT].
- **Bukkit side:**
  - `org.bukkit.SoundCategory` (implements `Sound.Source.Provider`): `MASTER`, `MUSIC`, `RECORDS`, `WEATHER`, `BLOCKS`, `HOSTILE`, `NEUTRAL`, `PLAYERS`, `AMBIENT`, `VOICE`, `UI`.
  - `Player#playSound(Location, org.bukkit.Sound, SoundCategory, float, float)` with `SoundCategory.UI` → UI source [RT].
- **`org.bukkit.Sound`** is an interface (`OldEnum<Sound>`, `Keyed`, `Sound.Type`) with constants such as `UI_BUTTON_CLICK`. Its `getKey()`/`key()` and static `valueOf`/`values()` are [DFR] (scheduled for removal in "1.22"). Prefer `Sound.sound(Key.key("ui.button.click"), Sound.Source.UI, 1f, 1f)`.
- **Registry:** `RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT)` has 1968 entries. That equals the event count in the 26.2 client `assets/minecraft/sounds.json` (asset index 32, object `9ac006d5…`) [RT].

All 76 keys below exist in the 26.2 client `sounds.json` and in the generated `org.bukkit.Sound` class; 75 of them were also looked up in the live server registry [RT]. Keys marked * have **no subtitle**, so they don't show captions in a UI:

| use | keys |
|---|---|
| clicks | `ui.button.click`*, `block.lever.click`, `block.wooden_button.click_on`, `block.stone_button.click_on`, `entity.item_frame.rotate_item` |
| notes (pitch 0.5–2.0) | `block.note_block.pling`, `.bell`, `.chime`, `.hat`, `.bit`, `.harp`, `.bass`, `.basedrum`, `.snare`, `.iron_xylophone`, `.xylophone`, `.cow_bell`, `.didgeridoo`, `.banjo`, `.flute`, `.guitar`, `.imitate.zombie` |
| success / reward | `entity.experience_orb.pickup`, `entity.player.levelup`, `entity.villager.yes`, `entity.villager.trade`, `entity.wandering_trader.yes`, `ui.toast.challenge_complete`*, `block.amethyst_block.chime`, `block.amethyst_block.resonate`, `entity.allay.item_given`, `block.beacon.activate`, `entity.firework_rocket.blast`, `entity.firework_rocket.twinkle` |
| fail / deny | `entity.villager.no`, `item.bundle.insert_fail`, `entity.player.attack.nodamage`, `block.beacon.deactivate` |
| menus | `ui.toast.in`*, `ui.toast.out`*, `ui.loom.select_pattern`*, `ui.stonecutter.select_recipe`*, `ui.cartography_table.take_result`, `ui.hud.bubble_pop`, `item.book.page_turn`, `block.chest.open`, `block.chest.close`, `block.ender_chest.open`, `block.barrel.open`, `item.bundle.insert`, `item.bundle.remove_one`, `item.armor.equip_generic` |
| misc | `entity.enderman.teleport`, `entity.arrow.hit_player`, `block.anvil.use`, `block.anvil.land`, `entity.item.pickup`, `entity.item.break`, `block.spawner.place`, `block.spawner.break`, `block.trial_spawner.spawn_mob`, `block.vault.open_shutter`, `block.copper_bulb.turn_on`, `block.respawn_anchor.charge`, `entity.wither.spawn`, `entity.ender_dragon.growl`, `entity.player.burp`, `block.bell.use`, `entity.zombie_villager.converted`, `entity.illusioner.cast_spell`, `item.trident.return`, `block.portal.trigger`, `entity.generic.explode`, `block.sculk_sensor.clicking`, `entity.player.hurt`, `entity.lightning_bolt.thunder` |

All `ui.*` events in 26.2: `ui.button.click`, `ui.cartography_table.take_result`, `ui.hud.bubble_pop`, `ui.loom.select_pattern`, `ui.loom.take_result`, `ui.stonecutter.select_recipe`, `ui.stonecutter.take_result`, `ui.toast.challenge_complete`, `ui.toast.in`, `ui.toast.out`.

### 3.5 Hover and click events (Adventure 5.2; **`ClickEvent` is generic now**) [C]
`net.kyori.adventure.text.event.HoverEvent<V>`:
```java
static HoverEvent<Component> showText(ComponentLike | Component text)
static HoverEvent<ShowItem> showItem(Key item, int count) / showItem(Keyed item, int count)
static HoverEvent<ShowItem> showItem(Keyed item, int count, Map<Key, ? extends DataComponentValue> dataComponents)
static HoverEvent<ShowItem> showItem(Key|Keyed, int, @Nullable BinaryTagHolder nbt)        // [OBS] (pre-1.20.5 NBT)
static HoverEvent<ShowEntity> showEntity(Key|Keyed type, UUID id[, @Nullable Component name])
static HoverEvent<String> showAchievement(String)                                          // [OBS]
HoverEvent.ShowItem: item(), count(), Map<Key,DataComponentValue> dataComponents(), dataComponentsAs(Class)
```
Easiest correct `show_item`: `component.hoverEvent(itemStack)`. `ItemStack` is a `HoverEventSource<ShowItem>`, so `itemStack.asHoverEvent()` works too. Paper fills in the data components [RT].

`net.kyori.adventure.text.event.ClickEvent<T extends ClickEvent.Payload>`:
```java
static ClickEvent<Payload.Text> openUrl(String | URL)
static ClickEvent<Payload.Text> openFile(String)
static ClickEvent<Payload.Text> runCommand(String)            // include the leading "/"
static ClickEvent<Payload.Text> suggestCommand(String)
static ClickEvent<Payload.Int>  changePage(int)
static ClickEvent<Payload.Text> copyToClipboard(String)
static ClickEvent<?> callback(ClickCallback<Audience> fn)
static ClickEvent<?> callback(ClickCallback<Audience> fn, ClickCallback.Options options)
static ClickEvent<?> callback(ClickCallback<Audience> fn, Consumer<ClickCallback.Options.Builder> options)
static ClickEvent<Payload.Dialog> showDialog(DialogLike dialog)
static ClickEvent<Payload.Custom> custom(Key key)
static ClickEvent<Payload.Custom> custom(Key key, @Nullable BinaryTagHolder nbt)
static <T extends Payload> ClickEvent<T> clickEvent(Action<T> action, T payload)
Action<T> action(); Payload payload();
```
- **`ClickCallback<T extends Audience>`:**
  - `void accept(T)`, `filter(Predicate<T>[, otherwise])`, `requiringPermission(String[, otherwise])`
  - `static widen(ClickCallback<N>, Class<N>[, otherwise])`
  - `DEFAULT_LIFETIME = Duration.ofHours(12)`, `UNLIMITED_USES = -1`
  - The default options are **1 use**, 12 h [SRC].
  - `Options.builder().uses(int).lifetime(TemporalAmount)`
- **Custom click payloads** arrive as `io.papermc.paper.event.player.PlayerCustomClickEvent` (`getIdentifier()`, `@Nullable BinaryTagHolder getTag()`, `@Nullable DialogResponseView getDialogResponseView()`, `getCommonConnection()`). Validate them: see `runtime.md` §9.

### 3.6 Dialogs
`io.papermc.paper.dialog.Dialog extends Keyed, DialogLike`:
- `static Dialog create(Consumer<RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder>>)`
- built-ins `Dialog.CUSTOM_OPTIONS`, `QUICK_ACTIONS`, `SERVER_LINKS`
- `getKey()`/`key()` [DFR since 1.21.8]

Show a dialog with `audience.showDialog(dialog)` or `ClickEvent.showDialog(dialog)`; close it with `audience.closeDialog()`. Registration and the bootstrap path are in `runtime.md` §3; the toolkit is `net.siftvanilla.siftcore.ui.dialog`.

### 3.7 Tab list
- **Header and footer:** `Player#sendPlayerListHeaderAndFooter(Component, Component)` (Audience). Getters: `@Nullable Component playerListHeader()`, `playerListFooter()`.
- **Name and order:** `void playerListName(@Nullable Component)`, `Component playerListName()`, `int getPlayerListOrder()`, `void setPlayerListOrder(int)`.
- **Display name:** `displayName(@Nullable Component)` / `Component displayName()`.
- **Deprecated:** the `String` variants (`setPlayerListName`, `setPlayerListHeaderFooter`, …) are [DEP].

### 3.8 Server links [RT]
- **`org.bukkit.ServerLinks`:**
  - `@Nullable ServerLink getLink(Type)`, `List<ServerLink> getLinks()`
  - `ServerLink setLink(Type, URI)`, `ServerLink addLink(Type, URI)`, `ServerLink addLink(Component displayName, URI)`, `addLink(String, URI)` [DEP]
  - `boolean removeLink(ServerLink)`, `ServerLinks copy()`
- **`ServerLinks.ServerLink`:** `@Nullable Type getType()` (`null` for custom-named links [RT]), `Component displayName()`, `URI getUrl()`, `getDisplayName()` [DEP].
- **`ServerLinks.Type`:** `REPORT_BUG`, `COMMUNITY_GUIDELINES`, `SUPPORT`, `STATUS`, `FEEDBACK`, `COMMUNITY`, `WEBSITE`, `FORUMS`, `NEWS`, `ANNOUNCEMENTS`.
- **`Bukkit.getServerLinks()`:** the global, mutable set that is sent to every joining client [RT].
- **`org.bukkit.event.player.PlayerLinksSendEvent`:**
  - `ServerLinks getLinks()` is a per-connection copy: mutate it to customise links per player [SRC].
  - `PlayerCommonConnection getConnection()` is a `PlayerConfigurationConnection` (`getProfile()`, `getAudience()`) [RT].
  - Fired on the global region thread during configuration [RT].
- **Live updates:** `Player#sendLinks(ServerLinks)` or `PlayerCommonConnection#sendLinks(ServerLinks)`.

---

## 4. Scoreboard [C][RT]
Structural changes are **global-region-thread only**: objectives, display slots, teams, prefix/suffix/colour, number formats. `Player#setScoreboard` runs on the player's region thread. See `runtime.md` §7 for the per-player sidebar recipe.

- **`ScoreboardManager`:** `getMainScoreboard()`, `getNewScoreboard()` [global thread].
- **`Scoreboard`:**
  - `Objective registerNewObjective(String name, Criteria criteria, @Nullable Component displayName)`
  - `… (String, Criteria, @Nullable Component, RenderType)`; `String`-criteria overloads are [DEP]
  - `@Nullable Objective getObjective(String | DisplaySlot)`, `clearSlot(DisplaySlot)`
  - `Team registerNewTeam(String)`, `@Nullable Team getTeam(String)`, `getEntryTeam(String)`, `getPlayerTeam(OfflinePlayer)`
  - `resetScores(String)`, `getEntries()`
- **`Objective`:**
  - `setDisplaySlot(@Nullable DisplaySlot)`, `displayName(@Nullable Component)`
  - `Score getScore(String entry)`, `getScoreFor(Entity)`
  - `@Nullable NumberFormat numberFormat()` / `numberFormat(@Nullable NumberFormat)`
  - `setRenderType(RenderType)`, `setAutoUpdateDisplay(boolean)`, `unregister()`
- **`Score`:** `setScore(int)`, `getScore()`, `isScoreSet()`, `resetScore()`, `@Nullable Component customName()` / `customName(@Nullable Component)`, `numberFormat(@Nullable NumberFormat)`, `setTriggerable(boolean)`.
- **`io.papermc.paper.scoreboard.numbers.NumberFormat`:** `static NumberFormat blank()`, `static StyledFormat noStyle()`, `styled(Style)`, `styled(StyleBuilderApplicable...)`, `static FixedFormat fixed(ComponentLike)`. A sidebar with `blank()` and `Score#customName` worked [RT].
- **`Criteria`** constants:
  - `DUMMY` ("dummy"), `TRIGGER`, `DEATH_COUNT`, `PLAYER_KILL_COUNT`, `TOTAL_KILL_COUNT`, `HEALTH`, `FOOD`, `AIR`, `ARMOR`, `XP`, `LEVEL`
  - `TEAM_KILL_*`, `KILLED_BY_TEAM_*`
  - `static Criteria statistic(Statistic[, Material | EntityType])`, `create(String)`
- **`DisplaySlot`:** `PLAYER_LIST`, `SIDEBAR`, `BELOW_NAME`, `SIDEBAR_TEAM_<COLOR>`.
- **`RenderType`:** `INTEGER`, `HEARTS`.
- **`Team`** (also a `ForwardingAudience`):
  - `prefix(@Nullable Component)` / `prefix()`, `suffix(..)`, `color(@Nullable NamedTextColor)` / `TextColor color()` / `hasColor()`, `displayName(..)`
  - `addEntry(String)`, `addEntries(..)`, `addEntity(Entity)`, `removeEntry(String)`, `hasEntry(String)`
  - `setOption(Team.Option, Team.OptionStatus)`; `Option`: `NAME_TAG_VISIBILITY`, `DEATH_MESSAGE_VISIBILITY`, `COLLISION_RULE`; `OptionStatus`: `ALWAYS`, `NEVER`, `FOR_OTHER_TEAMS`, `FOR_OWN_TEAM`
  - `setAllowFriendlyFire(boolean)`, `setCanSeeFriendlyInvisibles(boolean)`, `unregister()`
  - `String` prefix/suffix and `ChatColor` variants are [DEP]

---

## 5. Entities and world

### 5.1 Teleporting [RT][SRC]
```java
default CompletableFuture<Boolean> Entity#teleportAsync(Location loc)
default CompletableFuture<Boolean> Entity#teleportAsync(Location loc, PlayerTeleportEvent.TeleportCause cause)
default CompletableFuture<Boolean> Entity#teleportAsync(Location loc, io.papermc.paper.entity.TeleportFlag... flags)
CompletableFuture<Boolean> Entity#teleportAsync(Location loc, TeleportCause cause, TeleportFlag... flags)
// Entity#teleport(..) (sync, all overloads) throws UnsupportedOperationException on Canvas (runtime.md).
```
- **`TeleportCause` values:**
  - `ENDER_PEARL`, `COMMAND`, `PLUGIN`, `NETHER_PORTAL`, `END_PORTAL`, `SPECTATE`, `END_GATEWAY`
  - `CONSUMABLE_EFFECT`, `DISMOUNT`, `EXIT_BED`, `UNKNOWN`
  - `CHORUS_FRUIT` is a [DFR] alias of `CONSUMABLE_EFFECT`
- **`TeleportFlag`:**
  - `TeleportFlag.Relative`: `VELOCITY_X`, `VELOCITY_Y`, `VELOCITY_Z`, `VELOCITY_ROTATION`; the aliases `X`/`Y`/`Z`/`YAW`/`PITCH` are [DFR].
  - `TeleportFlag.EntityState` is [DFR since 1.21.10].
- **Observed:** `teleportAsync(target, COMMAND)` completed `true`, and the callback ran on the player's region thread.
- **`PlayerTeleportEvent` (`getCause()`, `getRelativeTeleportationFlags()`, `getFrom()`, `getTo()`, `setTo(..)`) did NOT fire** for it, nor for a thrown ender pearl. In the decompiled server, `new PlayerTeleportEvent` only appears in `ServerGamePacketListenerImpl#teleport`, reached via `EXIT_BED`, internal `ServerPlayer#teleportTo` and ride rotation sync [SRC]. Its subclass `PlayerPortalEvent` is still built by `CraftEventFactory.handlePortalEvents` from `NetherPortalBlock`/`EndPortalBlock` destination lookup [SRC]; portals were not tested at runtime.
- **What Canvas fires instead** [RT] (all have a static `getHandlerList()`, so they can be registered reflectively, see §6.3):
  - `io.canvasmc.canvas.event.EntityTeleportAsyncEvent extends EntityEvent implements Cancellable`:
    - `getFrom()`, `getTo()`, `setTo(Location)`, `TeleportCause getCause()`
    - `EntityTeleportAsyncEvent.TeleportType getType()`: `SAME_REGION`, `CROSS_REGION`, `CROSS_WORLD`
    - It fired for both the plugin teleport (`COMMAND`) and the pearl (`ENDER_PEARL`), on the region thread.
    - It is only constructed when it has listeners [SRC].
  - `EntityPostTeleportAsyncEvent`: `getFrom()`, `getTo()`, `getCause()`, `getType()`; not cancellable.
  - `EntityPortalAsyncEvent`: cancellable; `getFrom()`/`getTo()`/`setTo(World)`, `getPortalType()`.
  - `EntityPostPortalAsyncEvent`.
- **Ender pearls** [RT]:
  - The thrower takes 5 damage with `damager=ENDER_PEARL`, `cause=PROJECTILE`, `type=minecraft:ender_pearl`, and `Projectile#getShooter()` = the player.
  - To block pearls in combat, cancel `com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent` (`getProjectile()`, `getItemStack()`, `setShouldConsume(boolean)`) or `org.bukkit.event.entity.ProjectileLaunchEvent`. `ProjectileLaunchEvent` fired for `launchProjectile(EnderPearl.class)` and for API-spawned arrows [RT].

### 5.2 Chunks
- **Future overloads:** `CompletableFuture<Chunk> World#getChunkAtAsync(int x, int z)`, `(int,int,boolean gen)`, `(int,int,boolean gen,boolean urgent)`, `(Location)`, `(Location,boolean)`, `(Block)`, `(Block,boolean)`.
- **Urgent:** `getChunkAtAsyncUrgently(Location | Block | int x,int z)`, plus `(Location|Block, boolean gen)`.
- **Callback overloads:** `getChunkAtAsync(int,int, Consumer<? super Chunk>)`, `(int,int,boolean gen, Consumer)`, `(int,int,boolean gen, boolean urgent, Consumer)`, `(Location|Block[, boolean], Consumer)`. The `ChunkLoadCallback` overloads are [DEP].
- **Observed:** futures for (64,64), (−64,−64) and spawn completed **on the region thread that owns that chunk** (`isOwnedByCurrentRegion==true`, `isGlobalTickThread==false`) [RT].
- **Other:** `World#isChunkLoaded(int,int)`, `refreshChunk(int,int)`, `Location#isChunkLoaded()`, `Location#isGenerated()`.

### 5.3 Heights [RT]
- **Highest block:**
  - `default Block World#getHighestBlockAt(int x, int z[, HeightMap])` and `(Location[, HeightMap])`
  - `int RegionAccessor#getHighestBlockYAt(int x, int z[, HeightMap])` and `(Location[, HeightMap])`
- **`HeightMap`:** `MOTION_BLOCKING`, `MOTION_BLOCKING_NO_LEAVES`, `OCEAN_FLOOR`, `OCEAN_FLOOR_WG`, `WORLD_SURFACE`, `WORLD_SURFACE_WG`.
- **World height** (`WorldInfo`): `int getMinHeight()` = −64, `int getMaxHeight()` = 320 (exclusive top). `World#getLogicalHeight()` = 384, `getSeaLevel()` = 63 (overworld).
- **Location helpers:** `Location#toHighestLocation()` and `(HeightMap)`.

### 5.4 World border [RT]
- **Getters:** `@Nullable World getWorld()`, `double getSize()`, `Location getCenter()`, `double getMaxSize()`, `double getMaxCenterCoordinate()`, `reset()`.
- **Size and centre:**
  - `void setSize(double)`
  - **`void changeSize(double newSize, long ticks)`**
  - `setSize(double, long seconds)` [DFR since 1.21.11] and `setSize(double, TimeUnit, long)` [DFR]
  - `void setCenter(double x, double z)`, `setCenter(Location)`
- **Damage:** `getDamageBuffer()`, `setDamageBuffer(double)`, `getDamageAmount()`, `setDamageAmount(double)`.
- **Warnings:** `int getWarningTimeTicks()`, `setWarningTimeTicks(int)`, `getWarningDistance()`, `setWarningDistance(int)`. `getWarningTime()` / `setWarningTime(int seconds)` are [DFR since 1.21.11].
- **`boolean isInside(Location)`**.
- **Defaults:** size 5.9999968E7, warnTicks 300, warnDist 5, buffer 5.0, damage 0.2.
- **Transitions:** after `setSize(2000)` then `changeSize(1000, 100)`, `getSize()` immediately reads 2000.0 (the shrink is in progress).
- **Per-player borders:**
  - `Bukkit.createWorldBorder()` returns a virtual border (`getWorld()==null`).
  - `Player#setWorldBorder(@Nullable WorldBorder)` / `getWorldBorder()`.
  - Event: `io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent` has `getDurationTicks()`; `getDuration()` is [DFR].
- **Threading:** `setSize` from a region thread did not throw [RT]. It is world-global state, so call it from the global region scheduler.

### 5.5 Game rules: `org.bukkit.GameRules` (26.2 registry; snake_case) [RT]
- **API:**
  - `<T> boolean World#setGameRule(GameRule<T> rule, T value)`: **global region thread only**. From a region thread it threw `IllegalStateException: Cannot modify server settings off of the global region`; from the global thread it returned `true`.
  - `<T> T World#getGameRuleValue(GameRule<T>)`
  - `String[] World#getGameRules()`: 58 snake_case names.
  - `boolean isGameRule(String)`: `"keep_inventory"` and `"minecraft:keep_inventory"` → true, `"keepInventory"` → **false**.
  - `getGameRuleDefault(GameRule)` is [DEP since 26.1.2]. `getGameRuleValue(String)` and `setGameRuleValue(String,String)` are [DFR].
- **`org.bukkit.GameRule<T>`** (`abstract class`, `Keyed`, `FeatureDependant`): `getKey()`, `Class<T> getType()`, `T getDefaultValue()`, `translationKey()`. `getName()`, `getByName(String)` and `values()` are [DFR since 1.21.11].
- **Enumeration:** `RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE)` has 59 entries; every one has a `GameRules` constant.
- **Experimental rule:** `max_minecart_speed` is [EXP] and `@MinecraftExperimental(MINECART_IMPROVEMENTS)`. `getGameRuleValue` on it throws `IllegalArgumentException: Tried to access invalid game rule` unless the experiment is enabled; check `world.isEnabled(rule)` first.

| `GameRules.` constant | key | type | default | legacy `GameRule.` constant [DFR] |
|---|---|---|---|---|
| ADVANCE_TIME | advance_time | Boolean | true | DO_DAYLIGHT_CYCLE |
| ADVANCE_WEATHER | advance_weather | Boolean | true | DO_WEATHER_CYCLE |
| ALLOW_ENTERING_NETHER_USING_PORTALS | allow_entering_nether_using_portals | Boolean | true | same name |
| BLOCK_DROPS | block_drops | Boolean | true | DO_TILE_DROPS |
| BLOCK_EXPLOSION_DROP_DECAY | block_explosion_drop_decay | Boolean | true | same |
| COMMAND_BLOCK_OUTPUT | command_block_output | Boolean | true | same |
| COMMAND_BLOCKS_WORK | command_blocks_work | Boolean | true | COMMAND_BLOCKS_ENABLED |
| DROWNING_DAMAGE | drowning_damage | Boolean | true | same |
| ELYTRA_MOVEMENT_CHECK | elytra_movement_check | Boolean | true | DISABLE_ELYTRA_MOVEMENT_CHECK (inverted) |
| ENDER_PEARLS_VANISH_ON_DEATH | ender_pearls_vanish_on_death | Boolean | true | same |
| ENTITY_DROPS | entity_drops | Boolean | true | DO_ENTITY_DROPS |
| FALL_DAMAGE | fall_damage | Boolean | true | same |
| FIRE_DAMAGE | fire_damage | Boolean | true | same |
| FIRE_SPREAD_RADIUS_AROUND_PLAYER | fire_spread_radius_around_player | Integer | 128 | DO_FIRE_TICK (bridged, Boolean: true↔128, false↔0); ALLOW_FIRE_TICKS_AWAY_FROM_PLAYER (true↔−1) |
| FORGIVE_DEAD_PLAYERS | forgive_dead_players | Boolean | true | same |
| FREEZE_DAMAGE | freeze_damage | Boolean | true | same |
| GLOBAL_SOUND_EVENTS | global_sound_events | Boolean | true | same |
| IMMEDIATE_RESPAWN | immediate_respawn | Boolean | false | DO_IMMEDIATE_RESPAWN |
| KEEP_INVENTORY | keep_inventory | Boolean | false | KEEP_INVENTORY (same object [RT]) |
| LAVA_SOURCE_CONVERSION | lava_source_conversion | Boolean | false | same |
| LIMITED_CRAFTING | limited_crafting | Boolean | false | DO_LIMITED_CRAFTING |
| LOCATOR_BAR | locator_bar | Boolean | true | same |
| LOG_ADMIN_COMMANDS | log_admin_commands | Boolean | true | same |
| MAX_BLOCK_MODIFICATIONS | max_block_modifications | Integer | 32768 | COMMAND_MODIFICATION_BLOCK_LIMIT |
| MAX_COMMAND_FORKS | max_command_forks | Integer | 65536 | MAX_COMMAND_FORK_COUNT |
| MAX_COMMAND_SEQUENCE_LENGTH | max_command_sequence_length | Integer | 65536 | MAX_COMMAND_CHAIN_LENGTH |
| MAX_ENTITY_CRAMMING | max_entity_cramming | Integer | 24 | same |
| MAX_MINECART_SPEED [EXP] | max_minecart_speed | Integer | 8 | MINECART_MAX_SPEED |
| MAX_SNOW_ACCUMULATION_HEIGHT | max_snow_accumulation_height | Integer | 1 | SNOW_ACCUMULATION_HEIGHT |
| MOB_DROPS | mob_drops | Boolean | true | DO_MOB_LOOT |
| MOB_EXPLOSION_DROP_DECAY | mob_explosion_drop_decay | Boolean | true | same |
| MOB_GRIEFING | mob_griefing | Boolean | true | same |
| NATURAL_HEALTH_REGENERATION | natural_health_regeneration | Boolean | true | NATURAL_REGENERATION |
| PLAYER_MOVEMENT_CHECK | player_movement_check | Boolean | true | DISABLE_PLAYER_MOVEMENT_CHECK (inverted) |
| PLAYERS_NETHER_PORTAL_CREATIVE_DELAY | players_nether_portal_creative_delay | Integer | 0 | same |
| PLAYERS_NETHER_PORTAL_DEFAULT_DELAY | players_nether_portal_default_delay | Integer | 80 | same |
| PLAYERS_SLEEPING_PERCENTAGE | players_sleeping_percentage | Integer | 100 | same |
| PROJECTILES_CAN_BREAK_BLOCKS | projectiles_can_break_blocks | Boolean | true | same |
| PVP | pvp | Boolean | true | same |
| RAIDS | raids | Boolean | true | DISABLE_RAIDS (inverted) |
| RANDOM_TICK_SPEED | random_tick_speed | Integer | 3 | same |
| REDUCED_DEBUG_INFO | reduced_debug_info | Boolean | false | same |
| RESPAWN_RADIUS | respawn_radius | Integer | 10 | SPAWN_RADIUS |
| SEND_COMMAND_FEEDBACK | send_command_feedback | Boolean | true | same |
| SHOW_ADVANCEMENT_MESSAGES | show_advancement_messages | Boolean | true | ANNOUNCE_ADVANCEMENTS |
| SHOW_DEATH_MESSAGES | show_death_messages | Boolean | true | same |
| SPAWN_MOBS | spawn_mobs | Boolean | true | DO_MOB_SPAWNING |
| SPAWN_MONSTERS | spawn_monsters | Boolean | true | same |
| SPAWN_PATROLS | spawn_patrols | Boolean | true | DO_PATROL_SPAWNING |
| SPAWN_PHANTOMS | spawn_phantoms | Boolean | true | DO_INSOMNIA |
| SPAWN_WANDERING_TRADERS | spawn_wandering_traders | Boolean | true | DO_TRADER_SPAWNING |
| SPAWN_WARDENS | spawn_wardens | Boolean | true | DO_WARDEN_SPAWNING |
| SPAWNER_BLOCKS_WORK | spawner_blocks_work | Boolean | true | SPAWNER_BLOCKS_ENABLED |
| SPECTATORS_GENERATE_CHUNKS | spectators_generate_chunks | Boolean | true | same |
| SPREAD_VINES | spread_vines | Boolean | true | DO_VINES_SPREAD |
| TNT_EXPLODES | tnt_explodes | Boolean | true | same |
| TNT_EXPLOSION_DROP_DECAY | tnt_explosion_drop_decay | Boolean | false | same |
| UNIVERSAL_ANGER | universal_anger | Boolean | false | same |
| WATER_SOURCE_CONVERSION | water_source_conversion | Boolean | true | same |

### 5.6 `Location`, `Vector`, `Position`
- **`org.bukkit.Location`** (`implements io.papermc.paper.math.FinePosition`):
  - world: `World getWorld()` (throws if the world is unloaded), `isWorldLoaded()`, `getChunk()`, `getBlock()`
  - coordinates: `getBlockX/Y/Z()`, `x()/y()/z()`, `set(double,double,double)`
  - arithmetic: `add(..)`, `subtract(..)`, `multiply(double)`, `zero()`, `distance(Location)`, `distanceSquared(Location)` (same-world only), `length()`
  - conversion: `toBlockLocation()`, `toCenterLocation()`, `toHighestLocation([HeightMap])`, `toVector()`, `clone()`
  - rotation: `setDirection(Vector)`, `getDirection()`, `setRotation(float yaw, float pitch)`, `addRotation(..)`, `static normalizeYaw(float)`, `normalizePitch(float)`
  - chunk state: `isChunkLoaded()`, `isGenerated()`
  - `checkFinite()`, `isFinite()`
- **`org.bukkit.util.Vector`:**
  - arithmetic: `add`, `subtract`, `multiply(int|double|float|Vector)`, `divide`, `dot`, `crossProduct`, `normalize`, `length`, `lengthSquared`, `distance`, `distanceSquared`, `midpoint`
  - rotation: `rotateAroundX/Y/Z(double)`, `rotateAroundAxis(Vector,double)`
  - containment: `isInAABB(Vector min, Vector max)`, `isInSphere(Vector, double)`
  - conversion: `toLocation(World[, yaw, pitch])`, `toBlockVector()`, `toVector3d()`, `static fromJOML(..)`
- **`io.papermc.paper.math.Position`** is [EXP]: `Position.block(int,int,int)`, `Position.fine(double,double,double)`, `toLocation(World)`.

### 5.7 Entity scheduler [RT]
`io.papermc.paper.threadedregions.scheduler.EntityScheduler` (`entity.getScheduler()`):
```java
boolean execute(Plugin plugin, Runnable run, @Nullable Runnable retired, long delay)
@Nullable ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task, @Nullable Runnable retired)
@Nullable ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, @Nullable Runnable retired, long delayTicks)   // delayTicks >= 1
@Nullable ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, @Nullable Runnable retired, long initialDelayTicks, long periodTicks)
```
- **`runDelayed(.., 0)`** throws `IllegalArgumentException: Delay ticks may not be <= 0` [RT]. Use `run(..)` for "next tick".
- **`ScheduledTask`:**
  - `getOwningPlugin()`, `isRepeatingTask()`, `cancel()` → `CancelledState`, `getExecutionState()`, `isCancelled()`
  - `CancelledState`: `CANCELLED_BY_CALLER`, `CANCELLED_ALREADY`, `RUNNING`, `ALREADY_EXECUTED`, `NEXT_RUNS_CANCELLED`, `NEXT_RUNS_CANCELLED_ALREADY`
  - `ExecutionState`: `IDLE`, `RUNNING`, `FINISHED`, `CANCELLED`, `CANCELLED_RUNNING`
- **Other schedulers:** see `runtime.md` §5.1.

### 5.8 Player and offline-player info [RT]
- **Ping:** `int Player#getPing()`. Over loopback it was 0–6 at join and 7–26 ms a second later; it updates with keepalives.
- **Address:** `@Nullable InetSocketAddress Player#getAddress()` → `/127.0.0.1:49892`. `PlayerConnection` also has `getClientAddress()`, `getVirtualHost()`, `getHAProxyAddress()`.
- **Client info:** `@Nullable String getClientBrandName()` (`null` for the bot), `Locale locale()`, `getClientViewDistance()`.
- **Flight:** `getAllowFlight()`, `setAllowFlight(boolean)`, `isFlying()`, `setFlying(boolean)`, `setFlyingFallDamage(TriState)`.
- **Statistics** (on `OfflinePlayer`):
  - `int getStatistic(Statistic)`, `setStatistic(Statistic,int)`, `incrementStatistic(Statistic[,int])`, `decrementStatistic(..)`
  - Overloads with `Material` / `EntityType` exist for typed statistics.
  - **`PLAY_ONE_MINUTE` counts ticks** (key `minecraft:play_one_minute`; 277 ticks ≈ 14 s after join).
  - `DEATHS` and the killer's `PLAYER_KILLS` incremented after a PvP kill [RT].
- **`Statistic`** (enum, `Keyed`):
  - Untyped stats useful for SiftCore: `PLAY_ONE_MINUTE`, `PLAYER_KILLS`, `DEATHS`, `MOB_KILLS`, `DAMAGE_DEALT`, `DAMAGE_TAKEN`, `DAMAGE_ABSORBED`, `DAMAGE_RESISTED`, `DAMAGE_BLOCKED_BY_SHIELD`, `TIME_SINCE_DEATH`, `TIME_SINCE_REST`, `TOTAL_WORLD_TIME`, `LEAVE_GAME`, `JUMP`, `WALK_ONE_CM`, `SPRINT_ONE_CM`, `FLY_ONE_CM`, `AVIATE_ONE_CM`, `SWIM_ONE_CM`, `FISH_CAUGHT`, `ANIMALS_BRED`, `TRADED_WITH_VILLAGER`, `RAID_WIN`, `SLEEP_IN_BED`, `ITEM_ENCHANTED`, `SNEAK_TIME`.
  - Typed stats: `MINE_BLOCK` (BLOCK), `BREAK_ITEM`, `CRAFT_ITEM`, `DROP`, `PICKUP`, `USE_ITEM` (ITEM), `KILL_ENTITY`, `ENTITY_KILLED_BY` (ENTITY).
  - Helpers: `getType()`, `isSubstatistic()`, `isBlock()`.
- **Offline lookups:**
  - `@Nullable OfflinePlayer Bukkit.getOfflinePlayerIfCached(String name)` only checks the user cache: unknown → `null`; after a join (and after quit) → `CraftOfflinePlayer` [RT].
  - `Bukkit.getOfflinePlayer(UUID)` makes no web request.
  - `getOfflinePlayer(String)` "may involve a blocking web request" (javadoc): never call it on a tick thread.
  - `OfflinePlayer` getters: `getFirstPlayed()`, `getLastLogin()`, `getLastSeen()`, `hasPlayedBefore()`, `@Nullable getPlayer()`, `getPlayerProfile()`, `PersistentDataContainerView getPersistentDataContainer()`.
- **Respawn point:** `Player#setRespawnLocation(@Nullable Location, boolean force)`; `default setRespawnLocation(@Nullable Location)`; `getRespawnLocation()`.

### 5.9 Unspawned entities [RT]
- **Creating:** `<T extends Entity> T RegionAccessor#createEntity(Location, Class<T>)` creates an entity **without adding it to the world**: `isValid()==false`, `isInWorld()==false`.
  - The call itself succeeded on the global thread.
  - Using the entity there (e.g. for loot) threw `IllegalStateException: Thread failed main thread check: Accessing entity state off owning region's thread`.
  - So create and use it on the region thread that owns the location.
- **Spawning:** `EntitySnapshot#createEntity(World | Location)`, `Entity#spawnAt(Location[, SpawnReason])`, `RegionAccessor#spawn(Location, Class<T>[, Consumer<? super T>][, SpawnReason])`.

---

## 6. Combat and player events

### 6.1 Damage [RT]
- **`EntityDamageEvent`:**
  - `getDamage()`, `setDamage(double)`, `final double getFinalDamage()`
  - `DamageCause getCause()`, `DamageSource getDamageSource()`, `isCancelled()`, `setCancelled(boolean)`
  - the `DamageModifier` enum is [DEP] (since 1.12), and the per-modifier `getDamage(DamageModifier)`/`setDamage(DamageModifier, double)` go with it
  - `DamageCause.HOT_FLOOR` and `CAMPFIRE` are [DEP since 26.2]: use `CONTACT`, which exposes the block. `DRAGON_BREATH` is [DEP].
- **`EntityDamageByEntityEvent`:** `Entity getDamager()` (the *direct* entity), `boolean isCritical()`, `DamageSource getDamageSource()`.
- **`org.bukkit.damage.DamageSource`:**
  - `DamageType getDamageType()`
  - `@Nullable Entity getCausingEntity()`, `@Nullable Entity getDirectEntity()`
  - `@Nullable Location getDamageLocation()`, `getSourceLocation()`
  - `boolean isIndirect()`, `getFoodExhaustion()`, `scalesWithDifficulty()`
  - `Pointers getDamageContext()` [EXP]
  - `static Builder builder(DamageType)` → `withCausingEntity(Entity)`, `withDirectEntity(Entity)`, `withDamageLocation(Location)`, `build()`
- **Applying damage:** `Damageable#damage(double)`, `damage(double, @Nullable Entity source)`, `damage(double, DamageSource)`.

| scenario [RT] | `getDamager()` | `getCause()` | damage type | causing | direct | indirect |
|---|---|---|---|---|---|---|
| arrow shot by zombie (DamageSource builder) | ARROW | PROJECTILE | `minecraft:arrow` | ZOMBIE | ARROW | true |
| `player.damage(1, zombie)` | ZOMBIE | ENTITY_ATTACK | `minecraft:mob_attack` | ZOMBIE | ZOMBIE | false |
| arrow shot by player B | ARROW | PROJECTILE | `minecraft:arrow` | PLAYER (B) | ARROW | true |
| ender pearl landing | ENDER_PEARL | PROJECTILE | `minecraft:ender_pearl` | null | null | false |

Attacker resolution for combat tagging (verified pieces):
```java
Entity attacker = e.getDamageSource().getCausingEntity();          // preferred: shooter/owner for projectiles
if (attacker == null && e.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity s) attacker = s;
if (attacker == null) attacker = e.getDamager();
```
- **Projectiles:** `@Nullable ProjectileSource Projectile#getShooter()`, `setShooter(..)`, `@Nullable UUID getOwnerUniqueId()`, `hasLeftShooter()`.
- **Invulnerability:** damage arriving within about 60 ticks after respawn, or within the hurt-invulnerability window, is ignored **without** an `EntityDamageEvent` [RT].

### 6.2 Death [RT]
- **`PlayerDeathEvent extends EntityDeathEvent`:**
  - message: `@Nullable Component deathMessage()` / `deathMessage(@Nullable Component)`; `deathScreenMessageOverride(@Nullable Component)`
  - entity: `Player getEntity()` / `getPlayer()`
  - inventory: `getKeepInventory()` / `setKeepInventory(boolean)`, `getItemsToKeep()`, `getDrops()` (from `EntityDeathEvent`, mutable)
  - experience: `getKeepLevel()` / `setKeepLevel(boolean)`, `setNewExp(int)`, `setNewLevel(int)`, `setNewTotalExp(int)`, `shouldDropExperience()` / `setShouldDropExperience(boolean)`, `getDroppedExp()` / `setDroppedExp(int)`
  - `setShowDeathMessages(boolean)`
  - `getDeathMessage()` / `setDeathMessage(String)` are [DEP]
- **`EntityDeathEvent`** also has: `getDamageSource()`, `setReviveHealth(double)`, `setCancelled(boolean)` (revives), and death-sound setters.
- **Killer:** `@Nullable Player LivingEntity#getKiller()` / `setKiller(@Nullable Player)`. It is set when the killing damage source's **causing** entity is a player.
  - Player B kills A with an arrow (DamageSource causing = B, direct = arrow) → `getKiller()=B`, message "ApiBot was shot by ApiBot2", B's `PLAYER_KILLS` +1 [RT].
  - Mob or `setHealth(0)` deaths → `getKiller()==null`. `setHealth(0)` reports damage type `minecraft:generic`, but the message comes from the combat tracker, e.g. "was slain by Zombie" [RT].
- **Thread:** the event runs on the player's region thread (`runtime.md` §8).

### 6.3 Respawn on Canvas [RT][SRC]
- **Not fired:** `org.bukkit.event.player.PlayerRespawnEvent` (`setRespawnLocation`, `getRespawnReason`, `isBedSpawn`, `isAnchorSpawn`, `isMissingRespawnBlock`) and `com.destroystokyo.paper.event.player.PlayerPostRespawnEvent` were **not** fired across 4 deaths and respawns.
- **What is fired:** Canvas `ServerPlayer#respawn` fires `io.canvasmc.canvas.event.PlayerRespawnAsyncEvent` and `PlayerPostRespawnAsyncEvent`. Both `extends io.papermc.paper.event.player.AbstractRespawnEvent`.
- **Portable way to choose the respawn spot (verified):** in `PlayerDeathEvent` call `e.getPlayer().setRespawnLocation(target, true)`. The player respawned exactly at `target` (y adjusted by +0.1).
- **Canvas-only way (verified):**
  - `PlayerRespawnAsyncEvent#setRespawnLocation(Location)` worked; the player ended up at the new location.
  - It runs on neither the player's region thread nor the global thread (`isOwnedByCurrentRegion(player)==false`, `isGlobalTickThread()==false`).
  - `PlayerPostRespawnAsyncEvent` runs on the player's region thread after placement.
  - SiftCore bans `io.canvasmc.*` imports, so register these by name:
```java
@SuppressWarnings("unchecked")
static void listenIfPresent(Plugin plugin, String className, EventPriority prio, Consumer<Event> handler) {
  try {
    Class<? extends Event> cls = (Class<? extends Event>) Class.forName(className);
    Bukkit.getPluginManager().registerEvent(cls, new Listener() {}, prio,
        (listener, event) -> { if (cls.isInstance(event)) handler.accept(event); }, plugin, false);
  } catch (ClassNotFoundException absent) { /* not Canvas */ }
}
// listenIfPresent(p, "io.canvasmc.canvas.event.PlayerRespawnAsyncEvent", NORMAL, ev -> {
//   AbstractRespawnEvent re = (AbstractRespawnEvent) ev;          // getPlayer(), getRespawnLocation(), getRespawnReason()
//   ev.getClass().getMethod("setRespawnLocation", Location.class).invoke(ev, target); });
```
- **`PlayerRespawnEvent.RespawnReason`:** `DEATH`, `END_PORTAL`, `PLUGIN`. **`RespawnFlag`:** `BED_SPAWN`, `ANCHOR_SPAWN`, `END_PORTAL` (`getRespawnFlags()` is [DEP]).

### 6.4 Other events [RT]
| event | members (exact) | observed |
|---|---|---|
| `org.bukkit.event.player.PlayerCommandPreprocessEvent` | `String getMessage()`, `void setMessage(String)`, `setCancelled(boolean)`; `getRecipients()` [DFR], `setPlayer(Player)` [DFR] | message keeps the leading `/` ("/apiprobe-nonexistent hello world"); fires for unknown commands too; cancelling suppresses "Unknown command" |
| `org.bukkit.event.player.PlayerQuitEvent` | `@Nullable Component quitMessage()`, `quitMessage(@Nullable Component)`, `QuitReason getReason()`; String variants [DEP] | `QuitReason`: `DISCONNECTED`, `KICKED`, `TIMED_OUT`, `ERRONEOUS_STATE`; bot quit → `DISCONNECTED` |
| `org.bukkit.event.entity.EntityToggleGlideEvent` | `boolean isGliding()` (new state), `isCancelled()`, `setCancelled(boolean)`, `getEntity()` | — |
| `org.bukkit.event.entity.ProjectileLaunchEvent extends EntitySpawnEvent` | `Projectile getEntity()`, `setCancelled(boolean)` | fired for `launchProjectile(EnderPearl.class)` and for `world.spawn(.., Arrow.class, a -> a.setShooter(z))` |
| `com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent` | `getProjectile()`, `getItemStack()`, `shouldConsume()`, `setShouldConsume(boolean)`, `setCancelled(boolean)` | player item throws only [SRC] |
| `org.bukkit.event.player.PlayerJoinEvent` | — | `getPing()` may still be 0 at join; first join `hasPlayedBefore()==false` |

---

## 7. Spawners

### 7.1 Block state API [RT]
- **Interfaces:** `org.bukkit.block.CreatureSpawner extends TileState, org.bukkit.spawner.Spawner`. `Spawner extends org.bukkit.spawner.BaseSpawner`.
- **`BaseSpawner`:**
  - type: `@Nullable EntityType getSpawnedType()`, `setSpawnedType(@Nullable EntityType)`
  - timing: `getDelay()`, `setDelay(int)`
  - ranges: `getRequiredPlayerRange()`, `setRequiredPlayerRange(int)`, `getSpawnRange()`, `setSpawnRange(int)`
  - spawned entity: `@Nullable EntitySnapshot getSpawnedEntity()`, `setSpawnedEntity(@Nullable EntitySnapshot)`, `setSpawnedEntity(SpawnerEntry)`
  - potentials: `addPotentialSpawn(EntitySnapshot, int weight, @Nullable SpawnRule)`, `addPotentialSpawn(SpawnerEntry)`, `setPotentialSpawns(Collection<SpawnerEntry>)`, `List<SpawnerEntry> getPotentialSpawns()`
- **`Spawner`:**
  - delays: `getMinSpawnDelay()`, `setMinSpawnDelay(int)`, `getMaxSpawnDelay()`, `setMaxSpawnDelay(int)`
  - counts: `getSpawnCount()`, `setSpawnCount(int)`, `getMaxNearbyEntities()`, `setMaxNearbyEntities(int)`
  - `boolean isActivated()`, `void resetTimer()`, `setSpawnedItem(ItemStack)`
- **`CreatureSpawner`:** `setCreatureTypeByName(String)` / `getCreatureTypeName()` are [DEP].
- **Defaults of a freshly placed spawner on Canvas 962:** `type=null`, `delay=20`, `minSpawnDelay=200`, `maxSpawnDelay=800`, `spawnCount=4`, `maxNearbyEntities=6`, `requiredPlayerRange=16`, `spawnRange=4`, `isActivated=false`, `potentialSpawns=0`.
- **Validation:** `setMinSpawnDelay(500)` with max 400 throws `IllegalArgumentException: Minimum Spawn Delay must be less than or equal to Maximum Spawn Delay`. Set the max first.
- **Writing back:** changes plus the `TileState` PDC persisted after `state.update(true, false)` (re-read: `type=ZOMBIE`, values and PDC kept). `setSpawnedType(null)` empties the spawner.
- **Canvas defaults:** `config/canvas-worlds.yml` `blocks.spawner.*` can change defaults for new spawners (`runtime.md` §8).
- **Folia note:** Canvas `isNearPlayer` uses `canvas$hasNearbyAlivePlayerThatAffectsSpawningForSpawner`, and a negative `requiredPlayerRange` does not disable the check (`runtime.md` §8).

### 7.2 Spawner items and placement [RT]
```java
ItemStack sp = ItemStack.of(Material.SPAWNER);                        // ItemType.Typed<BlockStateMeta> SPAWNER
sp.editMeta(BlockStateMeta.class, m -> {
  CreatureSpawner s = (CreatureSpawner) m.getBlockState();            // fresh item: getSpawnedType()==null
  s.setSpawnedType(EntityType.BLAZE); s.setSpawnCount(3);
  m.setBlockState(s);                                                  // writes minecraft:block_entity_data
});
// BlockStateMeta: boolean hasBlockState(), void clearBlockState(), BlockState getBlockState(), void setBlockState(BlockState)
```
- **The item itself:** after `editMeta`, `getDataTypes()` gains `block_entity_data`. The vanilla tooltip reads `[Monster Spawner, Blaze]`. Reading back gives `BLAZE`, count 3.
- **Placement by a non-op survival player** (real client, `BlockPlaceEvent` fired):

  | listener behaviour | resulting spawner |
  |---|---|
  | none (item carries BlockStateMeta ZOMBIE) | **`type=null`**: the data was ignored, and `getState()` at event time already showed `null` |
  | `@EventHandler(priority = HIGHEST, ignoreCancelled = true)`: read the type from the item PDC, `CreatureSpawner cs = (CreatureSpawner) e.getBlockPlaced().getState(); cs.setSpawnedType(t); cs.update(true, false);` | **`SKELETON`** (`update` returned true) |
  | in-event `Bukkit.getRegionScheduler().run(plugin, block.getLocation(), t -> { …same… })` (next tick) | **`CREEPER`** |
- **Why:** `BlockItem.updateCustomBlockEntityTag` refuses `block_entity_data` for `BlockEntityTypes.OP_ONLY_CUSTOM_DATA` = {command block, lectern, sign, hanging sign, **mob spawner**, **trial spawner**}. Only `player.canUseGameMasterBlocks()` (op), or creative with `minecraft.nbt.place`, can apply it [SRC].
- **Store your own data in the item PDC** (`editPersistentDataContainer`), not in `BlockStateMeta`. Copy it into the block's `TileState` PDC on place, and back out on break.
- **`BlockPlaceEvent`:**
  - `getItemInHand()` (not yet decremented at event time), `getBlockPlaced()`, `getBlockReplacedState()`, `getBlockAgainst()`, `getPlayer()`, `getHand()`
  - `canBuild()` / `setBuild(boolean)`, `setCancelled(boolean)`
- **`BlockBreakEvent extends BlockExpEvent`:** `getPlayer()`, `setDropItems(boolean)`, `isDropItems()`, `getExpToDrop()`, `setExpToDrop(int)`, `setCancelled(boolean)`.
- **Thread:** both events run on the player's region thread, which also owns the block (`runtime.md` §8).

### 7.3 Spawn events
- **`org.bukkit.event.entity.SpawnerSpawnEvent extends EntitySpawnEvent`:**
  - `@Nullable CreatureSpawner getSpawner()` (`null` for minecart spawners)
  - `getEntity()`, `getLocation()`, `setCancelled(boolean)`
  - Fires on the spawner chunk's region thread (`runtime.md` §8).
- **`com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent extends PreCreatureSpawnEvent`:**
  - `Location getSpawnerLocation()`, plus `getSpawnLocation()`, `EntityType getType()`, `getReason()`
  - `shouldAbortSpawn()`, `setShouldAbortSpawn(boolean)`, `setCancelled(boolean)`
  - Fires **once per spawn attempt**, i.e. up to `spawnCount` times per cycle, before the entity is created [SRC: `BaseSpawner`].
  - Cancelling it skips that attempt and marks the cycle as having spawned, so the spawner delay is reset.
  - Cancelling *and* calling `setShouldAbortSpawn(true)` also stops the remaining attempts of the cycle [SRC].
  - That gives one callback per cycle with nothing spawned, which suits "virtual" stacked spawners that generate drops themselves (recommendation; not exercised at runtime).

### 7.4 Spawner block PDC, and Canvas live block states [RT][SRC]
- `TileState#getPersistentDataContainer()` returns a mutable `PersistentDataContainer`. Data persists after `update(true, false)`, e.g. `INTEGER 7` read back after `update`.
- **Canvas difference:** `Block#getState()` uses `config/canvas-server.yml` `tile-entity-snapshot-creation` (default and local value: `false`) [SRC]. With `false`:
  - `getState()` on a block entity returns a **live** state, not a copy (`TileState#isSnapshot()==false` [RT]), so setters write straight into the real block entity.
  - Call `update(..)` to resync clients.
  - Use `Block#getState(true)` when you need a detached copy, e.g. before mutating speculatively.
  - Item-side `BlockStateMeta#getBlockState()` is unaffected.

### 7.5 Computing mob loot without spawning [RT]
- **Loot API:**
  - `org.bukkit.loot.LootTable extends Keyed`: `Collection<ItemStack> populateLoot(@Nullable Random, LootContext)`, `void fillInventory(Inventory, @Nullable Random, LootContext)`.
  - `LootContext.Builder(Location)` → `.lootedEntity(@Nullable Entity)`, `.killer(@Nullable HumanEntity)`, `.luck(float)`, `.build()`. `lootingModifier(int)` and `getLootingModifier()` are [DFR since 1.21]: looting now comes from the killer's equipped weapon enchantments.
- **Finding a table:**
  - `Mob#getLootTable()` (`Lootable`) on an unspawned entity, e.g. `minecraft:entities/zombie`.
  - `Bukkit.getLootTable(NamespacedKey.minecraft("entities/" + type.getKey().getKey()))` works for every vanilla mob tested, incl. `breeze` and `bogged`.
  - The `org.bukkit.loot.LootTables` enum is **incomplete**: no breeze, bogged, armadillo, camel, … Don't rely on it.
- **Failure modes:**
  - `zt.populateLoot(r, new LootContext.Builder(loc).build())` → `IllegalArgumentException: Missing required parameter: <parameter minecraft:this_entity>`. `CraftLootTable` passes the table's own param set to `LootParams.Builder#create`, and the entity set requires `this_entity`, `origin` and `damage_source` [SRC].
  - With `lootedEntity`, `CraftLootTable` sets `DAMAGE_SOURCE = generic`.
  - With `killer(HumanEntity)` it sets `ATTACKING_ENTITY`, `DAMAGE_SOURCE = playerAttack`, `LAST_DAMAGE_PLAYER`, and `TOOL = killer.getUseItem()` [SRC]. The killer must be a real (online) `HumanEntity`.
- **Results** (unspawned entity, 200 runs unless noted, no killer):

| mob | total drops | mob | total drops |
|---|---|---|---|
| zombie (2000 runs) | rotten_flesh 1986 (≈0.99/kill; **36 µs/call**) | skeleton | arrow 194, bone 187 |
| creeper | gunpowder 219 | spider | string 207 (spider_eye needs killer) |
| cow | beef 408, leather 203 | pig | porkchop 416 |
| iron_golem | iron_ingot 802, poppy 202 | enderman | ender_pearl 95 |
| slime | slime_ball 226 | magma_cube | **none** (size-dependent / killer) |
| witch | redstone 1184, stick 113, … | zombified_piglin | gold_nugget 110, rotten_flesh 99 (ingot needs killer) |
| chicken | chicken 200, feather 216 | sheep | mutton 305, white_wool 200 |
| guardian | prismarine_shard 190, crystals 80, cod 74 | rabbit | rabbit 200, rabbit_hide 101 |
| squid | ink_sac 402 | wither_skeleton | bone 210, coal 68 (skull needs killer) |
| bogged | arrow 196, bone 195 | breeze | **none** (breeze_rod needs killer) |
| **blaze, no killer (500 runs)** | **none** | **blaze, `.killer(onlinePlayer)`** | **blaze_rod 93 / 200** |

Recipe (run inside `RegionScheduler.run(plugin, loc, ..)` or on the owning entity's scheduler):
```java
Entity probe = world.createEntity(loc, type.getEntityClass());   // not spawned; reuse per type, it is cheap
LootTable table = probe instanceof Mob m ? m.getLootTable() : Bukkit.getLootTable(NamespacedKey.minecraft("entities/" + type.getKey().getKey()));
LootContext ctx = new LootContext.Builder(loc).lootedEntity(probe).killer(ownerIfOnline /* nullable */).build();
Collection<ItemStack> drops = table.populateLoot(random, ctx);
```
For drops conditioned on `killed_by_player` while the owner is offline, give each such mob a static fallback table, or accept that those drops are missing.

---

## 8. Permissions, services, lifecycle [RT][C]
- **Permissions via `PluginManager`** (it extends `io.papermc.paper.plugin.PermissionManager`):
  - `void addPermission(Permission)` throws `IllegalArgumentException: The permission <name> is already defined!` on duplicates [RT].
  - `void removePermission(Permission | String)`, `@Nullable Permission getPermission(String)`, `Set<Permission> getPermissions()`, `recalculatePermissionDefaults(Permission)`.
  - `overridePermissionManager(Plugin, PermissionManager)` is [EXP].
  - A permission added at runtime with `PermissionDefault.TRUE` is honoured **immediately** for an already-online player (`hasPermission → true`, `isPermissionSet → false`). `PermissionDefault.OP` → `false` for a non-op.
- **`org.bukkit.permissions.Permission`:**
  - constructors: `(String name)`, `(String, String description)`, `(String, PermissionDefault)`, `(String, String, PermissionDefault)`, `(String, Map<String,Boolean> children)`, `(String, String, Map)`, `(String, PermissionDefault, Map)`, `(String, String, PermissionDefault, Map)`
  - `getChildren()`, `setDefault(PermissionDefault)`, `addParent(String, boolean)`, `recalculatePermissibles()`
- **`PermissionDefault`:** `TRUE`, `FALSE`, `OP`, `NOT_OP`; `static getByName(String)`.
- **`ServicesManager`:**
  - `<T> void register(Class<T> service, T provider, Plugin plugin, ServicePriority priority)`
  - `void unregister(Class<?> service, Object provider)`, `unregister(Object provider)`, `unregisterAll(Plugin)`
  - `<T> @Nullable T load(Class<T>)`, `<T> @Nullable RegisteredServiceProvider<T> getRegistration(Class<T>)`, `getRegistrations(..)`, `isProvidedFor(Class<T>)`, `getKnownServices()`
  - `ServicePriority` constants are PascalCase: `Lowest`, `Low`, `Normal`, `High`, `Highest`.
  - Verified: register → `load()` returned the provider; after `unregister`, `load()` → `null`.
- **Lifecycle:**
  - `io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager<Plugin> Plugin#getLifecycleManager()` (final in `JavaPlugin`); `BootstrapContext#getLifecycleManager()` returns `LifecycleEventManager<BootstrapContext>`.
  - `default <E extends LifecycleEvent> void registerEventHandler(LifecycleEventType<? super O, ? extends E, ?> type, LifecycleEventHandler<? super E> handler)` and `registerEventHandler(LifecycleEventHandlerConfiguration<? super O>)`.
  - `LifecycleEvents.COMMANDS` is a `LifecycleEventType.Prioritizable<LifecycleEventOwner, ReloadableRegistrarEvent<Commands>>`.
  - `LifecycleEvents.TAGS` and `DATAPACK_DISCOVERY` are [EXP].
```java
getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
  Commands commands = event.registrar();        // io.papermc.paper.command.brigadier.Commands
  commands.register(Commands.literal("x").build(), "description", List.of("alias"));   // [C]
});
```
COMMANDS fires once on Canvas because there is no reload (`runtime.md` §0/§4).

---

## 9. Misc
- **Workbench, ender chest and other block menus:** use `MenuType.CRAFTING.create(player, title)` and the other types in §2.2. The `HumanEntity#openWorkbench(..)` family is [DEP since 1.21.4] but works. Open the ender chest with `player.openInventory(player.getEnderChest())`. All verified [RT].
- **Flight:** `Player#setAllowFlight(boolean)`, `setFlying(boolean)` [C].
- **Hover name / display name for chat:** `ItemStack#displayName()` (with hover), `effectiveName()`, `component.hoverEvent(itemStack)` (§1.4, §3.5).
- **Profiles:**
  - `Bukkit.createProfile(UUID)`, `createProfile(String)`, `createProfile(@Nullable UUID, @Nullable String)`, `createProfileExact(..)`.
  - `com.destroystokyo.paper.profile.PlayerProfile`:
    - properties: `getProperties()`, `setProperty(ProfileProperty)`, `removeProperty(String)`, `hasTextures()`, `getTextures()` / `setTextures(PlayerTextures)`
    - completion: `isComplete()`, `complete()` / `complete(boolean textures[, boolean onlineMode])` (blocking I/O), `completeFromCache(..)`
    - `CompletableFuture<PlayerProfile> update()` (async)
    - `setName` / `setId` are [DFR]
  - `ProfileProperty(String name, String value[, @Nullable String signature])`.
- **Head items:** prefer `DataComponentTypes.PROFILE` with `ResolvableProfile` (§1.3), or `SkullMeta#setPlayerProfile`.
- **Server MOTD:** `Bukkit.motd()` / `motd(Component)`. `Bukkit.broadcast(Component[, String permission])`.

---

## 10. Reproduce
- **Probe plugin** (scratchpad, probe-only, uses NMS for the bot):
  - sources: `research/D-scripts/probe/src/dev/siftvanilla/apiprobe/{ApiProbe,StaticChecks,PlayerChecks,Run4,Bot}.java`
  - build: `research/D-scripts/probe/build.sh`
  - server control: `research/D-scripts/srv.sh start|wait|stop` on `scratchpad/srv-D` (port 25614). `srv-D` was deleted after the runs; recreate it with `cp -r scratchpad/local scratchpad/srv-D`, then set `server-port=25614`
  - Run 4/5 used `JVMEXTRA=-Dapiprobe.mode=run4`.
  - `srv-D/config/paper-global.yml` `packet-limiter.all-packets.max-packet-rate` was raised to 1e6. Paper's limiter also applies to the in-JVM bot's *client* connection, and chunk sending tripped it.
- **Signature compile check:** `research/D-scripts/sigcheck/src/sig/SigCheck.java`, compiled against `paper-api-26.2.build.132-stable.jar` + Adventure 5.2 + brigadier only.
- **Grep-able signature index** (annotations kept, bodies stripped):
  - `research/D/out/outline-all.txt` (all 1911 paper-api sources)
  - `research/D/out/adv-outline.txt` (Adventure 5.2.0 sources from Maven Central)
  - generator: `research/D-scripts/outline.py` (`python3 -I outline.py FILE.java...`)
- **Client sounds:** `research/D/dl-sounds/sounds.json` (26.2 asset index 32, sha1 `9ac006d5537ed0fa4a7bcd1eccfc505155847686`), checked with `research/D-scripts/checksounds.py` and `subtitles.py`.
- **Server source:** decompile at `research/runtime/decomp` (Track A, Vineflower 1.12.0). Files read: `CraftLootTable`, `BlockItem`, `BlockEntityTypes`, `ItemStack#useOn`, `CraftMenus`, `CraftAbstractLocationInventoryViewBuilder`, `CraftEntity#teleportAsync`, `Entity#teleportAsync`, `ServerPlayer#respawn`, `ServerGamePacketListenerImpl#teleport`, `PlayerList#remove`, `CraftItemStack#serializeAsBytes`, `MCUtil#serializeTagToBytes`, `ServerConfigurationPacketListenerImpl`.
