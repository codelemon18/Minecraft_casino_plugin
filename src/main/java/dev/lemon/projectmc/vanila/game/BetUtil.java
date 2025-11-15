package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;

public final class BetUtil {
    private BetUtil() {}

    public static boolean withdraw(casino plugin, Player p, long amount) {
        if (amount <= 0) return false;
        Economy eco = plugin.getEconomy();
        if (eco.getBalance(p) < amount) return false;
        eco.withdrawPlayer(p, amount);
        return true;
    }

    public static long applyTaxAndDeposit(casino plugin, Player p, long gross) {
        if (gross <= 0) return 0L;
        double net = plugin.applyHouseTax(gross);
        long pay = Math.round(net);
        if (pay > 0) plugin.getEconomy().depositPlayer(p, pay);
        return pay;
    }

    public static void depositRaw(casino plugin, Player p, long amount) {
        if (amount > 0) plugin.getEconomy().depositPlayer(p, amount);
    }
}
