# Sprites & text rendering (Track B)

Verified 2026-10-07 against:

- Minecraft **26.2** `client.jar` (sha1 `2dc72797acbc1b63fc16a11c4ac393605f453754`), which is unobfuscated and was decompiled with Vineflower 1.12.0.
- **Canvas 26.2-962** (`Implementing API version 26.2.build.962-stable`).
- **Adventure 5.2.0**. The bundled jars are byte-identical to Maven Central.

How each fact was checked:

- **(D)** Read in decompiled client or server code.
- **(R)** Run empirically on JDK 25.0.4.1: MiniMessage probes, a probe plugin on a Canvas 962 test server, and the client's own atlas code run headless.
- **(P)** Pixels of the actual PNGs were parsed.

## 0. Rules for SiftCore (TL;DR)

1. **Always name the atlas.** With no atlas argument the default is `minecraft:blocks`. Since 1.21.11 that atlas only holds `block/…` plus 9 entity textures (7 × `entity/conduit/*`, `entity/bell/bell_body`, `entity/enchantment/enchanting_table_book`). So `<sprite:item/emerald>` renders as a **magenta square on 26.2**; write `<sprite:items:item/emerald>` instead. (D)(R)
2. **Never write `minecraft:` unquoted inside `<sprite:…>`.** `<sprite:minecraft:gui:hud/heart/full>` parses as atlas `minecraft:minecraft` and sprite `minecraft:gui`. Use `<sprite:gui:hud/heart/full>` or `<sprite:'minecraft:gui':'minecraft:hud/heart/full'>`. (R)
3. **Validate every sprite at plugin enable** against `src/main/resources/atlas-index.txt`. Nothing downstream validates for you: not MiniMessage (even in strict mode), not Paper, not the server. (R)
   - Bad key syntax shows up as literal `<sprite:…>` text.
   - An unknown sprite in a known atlas renders as the magenta/black `missingno`.
   - An unknown atlas renders as a hollow 5×8 "missing glyph" box.
4. **Sprites are multiplied by the text colour.** (D) The shader computes `texColor * vertexColor`.
   - Colourful icons: force `color(WHITE)`.
   - Grey/white icons: let them inherit the colour. This is how to get a monochrome theme.
   - Item lore defaults to dark-purple italic, and sign/book text to black. **Always set a colour** there.
5. **A sprite is always drawn as an 8×8 GUI-pixel square, stretched to fit.** Its advance is 8 px (9 if bold), with no 1-px gap after it.
   - Put a space after an icon.
   - Count 8 px per icon when measuring or centring text.
   - `<u>`/`<st>` lines run through icons. (D)
6. **Untrusted text goes only through `Placeholder.unparsed(…)`** or `Placeholder.component(…, Component.text(raw))`. (R) Do not use any of these:
   - String concatenation.
   - `Placeholder.parsed`.
   - `escapeTags`. It leaves unknown tags and backslashes live, and `deserialize` throws on `§` codes.
7. **`ObjectComponent#fallback` is silently dropped** by Canvas/Paper's codec (R). In the server-list MOTD, player heads show as `[Name head]`.
8. **Prefer crisp sizes.** 8×8 sprites (particles, map decorations) are pixel-exact at every GUI scale, and 16×16 sprites (items, blocks) at even scales. 9×9, 18×18, 10×8 and 20×20 work but are always slightly resampled.

## 1. How the 26.2 client draws an object component (D)

