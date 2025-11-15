package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReloadCommand implements CommandExecutor {
    private final casino plugin;
    public ReloadCommand(casino plugin){ this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player p) {
            if (!p.hasPermission("casino.admin")) { p.sendMessage(plugin.tr("common.no_permission")); return true; }
        }
        plugin.reloadHouseSettings();
        plugin.getCoinFlipManager().reload();
        plugin.getRspManager().reload();
        plugin.getDiceManager().reload();
        plugin.getScratchManager().reload();
        plugin.getLotteryManager().reload();
        plugin.getHorseManager().reload();
        sender.sendMessage(plugin.tr("common.reload_done"));
        return true;
    }
}
