package net.siftvanilla.e2e;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only plugin that runs end-to-end scenarios against SiftCore with in-process protocol bots.
 * Console: {@code e2e run <scenario|all>}, {@code e2e list}. Results are logged as {@code E2E PASS/FAIL}.
 */
public final class E2EPlugin extends JavaPlugin {

    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();
    /** Scenarios that need a server restart in between: run by name only, never by {@code e2e run all}. */
    private final java.util.Set<String> acrossRestart = new java.util.HashSet<>();
    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    public void onEnable() {
        for (Scenario scenario : Scenarios.all()) {
            this.scenarios.put(scenario.name(), scenario);
        }
        for (Scenario scenario : AuditScenarios.acrossRestart()) {
            this.scenarios.put(scenario.name(), scenario);
            this.acrossRestart.add(scenario.name());
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
            Commands.literal("e2e")
                .requires(source -> source.getSender().isOp() || source.getSender() instanceof org.bukkit.command.ConsoleCommandSender)
                .then(Commands.literal("list").executes(ctx -> {
                    getLogger().info("E2E scenarios: " + String.join(", ", this.scenarios.keySet()));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("run").then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                    run(StringArgumentType.getString(ctx, "name"));
                    return Command.SINGLE_SUCCESS;
                })))
                .build(), "SiftCore end-to-end tests", List.of()));
    }

    private void run(String names) {
        if (!this.running.compareAndSet(false, true)) {
            getLogger().warning("E2E already running");
            return;
        }
        List<Scenario> selected = new ArrayList<>();
        if (names.equals("all")) {
            for (Scenario scenario : this.scenarios.values()) {
                if (!this.acrossRestart.contains(scenario.name())) {
                    selected.add(scenario);
                }
            }
        } else {
            for (String name : names.split("[ ,]+")) {
                Scenario scenario = this.scenarios.get(name);
                if (scenario == null) {
                    getLogger().warning("E2E unknown scenario " + name);
                    this.running.set(false);
                    return;
                }
                selected.add(scenario);
            }
        }
        // Keep every world at midday for the run: a long run otherwise reaches night, and hostile mobs outside the
        // protected spawn then hurt bots, which cancels teleport warmups and kills players mid-scenario.
        org.bukkit.Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL) {
                    continue; // the nether and the end have no world clock
                }
                try {
                    world.setGameRule(org.bukkit.GameRules.ADVANCE_TIME, false);
                    world.setTime(6_000);
                } catch (RuntimeException e) {
                    // A world that refuses (a fixed-time dimension type) keeps its clock; the run goes on.
                    getLogger().warning("E2E could not stop the clock of " + world.getName() + ": " + e);
                }
            }
        });
        Thread runner = new Thread(() -> {
            int passed = 0;
            int failed = 0;
            try {
                for (Scenario scenario : selected) {
                    E2E e2e = new E2E(this);
                    long start = System.currentTimeMillis();
                    getLogger().info("E2E RUN " + scenario.name());
                    try {
                        scenario.run(e2e);
                        passed++;
                        getLogger().info("E2E PASS " + scenario.name() + " (" + (System.currentTimeMillis() - start) + " ms)");
                    } catch (Throwable t) {
                        failed++;
                        getLogger().severe("E2E FAIL " + scenario.name() + " at step '" + e2e.currentStep() + "': " + t);
                        if (!(t instanceof E2E.Failure)) {
                            t.printStackTrace();
                        }
                    } finally {
                        e2e.cleanup();
                        e2e.sleep(1000);
                    }
                }
            } finally {
                getLogger().info("E2E SUMMARY passed=" + passed + " failed=" + failed);
                this.running.set(false);
            }
        }, "SiftE2E-Runner");
        runner.setDaemon(true);
        runner.start();
    }
}