| Piece | Exact code / value |
|---|---|
| Sprite contents | `net.minecraft.network.chat.contents.objects.AtlasSprite(Identifier atlas, Identifier sprite)`. `DEFAULT_ATLAS = AtlasIds.BLOCKS` (`minecraft:blocks`). Codec: `atlas` optional (default blocks), `sprite` required. |
| Head contents | `…objects.PlayerSprite(ResolvableProfile player, boolean hat)`. `hat` defaults to `true`. |
| Type ids / JSON | `ObjectInfos`: `"atlas"` and `"player"`. Vanilla encodes `{"atlas":"minecraft:gui","sprite":"minecraft:hud/heart/full","fallback":…}`, omitting `atlas` when it is blocks. On decode, the discriminators `"type":"object"` and `"object":"atlas"\|"player"` are optional (R, client `ComponentSerialization` run headless). |
| Component | `net.minecraft.network.chat.contents.ObjectContents(ObjectInfo contents, Optional<Component> fallback)`. It renders one placeholder code point, U+FFFC, whose style font is `FontDescription.AtlasSprite`/`PlayerSprite`. |
| Plain-text form | `defaultFallback()` gives `[hud/heart/full@gui]` (`@atlas` is omitted for blocks; `minecraft:` is omitted). Heads give `[Notch head]` or `[unknown player head]`. Narration, `getString()` and Adventure's `PlainTextComponentSerializer` all use this form (R). |
| Known atlases | `net.minecraft.client.resources.model.sprite.AtlasManager.KNOWN_ATLASES` (13 ids, the same as `net.minecraft.data.AtlasIds`). Resource packs cannot add atlases. `FontManager` registers an `AtlasGlyphProvider` for each one. |
| Missing cases | `FontManager#getSpriteFont`: an unknown atlas uses `missingFontSet` (`SpecialGlyphs.MISSING`, a hollow 5×8 box). `AtlasGlyphProvider`: an unknown sprite uses the atlas `missingSprite()` (magenta/black). |
| Geometry | `AtlasGlyphProvider.GLYPH_INFO = GlyphInfo.simple(8.0F)`. `PlainTextRenderable` has width = height = ascent = 8 and `top = y - 1`, so the quad spans y-1…y+7 (bottom on the baseline). The whole sprite frame is UV-mapped into it. Bold adds `boldOffset 1` to the advance only. Obfuscation is a no-op. |
| Colour | Render type `RenderTypes.text(atlas)`. Shader `core/text.fsh` computes `color = texture * vertexColor`, where vertexColor is the text colour; texels with alpha < 0.1 are discarded. |
| Shadow | `Font…getShadowColor`: uses the style's `shadow_color` if set, otherwise `drawShadow ? textColor×0.25 : 0`. A dark copy of the sprite is drawn offset by 1 px. `<!shadow>` / `shadow_color:0` turns it off. |
| Animation | `TextureAtlas implements TickableTexture`, and `tick()` calls `cycleAnimationFrames()`. **Animated sprites animate inside text.** |
| MOTD | `ServerStatusPinger.DESCRIPTION_SANITIZE_CONTEXT` rejects `PlayerSprite`, which is replaced by its fallback (default `[Name head]`), and caps nesting depth at 16. Atlas sprites **are** allowed in the MOTD. No other caller of `withObjectInfoValidator` exists, so objects render in chat, action bar, title, boss bar, tab list, scoreboard, item name/lore, books, signs, dialogs, name tags and text displays. |

Only `blocks` is built with mipmaps (`AtlasConfig(…, createMipmaps)` is `true` for blocks only). The other atlases are sampled nearest-neighbour. At GUI scale *s* a glyph covers 8·s screen pixels.

- An 8×8 sprite is always integer-scaled.
- A 16×16 sprite is 1:1 at scale 2 and 2:1 at scale 4. At scale 1 every second texel is skipped, so 1-px outlines (for example the `container/slot/*` silhouettes) can vanish.
- 9×9 and 18×18 sprites are never integer-scaled. A few rows and columns get doubled or dropped; it is barely visible from scale 2 upwards.
- Block sprites at scale 1 may come from a mip level instead (unverified).

The `--preview` mode of the generator renders exactly this nearest-neighbour sampling.

## 2. Atlases in 26.2 (D)(R)

The default atlas is **`minecraft:blocks`**, both in vanilla (`AtlasSprite.DEFAULT_ATLAS`) and in Adventure (`SpriteObjectContents.DEFAULT_ATLAS = Key.key("minecraft:blocks")`).

| Atlas key | Sources (`assets/minecraft/atlases/*.json`) | Sprites | Sizes | Inline-icon value |
|---|---|---:|---|---|
| `minecraft:blocks` *(default)* | `directory block→block/`; `directory entity/conduit→entity/conduit/`; `single entity/bell/bell_body`; `single entity/enchantment/enchanting_table_book` | 1278 | 16×16 ×1230, 32×32 ×43 (signs, shelves…), 64×32, 32×16; **56 animated** | Opaque square block faces. A few work well (spawner, cobweb, target, vault). |
| `minecraft:items` | `directory item→item/`; `paletted_permutations` trims/items/{helmet,chestplate,leggings,boots}_trim × 16 palettes (separator `_`) | 860 | 16×16 ×853, 32×32 ×7 (`*_spear_in_hand`) | **Best source** of recognisable 16×16 icons. |
| `minecraft:gui` | `directory gui/sprites→""`; `directory mob_effect→mob_effect/` | 506 | 9×9 ×65, 18×18 ×63, 16×16 ×47, 20×20 ×29, plus large bars and widgets | HUD icons and status icons. Ids have **no** `gui/sprites/` prefix. |
| `minecraft:particles` | `directory particle→""` | 288 | **8×8 ×108**, 16×16 ×84, 32×32 ×53, 5×5, 3×3 | Glyph-native 8×8 shapes. |
| `minecraft:map_decorations` | `directory map/decorations→""` | 35 | **all 8×8** | Outlined map markers that tint well. |
| `minecraft:paintings` | `directory painting→""` | 52 | 16–64 px, mostly non-square | No |
| `minecraft:celestials` | `directory environment/celestial→""` | 10 | 32×32, 64×64 | No: opaque black backgrounds. |
| `minecraft:chests` | `directory entity/chest→entity/chest/` | 22 | 64×64 entity UV sheets | No |
| `minecraft:shulker_boxes` | `directory entity/shulker→entity/shulker/` | 18 | 64×64 | No |
| `minecraft:banner_patterns` | `directory entity/banner→entity/banner/` | 44 | 64×64 | No |
| `minecraft:shield_patterns` | `directory entity/shield→entity/shield/` | 45 | 64×64 | No |
| `minecraft:decorated_pot` | `directory entity/decorated_pot→entity/decorated_pot/` | 25 | 16×16 (+32×32 base) | No: low-contrast terracotta tiles. |
| `minecraft:armor_trims` | `paletted_permutations` trims/entity/humanoid{,_leggings}/<18 patterns> × 16 palettes | 576 | 64×32 entity overlays | No. **Atlas JSON removed in 26.3.** |

