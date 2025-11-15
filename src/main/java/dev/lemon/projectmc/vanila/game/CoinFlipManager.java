package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import dev.lemon.projectmc.vanila.util.LoggerBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class CoinFlipManager implements Listener {
    private final casino plugin;
    private int minBet, maxBet, animTicks;
    private String headsName, tailsName;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private volatile boolean shuttingDown = false;

    private static final int INV_SIZE = 27;
    private static final int HEADS_SLOT = 11;
    private static final int CENTER_SLOT = 13;
    private static final int TAILS_SLOT = 15;

    public CoinFlipManager(casino plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        minBet = plugin.getConfig().getInt("coinflip.min-bet", 10);
        maxBet = plugin.getConfig().getInt("coinflip.max-bet", 5000);
        animTicks = plugin.getConfig().getInt("coinflip.animation-ticks", 40);
        headsName = plugin.getConfig().getString("coinflip.heads-name", "앞면");
        tailsName = plugin.getConfig().getString("coinflip.tails-name", "뒷면");
    }

    public void shutdown(){
        shuttingDown = true;
        // 세션 단순 정리(애니메이션은 짧으므로 별도 취소 없음)
        sessions.clear();
    }

    public void open(Player p, long bet) {
        if (bet < minBet || bet > maxBet) {
            p.sendMessage(((casino)plugin).tr("coinflip.bet_range", Map.of("min", minBet, "max", maxBet)));
            return;
        }
        if (!BetUtil.withdraw(plugin, p, bet)) {
            p.sendMessage(((casino)plugin).tr("coinflip.insufficient"));
            return;
        }
        Inventory inv = Bukkit.createInventory(p, INV_SIZE, ((casino)plugin).tr("coinflip.gui_title", Map.of("bet", bet)));
        ItemStack heads = named(Material.SUNFLOWER, ((casino)plugin).tr("coinflip.heads_name"));
        ItemStack tails = named(Material.FERMENTED_SPIDER_EYE, ((casino)plugin).tr("coinflip.tails_name"));
        fill(inv, named(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(HEADS_SLOT, heads);
        inv.setItem(TAILS_SLOT, tails);
        inv.setItem(CENTER_SLOT, named(Material.CLOCK, ((casino)plugin).tr("coinflip.waiting")));
        p.openInventory(inv);
        sessions.put(p.getUniqueId(), new Session(bet, inv));
    }

    private ItemStack named(Material m, String name) {
        ItemStack is = new ItemStack(m);
        ItemMeta im = is.getItemMeta();
        if (im != null) { im.setDisplayName(name); is.setItemMeta(im);} return is;
    }
    private void fill(Inventory inv, ItemStack is) { for (int i=0;i<inv.getSize();i++) inv.setItem(i, is); }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isOurInv(e.getView().title())) return;
        e.setCancelled(true);
        if (e.getClickedInventory()!=e.getView().getTopInventory()) return;
        Session s = sessions.get(p.getUniqueId()); if (s==null) return;
        if (s.state!=State.PICK) return;
        int slot = e.getRawSlot();
        if (slot!=HEADS_SLOT && slot!=TAILS_SLOT) return;
        s.state = State.ANIM;
        s.choice = (slot==HEADS_SLOT?Choice.HEADS:Choice.TAILS);
        // 결과 선결정 (공정성 유지): animation 이전에 미리 result 저장
        s.result = new Random().nextBoolean()?Choice.HEADS:Choice.TAILS;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            s.ticks++;
            if (s.ticks>=animTicks) {
                task.cancel();
                finish(p, s);
                return;
            }
            if (s.ticks%2==0) s.flip = !s.flip;
            invSet(s.inv, CENTER_SLOT, named(s.flip?Material.SUNFLOWER:Material.FERMENTED_SPIDER_EYE, s.flip?((casino)plugin).tr("coinflip.heads_name"):((casino)plugin).tr("coinflip.tails_name")));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, s.flip?1.6f:1.3f);
        }, 0L, 2L);
    }

    private void invSet(Inventory inv, int slot, ItemStack item){ if (slot>=0 && slot<inv.getSize()) inv.setItem(slot,item); }

    private boolean isOurInv(Component title) {
        String plain = PlainTextComponentSerializer.plainText().serialize(title);
        return plain.startsWith("코인 플립");
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (isOurInv(e.getView().title())) e.setCancelled(true); }
    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return; if (!isOurInv(e.getView().title())) return;
        if (shuttingDown) return;
        Session s = sessions.get(p.getUniqueId()); if (s==null) return;
        if (s.state!=State.DONE) Bukkit.getScheduler().runTask(plugin, ()-> p.openInventory(s.inv));
    }

    private void finish(Player p, Session s) {
        Choice result = s.result; // 선결정된 결과 사용
        boolean win = (s.choice==result);
        long net = 0L;
        if (win) {
            long gross = s.bet*2L;
            net = BetUtil.applyTaxAndDeposit(plugin, p, gross);
        }
        s.inv.setItem(CENTER_SLOT, named(win?Material.LIME_CONCRETE:Material.RED_CONCRETE, win?ChatColor.GREEN+"승리":"패배"));
        if (result==Choice.HEADS) {
            s.inv.setItem(HEADS_SLOT, named(Material.LIME_CONCRETE, ChatColor.GREEN+((casino)plugin).tr("coinflip.heads_name")));
        } else {
            s.inv.setItem(TAILS_SLOT, named(Material.LIME_CONCRETE, ChatColor.GREEN+((casino)plugin).tr("coinflip.tails_name")));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.closeInventory(), 10L);
        if (win) {
            p.sendMessage(((casino)plugin).tr("coinflip.win", Map.of("net", net)));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
            LoggerBridge.info(plugin, "coinflip", "win player="+p.getName()+" bet="+s.bet+" net="+net);
        } else {
            p.sendMessage(((casino)plugin).tr("coinflip.lose"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.7f);
            LoggerBridge.info(plugin, "coinflip", "lose player="+p.getName()+" bet="+s.bet);
        }
        s.state = State.DONE;
        sessions.remove(p.getUniqueId());
    }

    enum State { PICK, ANIM, DONE }
    enum Choice { HEADS, TAILS }
    static class Session {
        final long bet; final Inventory inv; State state=State.PICK; Choice choice; boolean flip; int ticks; Choice result; // 결과 선결정 저장
        Session(long bet, Inventory inv){ this.bet=bet; this.inv=inv; }
    }
}
