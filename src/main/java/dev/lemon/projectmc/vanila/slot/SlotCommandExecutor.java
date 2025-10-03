package dev.lemon.projectmc.vanila.slot;

import org.bukkit.ChatColor;
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
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "플레이어만 사용할 수 있습니다.");
                return true;
            }
            if (!p.hasPermission("casino.slots.use")) {
                p.sendMessage(ChatColor.RED + "권한이 없습니다.");
                return true;
            }
            manager.openGui(p);
            return true;
        }
        // admin subcommands
        if (!sender.hasPermission("casino.slots.admin")) {
            sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                manager.reload();
                sender.sendMessage(ChatColor.YELLOW + "[슬롯머신] 설정을 새로 고쳤습니다.");
                return true;
            case "announcejackpot":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "사용법: /slots announcejackpot <on|off>");
                    return true;
                }
                boolean val = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                manager.toggleJackpotAnnounce(val);
                sender.sendMessage(ChatColor.YELLOW + "[슬롯머신] 잭팟 방송: " + (val ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
                return true;
            default:
                sender.sendMessage(ChatColor.RED + "알 수 없는 하위 명령. (reload / announcejackpot)");
                return true;
        }
    }
}