Every atlas also contains `minecraft:missingno` (the magenta texture). It is excluded from the index.

Resolution rules (a port of `net.minecraft.client.renderer.texture.atlas.*`):

- Sources run in order against one `Map<Identifier, Loader>`; a later `add` replaces an earlier one.
- `directory`: lists every `textures/<source>/**.png` in every namespace. The id is `<ns>:<prefix><relative path without .png>`.
- `single`: `{resource, sprite?}`.
- `filter`: `{pattern:{namespace?,path?}}`; removes ids where both regexes fully match.
- `unstitch`: adds `regions[].sprite`.
- `paletted_permutations`: produces `<texture><separator><key>`.
- Sprite size is the animation frame size: `.png.mcmeta` `animation.width/height`, otherwise `min(w,h)` square.
- Asset-index textures (panorama, `realms/textures/gui/images`) fall under no atlas source, so `client.jar` alone defines the atlases.

### Version drift (R)

These diffs come from running the generator on 26.1.2, 26.2 and 26.3.

**≤ 1.21.10.** `blocks.json` also listed `item/`, `entity/decorated_pot/decorated_pot_side` and the trim item permutations. That is why older examples like `<sprite:item/diamond>` worked. **1.21.11** moved `item/` and the trim item permutations into a new `items.json`. That version also still had `signs.json` and `beds.json` atlases, which no longer exist in 26.2; signs and beds now live in `blocks`.

**26.1.2 → 26.2:**

- blocks: +159 / −2 (`block/purpur_pillar`, `block/quartz_pillar`)
- gui: +20 (`friends/*`, `pause_menu/*`)
- particles: +34
- items: +4

**26.2 → 26.3:**

- `armor_trims.json` deleted (−576).
- items: +37 / −2 (`item/filled_map_markings`, `item/light`)
- blocks: +31
- gui: +3
- map_decorations: +5
- particles: +12

All 91 ids in the curated set below (primaries, alternates and extras) exist in 26.2 **and** 26.3. All exist in 26.1.2 except `gui friends/friends`. Every one of the 73 sprite ids cited in this doc was re-checked with `gen_atlas_index.py check`. This matters only if ViaVersion or ViaBackwards is ever installed; sprites come from the **client's** jar.

## 3. Adventure 5.2.0

### Java API (exact signatures)

`net.kyori.adventure.text.Component`:

- `ObjectComponent.Builder object()` (4.25.0)
- `object(Consumer<? super ObjectComponent.Builder>)` (4.25.0)
- `object(ObjectContents)` (4.25.0)
- `object(ObjectContentsLike)` (**5.2.0**; accepts a `SkinSource`)

`net.kyori.adventure.text.ObjectComponent` is sealed and is a `ScopedComponent<ObjectComponent>`, so `.color()` and `.shadowColor()` return an `ObjectComponent`. Its members:

- `ObjectContents contents()`
- `ObjectComponent contents(ObjectContents)`
- `@Nullable Component fallback()` (5.0.0)
- `ObjectComponent fallback(@Nullable ComponentLike)` (5.0.0)
- `Builder#contents(ObjectContents)`
- `Builder#fallback(ComponentLike)`

`net.kyori.adventure.text.object.ObjectContents` is sealed; its permitted types are `SpriteObjectContents` and `PlayerHeadObjectContents`. Its factories:

- `static SpriteObjectContents sprite(Key atlas, Key sprite)`
- `static SpriteObjectContents sprite(Key sprite)` (uses the default atlas)
- `static PlayerHeadObjectContents.Builder playerHead()`
- `playerHead(String name)`
- `playerHead(UUID id)`
- `playerHead(PlayerHeadObjectContents.SkinSource)`

