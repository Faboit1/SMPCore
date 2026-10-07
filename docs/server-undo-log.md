# SiftVanilla 2 server change log (undo log)

Server: "SiftVanilla 2" on the Pterodactyl panel, identifier `93babcbd` (uuid 93babcbd-cf82-4a57-a61e-d5fbd91f83bd), allocation 37.27.67.240:25602.
No other server on the panel was touched.

Every change made to the server is listed here in order, with how to revert it. All paths are relative to the server root.
The owner said full backups are optional (the server was brand new), so only small config files were kept as `.bak` copies.

| # | When (UTC) | Change | How to revert |
|---|------------|--------|---------------|
| 1 | 2026-10-07 21:27 | Pulled `canvas-26.2-962.jar` (Canvas build 962, MC 26.2, sha256 a4139189d3bc09695afea21a2595d435f530e21ebb099065b8082d9f14ec93e5) from jenkins.canvasmc.io via the panel's remote-download endpoint. | Delete the file. |
| 2 | 2026-10-07 21:27 | Stopped the server. Renamed `server.jar` (Paper 26.3-159) to `paper-26.3-159.jar.bak-20261007`, renamed `canvas-26.2-962.jar` to `server.jar`. | Stop, rename `paper-26.3-159.jar.bak-20261007` back to `server.jar`. |
| 3 | 2026-10-07 21:27 | Deleted `world/` (an empty 26.3 world that 26.2 cannot load), `versions/`, `libraries/`, `cache/`, `.paper/`, `.cache/`, `usercache.json`. All regenerate on start. | Not needed; Paper re-creates them. A 26.3 world cannot be opened by 26.2 anyway. |
| 4 | 2026-10-07 21:28 | Renamed `config/paper-global.yml` and `config/paper-world-defaults.yml` (Paper 26.3 schema) to `*.bak-26.3` so Canvas generates its own. | Rename back (only valid when going back to Paper 26.3). |
| 5 | 2026-10-07 21:28 | Started the server on Canvas: clean boot, new 26.2 world generated. | See row 2. |
