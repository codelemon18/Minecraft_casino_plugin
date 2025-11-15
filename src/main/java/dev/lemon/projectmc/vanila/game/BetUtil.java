package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;

public final class BetUtil {
    private BetUtil() {}

    public static boolean withdraw(casino plugin, Player p, long amount) {
        if (amount <= 0 || p == null) return false;
        Economy eco = plugin.getEconomy();
        if (eco == null) return false;
        if (eco.getBalance(p) < amount) return false;
        EconomyResponse resp = eco.withdrawPlayer(p, amount);
        return resp != null && resp.transactionSuccess();
    }

    public static long applyTaxAndDeposit(casino plugin, Player p, long gross) {
        if (gross <= 0 || p == null) return 0L;
        double net = plugin.applyHouseTax(gross);
        long pay = Math.round(net);
        if (pay <= 0) return 0L;
        Economy eco = plugin.getEconomy();
        if (eco == null) return 0L;
        EconomyResponse resp = eco.depositPlayer(p, pay);
        return (resp != null && resp.transactionSuccess()) ? pay : 0L;
    }

    public static void depositRaw(casino plugin, Player p, long amount) {
        if (amount <= 0 || p == null) return;
        Economy eco = plugin.getEconomy();
        if (eco == null) return;
        eco.depositPlayer(p, amount);
    }
}