`SpriteObjectContents`:

- `Key DEFAULT_ATLAS = Key.key("minecraft:blocks")`
- `Key atlas()`
- `Key sprite()`

`PlayerHeadObjectContents`:

- Constants and static helpers: `DEFAULT_HAT = true`, `NAME_REGEX = "^[!-~]{0,16}$"`, `isValidName(String)`, `property(name, value[, signature])`.
- Accessors: `name()`, `id()`, `profileProperties()`, `hat()`, `texture()`.
- Builder methods: `name`, `id`, `profileProperty`, `profileProperties`, `skin(SkinSource)`, `hat(boolean)`, `texture(Key)`, `build()`.

Paper types that implement `PlayerHeadObjectContents.SkinSource` are `org.bukkit.OfflinePlayer` (so also `Player`), `com.destroystokyo.paper.profile.PlayerProfile` and `io.papermc.paper.datacomponent.item.ResolvableProfile`. That means `Component.object(player)` works.

`Key.key("gui")` gives `minecraft:gui`. Invalid characters (upper case, spaces) throw `InvalidKeyException`, so wrap config-driven keys.

### MiniMessage tags (`net.kyori.adventure.text.minimessage.tag.standard`)

| Tag | Syntax | Notes |
|---|---|---|
| `SpriteTag` | `<sprite:SPRITE>` or `<sprite:ATLAS:SPRITE>` | Self-closing inserting tag. Each argument goes through `Key.key()`; args are split on `:`, so **namespaces must be quoted**. A third argument is ignored. In `StandardTags.defaults()`; `StandardTags.sprite()`. |
| `SequentialHeadTag` | `<head>`, `<head:true\|false>` (hat), `<head:NAME[:hat]>`, `<head:UUID[:hat]>`, `<head:TEXTURE_PATH[:hat]>` (an argument containing `/`, e.g. `entity/player/wide/steve`) | `StandardTags.sequentialHead()`. The name must pass `PlayerHeadObjectContents.isValidName`: at most 16 chars, each in 33–125 (`!`…`}`). Otherwise the tag stays literal. |
| `ShadowColorTag` | `<shadow:COLOR[:ALPHA]>` (alpha defaults to 0.25), `<shadow:#RRGGBBAA>`, `<!shadow>` (none) | Still supported. `<shadow>` with no argument is **not valid** and stays literal text. |
| `DecorationTag` | `<!italic>` = `<italic:false>` = `<!i>` = `<i:false>` = `<em:false>` | All produce `"italic":false`, as do the other decorations. |

Verified parse results (R). JSON is shown in Adventure Gson form; the server-side vanilla form is in §4.

| Input | Result |
|---|---|
| `<sprite:gui:hud/heart/full>` | atlas `minecraft:gui`, sprite `minecraft:hud/heart/full` ✅ |
| `<sprite:block/stone>` | default atlas `minecraft:blocks` ✅ |
| `<sprite:item/emerald>` | atlas **blocks**, so magenta on 26.2 ❌ |
| `<sprite:items:item/emerald>` | atlas `minecraft:items` ✅ |
| `<sprite:minecraft:gui:hud/heart/full>` | atlas **`minecraft:minecraft`**, sprite `minecraft:gui` ❌ |
| `<sprite:minecraft:block/stone>` | atlas **`minecraft:minecraft`** ❌ |
| `<sprite:'minecraft:gui':'minecraft:hud/heart/full'>` | correct ✅ (this is also what `serialize()` emits) |
| `<sprite:gui:HUD/Heart>`, `<sprite>` | left as literal text (also with `strict(true)`) |
| `<sprite:gui:hud/heart/full>x</sprite>y` | sprite, then literal `x</sprite>y` (the tag is self-closing) |
| `<head:Notch>` | `{"hat":true,"player":"Notch"}` (the client resolves the profile) |
| `<head:069a79f4-…:false>` | `{"hat":false,"player":{"id":[int,int,int,int]}}` |

### Escaping untrusted text (R)

I ran 44 hostile inputs through each approach: tags, quotes, backslashes, `§` codes, `<pre>`, `<reset>`, `<newline>`, custom tags, `<click>`, `<hover>`, `<insert>`, `<sprite>` and `<head>`.

