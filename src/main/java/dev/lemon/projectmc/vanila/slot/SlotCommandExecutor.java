package dev.lemon.projectmc.vanila.slot;

import dev.lemon.projectmc.vanila.casino;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SlotCommandExecutor implements CommandExecutor {
    private final SlotMachineManager manager;

    public SlotCommandExecutor(SlotMachineManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        casino plugin = manager.getPlugin();
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.tr("slots.cmd_player_only"));
                return true;
            }
            if (!p.hasPermission("casino.slots.use")) {
                p.sendMessage(plugin.tr("slots.cmd_no_permission"));
                return true;
            }
            manager.openGui(p);
            return true;
        }
        // admin subcommands
        if (!sender.hasPermission("casino.slots.admin")) {
            sender.sendMessage(plugin.tr("slots.cmd_no_permission"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                manager.reload();
                sender.sendMessage(plugin.tr("slots.cmd_reload_done"));
                return true;
            case "announcejackpot":
                if (args.length < 2) {
                    sender.sendMessage(plugin.tr("slots.cmd_usage_announcejackpot"));
                    return true;
                }
                boolean val = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                manager.toggleJackpotAnnounce(val);
                sender.sendMessage(val? plugin.tr("slots.cmd_jackpot_announce_on") : plugin.tr("slots.cmd_jackpot_announce_off"));
                return true;
            default:
                sender.sendMessage(plugin.tr("slots.cmd_unknown_subcommand"));
                return true;
        }
    }
}
