package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MainCasinoCommand implements CommandExecutor, org.bukkit.command.TabCompleter {
    private final casino plugin;

    public MainCasinoCommand(casino plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(plugin.tr("common.player_only")); return true; }
        if (!p.hasPermission("casino.use")) { p.sendMessage(plugin.tr("common.no_permission")); return true; }
        if (args.length == 0) { sendHelp(p, label); return true; }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "coin", "flip" -> plugin.getCoinFlipManager().open(p, parseLong(args, 1, plugin.getConfig().getInt("coinflip.min-bet",10)));
            case "rsp", "rps" -> plugin.getRspManager().handleCommand(p, args);
            case "dice" -> {
                int min = plugin.getConfig().getInt("dice.min-bet",10);
                int max = plugin.getConfig().getInt("dice.max-bet",10000);
                if (args.length < 2) { p.sendMessage(plugin.tr("help.header_dice", Map.of("label", label))); return true; }
                long bet = parseLong(args,1,-1);
                if (bet <= 0) { p.sendMessage(plugin.tr("dice.bet_range", Map.of("min", min, "max", max))); return true; }
                if (bet < min || bet > max) { p.sendMessage(plugin.tr("dice.bet_range", Map.of("min", min, "max", max))); return true; }
                plugin.getDiceManager().startGame(p, bet);
            }
            case "scratch" -> plugin.getScratchManager().open(p);
            case "lottery" -> plugin.getLotteryManager().handleCommand(p, args);
            case "horse" -> plugin.getHorseManager().open(p, parseLong(args, 1, -1));
            case "slots" -> plugin.getSlotMachineManager().openGui(p);
            case "reload" -> { if (!p.hasPermission("casino.admin")) { p.sendMessage(plugin.tr("common.no_permission")); return true; } reloadAll(p); }
            default -> sendHelp(p, label);
        }
        return true;
    }

    private void reloadAll(Player p){
        plugin.reloadHouseSettings();
        plugin.getCoinFlipManager().reload();
        plugin.getRspManager().reload();
        plugin.getDiceManager().reload();
        plugin.getScratchManager().reload();
        plugin.getLotteryManager().reload();
        plugin.getHorseManager().reload();
        p.sendMessage(plugin.tr("common.reload_done"));
    }

    private void sendHelp(Player p, String label) {
        p.sendMessage(plugin.tr("help.header_coin", Map.of("label", label)));
        p.sendMessage(plugin.tr("help.header_rsp", Map.of("label", label)));
        p.sendMessage(plugin.tr("help.header_dice", Map.of("label", label)));
        p.sendMessage(plugin.tr("help.header_scratch", Map.of("label", label)));
        p.sendMessage(plugin.tr("help.header_lottery", Map.of("label", label)));
        p.sendMessage(plugin.tr("help.header_horse", Map.of("label", label)));
        p.sendMessage(plugin.tr("help.header_slots", Map.of("label", label)));
    }

    private long parseLong(String[] args, int idx, long def) { if (args.length <= idx) return def; try { return Long.parseLong(args[idx]); } catch (NumberFormatException e) { return def; } }

    @Override
    public List<String> onTabComplete(@NotNull org.bukkit.command.CommandSender sender, @NotNull org.bukkit.command.Command command, @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("casino")) return Collections.emptyList();
        List<String> base = Arrays.asList("coin","flip","rsp","rps","dice","scratch","lottery","horse","slots","reload");
        if (args.length==1){ return filter(base, args[0]); }
        if (args.length==2){
            String sub=args[0].toLowerCase();
            switch(sub){
                case "coin","flip","dice","horse" -> { return Collections.singletonList("<베팅>"); }
                case "rsp","rps" -> {
                    // 현재 온라인 플레이어 + '봇','bot'
                    String prefix = args[1].toLowerCase();
                    List<String> names = new ArrayList<>();
                    names.add("봇"); names.add("bot");
                    for (org.bukkit.entity.Player op : Bukkit.getOnlinePlayers()) names.add(op.getName());
                    return filter(names, prefix);
                }
                case "lottery" -> { return Arrays.asList("buy", "draw", "drawset"); }
            }
        }
        if (args.length>=3 && args[0].equalsIgnoreCase("lottery")){
            String sub2 = args[1].toLowerCase();
            if (sub2.equals("drawset")){
                // 숫자 힌트 표현
                int need = plugin.getLotteryManager()!=null ? plugin.getLotteryManager().getPickCountSafe() : 6;
                List<String> hint = new ArrayList<>();
                for (int i=0;i<need;i++) hint.add("<n"+(i+1)+">");
                if (plugin.getLotteryManager()!=null && plugin.getLotteryManager().isBonusEnabledSafe()) hint.add("[bonus]");
                return hint;
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String prefix){ List<String> out=new ArrayList<>(); String pre=prefix==null?"":prefix.toLowerCase(); for (String s: list) if (s.toLowerCase().startsWith(pre)) out.add(s); return out; }
}