| Approach | Result |
|---|---|
| `mm.deserialize("<gray>Msg: <msg>", Placeholder.unparsed("msg", raw))` | **Exact literal for all 44 inputs.** No click, hover, insertion, sprite or head ever appears. **Use this.** `Placeholder.component("msg", Component.text(raw))` behaves the same. |
| `"<gray>" + mm.escapeTags(raw) + "</gray>"` | **Not safe as a general rule.** (1) Only *known* tags are escaped, so `<player>` stays live and resolves if a `player` resolver is passed to `deserialize` (injection); `escapeTags(raw, sameResolvers)` fixes that one. (2) User backslashes are not escaped: `x\` escapes the template's `</gray>`, and `\<red>` loses its backslash. (3) See the `§` row. |
| `§` codes in the template string | `deserialize` **throws** `ParsingException: Legacy formatting codes have been detected in a MiniMessage string…`. The trigger is `TokenParser.parseString`, non-lenient: `§` followed by `[0-9a-fk-or]`, case-insensitive. This also happens after `escapeTags`, and for `Placeholder.parsed` values. `escapeTags` and `stripTags` themselves are lenient. |
| `Placeholder.parsed` | Unsafe: `<click:run_command:…>` was injected. |
| String concatenation | Unsafe: `</gray><click:…>` was injected. |

Presets for player-supplied formatting (`MiniMessage.miniMessage(MiniMessage.Preset.X)` or `MiniMessage.builder(Preset.X)`):

- `NON_INTERACTABLE` removes click, hover and insert (post-processor) but **keeps** `<sprite>` and `<head>`.
- `FORMATTED_TEXT` allows only colour, decorations, font, gradient, rainbow, transition, pride, shadow and newline, and removes non-text components.

For nicknames and chat colours, prefer an explicit allow-list built as `MiniMessage.builder().tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(TextDecoration.BOLD), StandardTags.decorations(TextDecoration.ITALIC), StandardTags.decorations(TextDecoration.UNDERLINED), StandardTags.decorations(TextDecoration.STRIKETHROUGH)))`. `FORMATTED_TEXT` would also allow `<newline>`, `<font>` and `<obf>`. Plain `StandardTags.decorations()` includes `<obf>` too. Feed the player's string as the *template* to this restricted instance; never splice it into a trusted template. This was verified (R): `<red><b>`, `<#ff00ff>` and `<b>` apply. `<obf>`, `<newline>`, `<font>`, `<gradient>`, `<click>` and `<sprite>` stay literal. `§cBob` **throws** `ParsingException`, so reject `§` up front or catch the exception.

## 4. Server side on Canvas 962 (R, probe plugin)

`io.papermc.paper.adventure.PaperAdventure.asVanilla(Component)` converts sprites faithfully. For example, `<gray><sprite:items:item/emerald> 1,000` becomes vanilla `{"text":"","extra":[{"atlas":"minecraft:items","sprite":"minecraft:item/emerald"}," 1,000"],"color":"gray"}`. There is **no validation**: the bogus `minecraft:minecraft` atlas passes straight through.

`io.papermc.paper.adventure.AdventureCodecs.SPRITE_OBJECT_CODEC` encodes only `atlas` and `sprite`. So `Component.object(b -> b.contents(…).fallback(text("<3")))` arrives as a vanilla `ObjectContents` with `fallback=Optional.empty`. **The fallback is lost.** Vanilla itself does support `fallback`; only the Paper conversion drops it.

Canvas never calls `ResolutionContext.Builder#withObjectInfoValidator`, so the server strips no objects anywhere. The only stripping happens client-side, for player heads in the MOTD.

`Component.object(Bukkit.getOfflinePlayer(uuid))` produces `{"player":{"id":[…]}}`. For a never-seen offline player this contains the id only, and the client resolves the skin itself.

`<!shadow>` around a sprite becomes `"shadow_color":0` on the object, which disables the sprite's drop shadow.

Building components is pure and immutable, so it is safe from any Folia region thread.

## 5. `src/main/resources/atlas-index.txt`

- **Format:** one sprite per line, `<atlas key> <sprite id>`, for example `minecraft:gui hud/heart/full`. Sprite ids are paths in the `minecraft` namespace; all vanilla ids are. Lines are sorted, with no comments, no duplicates and no `missingno`. Every line matches `^minecraft:[a-z_]+ [a-z0-9/._-]+$`.
- **Size:** 3759 lines, sha1 `c962f0acbb5582dcc1fc7a81fb64e2471610f4f7`.
  - armor_trims 576, banner_patterns 44, blocks 1278, celestials 10, chests 22, decorated_pot 25, gui 506
  - items 860, map_decorations 35, paintings 52, particles 288, shield_patterns 45, shulker_boxes 18
- **Verification:**
  - The Python port's output is **byte-identical** to running the client's own `SpriteSource` implementations headless (`DirectoryLister`, `SingleFile`, `PalettedPermutations`, …) over `VanillaPackResources` (`AtlasDump.java`).
  - The PNG counts per source directory also match: 466+40 gui; 1269+7+2 blocks; 796+64 items.
  - All 63 animated textures have consistent frame sizes, so none is dropped at stitch time.
