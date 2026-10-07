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

1. Copy `SiftCore-1.0.0.jar` and `tools/e2e/SiftE2E.jar` into the test server's `plugins/`.
2. Use `online-mode=false`, `allow-flight=true` (bots don't simulate gravity) and a high
   `packet-limiter.all-packets.max-packet-rate` in `config/paper-global.yml` (the bot's client-side connection
   reuses the server's packet limiter).
3. Console: `e2e list`, `e2e run all` or `e2e run pay forged-clicks`.
4. Results are logged as `E2E PASS <scenario>` / `E2E FAIL <scenario> at step '...'` and a final
   `E2E SUMMARY passed=N failed=M`.

Each scenario uses fresh bot names (a per-run suffix), so runs never depend on earlier data.
