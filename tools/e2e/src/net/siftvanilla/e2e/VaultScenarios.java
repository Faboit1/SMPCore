package net.siftvanilla.e2e;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * SiftCore as the server's Vault economy, called the way other plugins call it. Needs VaultUnlocked (plugin name
 * "Vault") in the test server's plugins folder. The Vault API is reached by reflection so this test plugin does not
 * need it to compile; the calls still come from this plugin's classes, so SiftCore names SiftE2E as the caller.
 */
final class VaultScenarios {

    private VaultScenarios() {
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(new Scenario() {
            @Override
            public String name() {
                return "vault-economy";
            }

            @Override
            public void run(E2E e2e) throws Exception {
                economy(e2e);
            }
        });
        return list;
    }

    private static Object provider(E2E e2e, String type) throws Exception {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        e2e.expect(vault != null && vault.isEnabled(), "VaultUnlocked is installed in the test server");
        Class<?> api = Class.forName(type, true, vault.getClass().getClassLoader());
        RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(api);
        e2e.expect(registration != null, "an economy is registered for " + type);
        e2e.expect("SiftCore".equals(registration.getPlugin().getName()), type + " is SiftCore's, not "
            + registration.getPlugin().getName());
        return registration.getProvider();
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getInterfaces()[0].getMethod(name, types);
        return method.invoke(target, args);
    }

    private static Object field(Object response, String name) throws Exception {
        Field field = response.getClass().getField(name);
        return field.get(response);
    }

    /** The kinds of a player's stored ledger rows, newest first (rows are written shortly after the move). */
    private static List<String> kinds(E2E e2e, UUID player) {
        try {
            return e2e.services().ledger().history(player, 10, 0).get(10, TimeUnit.SECONDS).stream()
                .map(EconomyApi.LedgerEntry::kind).toList();
        } catch (Exception e) {
            throw new E2E.Failure("could not read the ledger: " + e);
        }
    }

    static void economy(E2E e2e) throws Exception {
        String name = e2e.name("VaultPay");
        e2e.bot(name);
        UUID id = e2e.uuid(name);
        OfflinePlayer player = Bukkit.getOfflinePlayer(id);
        Object legacy = provider(e2e, "net.milkbowl.vault.economy.Economy");
        Object modern = provider(e2e, "net.milkbowl.vault2.economy.Economy");
        Class<?>[] playerAmount = {OfflinePlayer.class, double.class};

        e2e.step("a plugin pays a player: whole dollars, rounded down");
        Object paid = call(legacy, "depositPlayer", playerAmount, player, 250.75);
        e2e.expect(String.valueOf(field(paid, "type")).equals("SUCCESS"), "the deposit succeeds: " + field(paid, "errorMessage"));
        e2e.expect((double) field(paid, "amount") == 250.0, "$250 paid: " + field(paid, "amount"));
        e2e.eventually(() -> e2e.money(name) == 250, "the ledger shows $250: " + e2e.money(name));
        e2e.expect((double) call(legacy, "getBalance", new Class<?>[] {OfflinePlayer.class}, player) == 250.0, "Vault reads $250");

        e2e.step("a plugin charges a player: rounded up, and refused when they can't pay");
        e2e.expect((boolean) call(legacy, "has", playerAmount, player, 250.0), "has $250");
        e2e.expect(!(boolean) call(legacy, "has", playerAmount, player, 250.5), "does not have $250.50 (that costs $251)");
        Object charged = call(legacy, "withdrawPlayer", playerAmount, player, 100.2);
        e2e.expect(String.valueOf(field(charged, "type")).equals("SUCCESS"), "the charge succeeds");
        e2e.expect(e2e.money(name) == 149, "$101 taken: " + e2e.money(name));
        Object refused = call(legacy, "withdrawPlayer", playerAmount, player, 1_000.0);
        e2e.expect(String.valueOf(field(refused, "type")).equals("FAILURE"), "charging $1,000 fails");
        e2e.expect(e2e.money(name) == 149, "nothing taken: " + e2e.money(name));
        Object negative = call(legacy, "depositPlayer", playerAmount, player, -5.0);
        e2e.expect(String.valueOf(field(negative, "type")).equals("FAILURE"), "negative amounts fail");

        e2e.step("the ledger names the calling plugin");
        e2e.eventually(() -> kinds(e2e, id).size() >= 2 && kinds(e2e, id).stream().limit(2).allMatch("vault_sifte2e"::equals),
            "the stored moves are vault_sifte2e: " + kinds(e2e, id));

        e2e.step("the VaultUnlocked interface works on the same money");
        Object modernPaid = call(modern, "deposit", new Class<?>[] {String.class, UUID.class, BigDecimal.class},
            "QuestsPlugin", id, new BigDecimal("5.9"));
        e2e.expect((boolean) modernPaid.getClass().getMethod("transactionSuccess").invoke(modernPaid), "the modern deposit succeeds");
        e2e.expect(e2e.money(name) == 154, "$5 more: " + e2e.money(name));
        e2e.eventually(() -> !kinds(e2e, id).isEmpty() && kinds(e2e, id).getFirst().equals("vault_questsplugin"),
            "named by the caller: " + kinds(e2e, id));
        Object balance = call(modern, "getBalance", new Class<?>[] {String.class, UUID.class}, "QuestsPlugin", id);
        e2e.expect(new BigDecimal("154").compareTo((BigDecimal) balance) == 0, "the modern balance is $154: " + balance);
        e2e.expect("$154".equals(call(legacy, "format", new Class<?>[] {double.class}, 154.0)), "formats like SiftCore");
    }
}