- **Suggested loader:** read lines into a `Set<String>` keyed by `atlas + ' ' + sprite`. Before use, normalise a `Key` atlas and sprite to that form, stripping a `minecraft:` sprite namespace.

## 6. Curated `Icons` registry

**Tint** column:

- **keep**: colourful sprite. Give the component `color(NamedTextColor.WHITE)` so the parent text colour doesn't darken it.
- **inherit**: white/grey sprite. Leave its colour unset so it takes the surrounding text colour; this is the monochrome theme.

Sizes are frame px, with the opaque bounding box in parentheses where it differs. None of these sprites is animated.

| Key | MiniMessage | Size | What it looks like | Tint |
|---|---|---|---|---|
| `money` | `<sprite:items:item/gold_ingot>` | 16×16 (16×12) | Gold ingot in perspective: yellow bar, orange edges, white glint. Fills the glyph width (≈8×6 at scale 1). *Mono alt:* `items:item/iron_ingot` (same bar in neutral grey). | keep |
| `shard` | `<sprite:items:item/amethyst_shard>` | 16×16 (12×13) | Purple crystal shard pointing top-right, pale highlight. *Alt:* `items:item/echo_shard` (dark teal), `gui:container/slot/amethyst_shard` (grey #555 outline; vanishes at scale 1). | keep |
| `kill` | `<sprite:items:item/iron_sword>` | 16×16 | Iron sword, blade to top-right: light-grey blade, black outline, brown grip. *Alt:* `items:item/diamond_sword`. | keep |
| `death` | `<sprite:particles:damage>` | 8×8 (7×7) | Black heart with dark-red rim and one white glint (damage-indicator particle). *Alt:* `gui:hud/heart/withered_full` 9×9 (wither heart). | keep |
| `streak` | `<sprite:particles:flame>` | 8×8 (4×8) | Slim flame: yellow core, orange/red tip. *Alt:* `particles:soul_fire_flame` (cyan), `particles:copper_fire_flame` (green). | keep |
| `kdr` | `<sprite:particles:critical_hit>` | 8×8 (7×7) | Pure-white 8-point "crit" spark, an X with short arms. *Alt:* `gui:mob_effect/strength` 18×18 (sword + spark; illegible below GUI scale 2). | inherit |
| `playtime` | `<sprite:items:item/clock_00>` | 16×16 (14×16) | Gold pocket-watch rim, dial showing the daytime sky. `clock_00…clock_63` are 64 **static** day-cycle frames, not `.mcmeta`-animated; `clock_32` shows the night/moon face. | keep |
| `mobs_killed` | `<sprite:items:item/bone>` | 16×16 (15×14) | Cream-white bone, diagonal. *Alt:* `items:item/rotten_flesh`, `items:item/zombie_spawn_egg`. | keep |
| `blocks_mined` | `<sprite:items:item/iron_pickaxe>` | 16×16 (13×13) | Iron pickaxe head top-right, brown diagonal handle. *Alt:* `gui:statistics/block_mined` 18×18 (same pickaxe; the Statistics screen "mined" header). | keep |
| `ping_5…ping_1`, `ping_unknown` | `<sprite:gui:icon/ping_5>` … `<sprite:gui:icon/ping_1>`, `<sprite:gui:icon/ping_unknown>` | 10×8 (10×7) | Tab-list signal bars. `ping_5` = 5 rising green bars; `ping_1` = 1 green bar + 4 dark; `unknown` = dark bars with a red X. Squeezed to 8 px wide. Exact copies exist as `gui:server_list/ping_1…5`. | keep |
| `online` | `<sprite:gui:icon/accessibility>` | 15×15 | White standing person, arms outstretched, grey shading. *Alt:* `gui:spectator/teleport_to_player` 16×16 (Steve face on a cyan glow), or `<head:UUID>`. | inherit |
| `team` | `<sprite:map_decorations:white_banner>` | 8×8 (6×8) | Narrow white banner under a black cross-bar, black outline. Tint it with the team colour. *Alt:* `gui:spectator/teleport_to_team` 16×16 (two heads); `gui:friends/friends` (26.2+). | inherit |
| `home` | `<sprite:map_decorations:plains_village>` | 8×8 | Orange-brown house (pitched roof, dark door), black outline: the map's village marker. | keep |
| `warning` | `<sprite:gui:icon/unseen_notification>` | 10×10 | Solid red square with a yellow "!". The dot of the "!" drops out at GUI scale 1. *Alt:* `particles:angry` 8×8 (grey storm cloud with yellow lightning bolt). | keep |
| `info` | `<sprite:gui:icon/info>` | 20×20 | Light-blue rounded tile, white border, white "i". Downsampled 2.5:1. | keep |
| `success` | `<sprite:gui:icon/checkmark>` | 9×8 | Green tick (#04a935/#04cb40) with dark-green shading. *Alt:* `gui:container/beacon/confirm` 18×18. | keep |
| `error` | `<sprite:map_decorations:red_x>` | 8×8 | Red X with black outline ("X marks the spot"). *Alt:* `gui:container/beacon/cancel` 18×18; `items:item/barrier` 16×16 (red no-entry ring). *Mono:* `map_decorations:target_x` (white outlined X). | keep |
| `bounty` | `<sprite:blocks:block/target_top>` | 16×16 | Target-block face: red/white concentric rings on hay; an opaque square tile. *Alt:* `map_decorations:target_point` 8×8 (red map pin pointing down). | keep |
| `crate` | `<sprite:blocks:block/vault_front_on>` | 16×16 | Trial Vault front: dark steel grille with a glowing orange band near the bottom (opaque). Pairs with `key`. *Alt:* `items:item/chest_minecart`. | keep |
| `key` | `<sprite:items:item/trial_key>` | 16×16 (8×16) | Upright copper Trial Key with a dark ring on top. *Alt:* `items:item/ominous_trial_key` (teal). | keep |
| `spawner` | `<sprite:blocks:block/spawner>` | 16×16 | Dark blue-black iron cage grid with see-through cells. | keep |
| `shop` | `<sprite:items:item/emerald>` | 16×16 (10×12) | Green emerald gem (villager trade currency). *Alt:* `items:item/bundle` (brown leather pouch). | keep |
| `auction` | `<sprite:items:item/bell>` | 16×16 (12×13) | Gold bell hanging from a bar. *Alt:* `items:item/name_tag` (tan tag on a white string; reads as a price tag). | keep |
| `order` | `<sprite:items:item/writable_book>` | 16×16 (16×14) | Book and Quill: brown/red cover, white pages, quill. *Alt:* `items:item/paper` (near-white sheet; inherit). | keep |
| `afk` | `<sprite:blocks:block/cobweb>` | 16×16 | Near-white cobweb strands on transparency ("gathering cobwebs"). Busy at GUI scale 1. *Alt:* `items:item/clock_32` (night clock). | inherit |
| `rank` | `<sprite:items:item/nether_star>` | 16×16 (13×13) | White 4-point star with a yellow core and cyan edges. *Mono alt:* `particles:glow` 8×8 (white 4-point twinkle). | keep |
| `health` | `<sprite:gui:hud/heart/full>` | 9×9 (7×7) | Bright-red HUD heart fill. It has **no outline**, because vanilla draws `hud/heart/container` underneath. *Alt:* `particles:heart` 8×8 (crisper red heart, darker shading). | keep |
| `hunger` | `<sprite:gui:hud/food_full>` | 9×9 (7×7) | HUD drumstick: brown-orange meat, pale bone tip, no outline (the outline lives in `hud/food_empty`). | keep |
| `armor` | `<sprite:gui:hud/armor_full>` | 9×9 | Grey chestplate with black outline and a light highlight. `hud/armor_empty` is the dark version. | keep |
| `experience` | `<sprite:items:item/experience_bottle>` | 16×16 (11×15) | Bottle o' Enchanting: green liquid, yellow-outlined glass. | keep |

UI glyph extras, all verified present and all 8×8 unless noted:

| Key | Sprite | Description | Tint |
|---|---|---|---|
| `bullet` | `map_decorations:player_off_map` | White disc (6×6), black outline. | inherit |
| `dot` | `map_decorations:player_off_limits` | Tiny white dot with outline (4×4 bbox). | inherit |
| `arrow_up` | `map_decorations:player` | White map-marker arrow pointing up, outlined. | inherit |
| `arrow_right` | `gui:container/villager/trade_arrow` (10×9) | Light-grey right arrow. | inherit |
| `cross` | `map_decorations:target_x` | White X, outlined. | inherit |
| `star` | `particles:glow` | White 4-point twinkle. | inherit |
| `spark` | `particles:critical_hit` | White crit spark. | inherit |
| `note` | `particles:note` | Grey music note. | inherit |
| `heart_small` | `particles:heart` | Red heart. | keep |
| `absorption` | `gui:hud/heart/absorbing_full` (9×9) | Golden heart. | keep |
| `air` | `gui:hud/air` (9×9) | Blue outlined bubble. | keep |
| `lock` | `gui:container/cartography_table/locked` (10×14) | White padlock; squashed, so it looks wide. | inherit |
| `search` | `gui:icon/search` (12×12) | Magnifier: cyan lens, orange handle. | keep |
| `badge_1…5`, `badge_more` | `gui:notification/1` … `/5`, `gui:notification/more` | Red disc with a pale digit; `more` is a plain red disc. | keep |
| `player_head` | `<head:UUID>` / `Component.object(player)` | 8×8 face plus hat layer. | n/a |

**Avoid these:**

- Bars and widgets, which get squashed into 8×8: `hud/experience_bar_*` and `boss_bar/*` (182×5), `widget/button*` (200×20), `tooltip/*` (100×100), `toast/*` (≥160×32).
- `gui:world_list/warning`: a 32×32 tile whose "!" is a 6×22 sliver at the left edge.
- `celestials:*`: opaque black backgrounds.
- Entity sheet atlases: chests, shulker_boxes, banner_patterns, shield_patterns.
- `paintings`, `decorated_pot` and `armor_trims`.
- `container/slot/*` and `mob_effect/*` if players use GUI scale 1.
- Grey textures that vanilla biome/dye-tints, which look grey in text unless you tint them: `block/grass_block_top`, `*_leaves`, `water_still`, `vine`, `lily_pad`, `item/leather_*`, `item/potion_overlay`, `item/firework_star*`.

**Animated** sprites (they animate inside text):

- gui: `icon/music_notes` (16×16, 8 frames), `icon/trial_available` (8×8, 6), `hud/locator_bar_arrow_up/down` (7×5, 2), `friends/loading` (5×2, 3), `realm_status/expires_soon` (10×28, 2)
- particles: `vibration` (18×18, 7)
- blocks: 56 sprites, including water/lava, fire, portal, prismarine, sea_lantern, magma, command blocks, sculk, lanterns and campfires

### Implementation sketch (compiled and run against Adventure 5.2.0)

```java
enum Tint { KEEP, INHERIT }
enum Icon {
  MONEY("items", "item/gold_ingot", Tint.KEEP),
  KDR("particles", "critical_hit", Tint.INHERIT); // …
  final ObjectComponent component;
  Icon(String atlas, String sprite, Tint tint) {
    ObjectComponent c = Component.object(ObjectContents.sprite(Key.key(atlas), Key.key(sprite)));
    this.component = tint == Tint.KEEP ? c.color(NamedTextColor.WHITE) : c; // white = original colours
  }
}
static final TagResolver ICONS = TagResolver.resolver("icon", (args, ctx) -> {
  String name = args.popOr("<icon:name> needs a name").lowerValue();
  try { return Tag.selfClosingInserting(Icon.valueOf(name.toUpperCase(Locale.ROOT)).component); }
  catch (IllegalArgumentException e) { throw ctx.newException("Unknown icon '" + name + "'", args); }
});
// trusted templates only:
MiniMessage mm = MiniMessage.builder().editTags(t -> t.resolver(ICONS)).build();
mm.deserialize("<gray><icon:money> <amount>", Placeholder.unparsed("amount", rawUserText));
// gives {"color":"gray","extra":[{"color":"white","atlas":"minecraft:items","sprite":"minecraft:item/gold_ingot"}," …"]}
```

An unknown `<icon:x>` is left as literal text, because MiniMessage swallows resolver exceptions in lenient mode. At enable time:

1. Check every `Icon` against `atlas-index.txt`.
2. Scan config and lang strings for `<icon:` and `<sprite:` and validate them.
3. Reject `§` codes in templates.
4. Optionally let config override `atlas`/`sprite` per icon, with the same validation.

## 7. Reproduce

The scripts are in the session scratchpad, `…/scratchpad/research/sprites-scripts/`. All standard library or JDK only:

- `gen_atlas_index.py`: the generator.
- `curated-ids.txt`: the curated ids.
- `probe/*.java`: MiniMessage probes.
- `probe-plugin/`: the Canvas probe plugin.
- `clientharness/AtlasDump.java`: runs the client's own atlas code headless.

```sh
# 1. client jar: version_manifest_v2.json -> id "26.2" -> downloads.client (verify sha1 2dc72797…)
python3 -I gen_atlas_index.py index --jar client-26.2.jar --version-json 26.2.json \
        --out src/main/resources/atlas-index.txt [--meta atlas-meta.tsv] [--header]
python3 -I gen_atlas_index.py show --preview --jar client-26.2.jar gui:hud/heart/full items:item/emerald   # pixel view + GUI-scale 1/2 previews
python3 -I gen_atlas_index.py check --jar client-26.2.jar --ids curated-ids.txt                           # exit 1 on any MISS
python3 -I gen_atlas_index.py diff --jar client-26.2.jar --jar2 client-26.3.jar                           # version drift
```

When moving to a new Minecraft version:

1. Regenerate the index.
2. Re-run `check` on the curated ids.
3. Re-check `AtlasManager.KNOWN_ATLASES` in the new client. The generator's `KNOWN_ATLASES` list is copied from 26.2, and 26.3 already dropped `armor_trims`.
