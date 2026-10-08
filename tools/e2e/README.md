# SiftE2E: end-to-end test harness

A test-only plugin that runs scripted scenarios against SiftCore with real protocol clients ("bots") running
inside the server JVM. The bots use the server's own network codecs, so they log in, receive dialogs, chest GUIs,
chat and action bars, and send commands, dialog clicks and container clicks exactly like a vanilla client.
It is never deployed to a production server.

## Build

```sh
tools/e2e/build.sh <test-server-dir> target/SiftCore-1.0.0.jar <jdk-25-home>
```

The test server directory must contain `versions/` (the patched Canvas jar) and `libraries/`.

## Run

1. Copy `SiftCore-1.0.0.jar` and `tools/e2e/SiftE2E.jar` into the test server's `plugins/`, plus
   VaultUnlocked 2.20.3 (`VaultUnlocked-2.20.3.jar`, plugin name `Vault`) for the `vault-economy` scenario.
2. Use `online-mode=false`, `allow-flight=true` (bots don't simulate gravity) and a high
   `packet-limiter.all-packets.max-packet-rate` in `config/paper-global.yml` (the bot's client-side connection
   reuses the server's packet limiter).
3. Console: `e2e list`, `e2e run all` or `e2e run pay forged-clicks`.
4. Results are logged as `E2E PASS <scenario>` / `E2E FAIL <scenario> at step '...'` and a final
   `E2E SUMMARY passed=N failed=M`.

Each scenario uses fresh bot names (a per-run suffix), so runs never depend on earlier data. A run stops the day
cycle and sets every world to midday, so hostile mobs don't attack bots during long runs.

New players arrive at the spawn point, inside SiftCore's protected spawn area where nobody can build, fight or be
hurt. `E2E#bot` therefore moves every freshly joined bot onto the ground just past the edge of that area (within 64
blocks of the spawn on both axes), where scenarios can mine, place and hit like on the live server.
`E2E#botAtSpawn` leaves a bot where it joined (for arrival and spawn protection scenarios), and `E2E#ground` gives a
safe standing spot on top of any column.

## Older clients (ViaBackwards)

`legacy_client.py` is a minimal 1.21.5 client (protocol 770) for checking that pre-dialog clients can still use
SiftCore through ViaBackwards, which shows dialogs as chest menus. With ViaVersion and ViaBackwards in the test
server's `plugins/`:

```sh
python3 -I tools/e2e/legacy_client.py <port> OldBot 1
```

It logs in, runs `/menu`, clicks chest slot 1 (the first menu button) and prints every screen it is shown. The
expected output ends with `SCREENS: ['SiftVanilla', 'Money']`: the click went through ViaBackwards to SiftCore's
dialog router, which answered with the next screen.
