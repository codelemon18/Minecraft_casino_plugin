package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import dev.lemon.projectmc.vanila.util.LoggerBridge;
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
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class DiceManager implements Listener {
    private final casino plugin; private int minBet,maxBet,animTicks; private double baseMulti;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private static final int RETRY_SLOT = 25; // 결과 후 재시작 버튼
    private static final int EXIT_SLOT = 26;  // 결과 후 종료 버튼

    public DiceManager(casino plugin){ this.plugin=plugin; reload(); Bukkit.getPluginManager().registerEvents(this, plugin);}
    public void reload(){ minBet=plugin.getConfig().getInt("dice.min-bet",10); maxBet=plugin.getConfig().getInt("dice.max-bet",10000); animTicks=plugin.getConfig().getInt("dice.roll-animation-ticks",30); baseMulti=plugin.getConfig().getDouble("dice.base-multiplier",5.0);}

    public void open(Player p){
        Inventory inv = Bukkit.createInventory(p, 27, plugin.tr("dice.gui_title_select", Map.of("bet", 0))); // bet 0 placeholder
        fill(inv, pane());
        for (int i=0;i<6;i++) inv.setItem(10+i, number(i+1,false));
        inv.setItem(22, named(Material.PAPER, plugin.tr("dice.select_hint")));
        p.openInventory(inv);
    }

    public void open(Player p, long bet, boolean[] picks){
        Inventory inv = Bukkit.createInventory(p, 27, plugin.tr("dice.gui_title_select", Map.of("bet", bet)));
        fill(inv, pane());
        for (int i=0;i<6;i++){ /* selection phase: no header */ inv.setItem(10+i, number(i+1,picks[i])); }
        inv.setItem(22, confirmButton(false));
        p.openInventory(inv);
        sessions.put(p.getUniqueId(), new Session(bet, picks, inv));
    }

    private ItemStack confirmButton(boolean enabled){ return named(enabled?Material.EMERALD_BLOCK:Material.RED_CONCRETE, plugin.tr(enabled?"dice.confirm_button_enabled":"dice.confirm_button_disabled")); }
    private ItemStack rollButton(){ return named(Material.EMERALD_BLOCK, plugin.tr("dice.roll_button")); }
    private ItemStack centerIdle(){ return named(Material.WHITE_CONCRETE, "?"); }
    private ItemStack number(int n, boolean selected){ return named(selected?Material.LIME_CONCRETE:Material.WHITE_CONCRETE, plugin.tr("dice.number", Map.of("n", n)) + (selected? plugin.tr("dice.number_selected_suffix"):"")); }
    private ItemStack numberLocked(int n, boolean selected){ return named(selected?Material.GREEN_CONCRETE:Material.GRAY_CONCRETE, plugin.tr("dice.number", Map.of("n", n)) + (selected? plugin.tr("dice.number_selected_suffix"):"")); }
    private ItemStack pane(){ return named(Material.GRAY_STAINED_GLASS_PANE, " "); }
    private ItemStack named(Material m, String name){ ItemStack is=new ItemStack(m); ItemMeta im=is.getItemMeta(); if (im!=null){ im.setDisplayName(name); is.setItemMeta(im);} return is; }
    private void fill(Inventory inv, ItemStack is){ for (int i=0;i<inv.getSize();i++) inv.setItem(i,is);}
    private String fmt(long v){ return String.format("%,d", v); }

    private ItemStack numberHeaderSelected(int n){ return named(Material.LIME_CONCRETE, plugin.tr("dice.number", Map.of("n", n)) + plugin.tr("dice.number_selected_suffix")); }
    private ItemStack numberHeaderUnselected(int n){ return named(Material.WHITE_CONCRETE, plugin.tr("dice.number", Map.of("n", n))); }
    private ItemStack numberHeaderResult(int n, boolean match){
        if (match) return named(Material.GREEN_CONCRETE, plugin.tr("dice.number", Map.of("n", n)) + plugin.tr("dice.number_selected_suffix"));
        return named(Material.RED_CONCRETE, plugin.tr("dice.number", Map.of("n", n)) + plugin.tr("dice.number_miss_suffix"));
    }
    private ItemStack numberResultSuccess(int n){ return named(Material.GREEN_CONCRETE, plugin.tr("dice.number", Map.of("n", n)) + plugin.tr("dice.number_selected_suffix")); }
    private ItemStack numberResultFail(int n){ return named(Material.RED_CONCRETE, plugin.tr("dice.number", Map.of("n", n)) + plugin.tr("dice.number_miss_suffix")); }
    private ItemStack numberResultUnpicked(int n){ return named(Material.WHITE_CONCRETE, plugin.tr("dice.number", Map.of("n", n))); }

    private void openRollingInventory(Player p, Session s){
        Inventory rollInv = Bukkit.createInventory(p, 27, plugin.tr("dice.gui_title_roll", Map.of("bet", s.bet)));
        fill(rollInv, pane());
        // 헤더 라인(오른쪽으로 한 칸 이동: 슬롯 1~6)에 이전 선택 반영
        for (int i=0;i<6;i++) rollInv.setItem(1+i, s.picks[i]? numberHeaderSelected(i+1) : numberHeaderUnselected(i+1));
        rollInv.setItem(22, rollButton());
        rollInv.setItem(13, centerIdle());
        s.inv = rollInv;
        s.phase = Phase.READY;
        s.transitioning = true;
        p.openInventory(rollInv);
        Bukkit.getScheduler().runTask(plugin, () -> s.transitioning = false);
    }

    private void reopenSelection(Player p, Session s){
        Arrays.fill(s.picks,false); s.phase=Phase.SELECT; s.ticks=0; s.faceResult=0; if (s.task!=null){ s.task.cancel(); s.task=null; }
        Inventory inv = Bukkit.createInventory(p, 27, plugin.tr("dice.gui_title_select", Map.of("bet", s.bet)));
        fill(inv, pane());
        for (int i=0;i<6;i++){ /* selection phase: no header */ inv.setItem(10+i, number(i+1,false)); }
        inv.setItem(22, confirmButton(false));
        s.inv = inv;
        s.transitioning = true;
        p.openInventory(inv);
        Bukkit.getScheduler().runTask(plugin, () -> s.transitioning = false);
    }

    enum Phase { SELECT, READY, ROLLING, DONE }
    static class Session{ final long bet; boolean[] picks; Inventory inv; Phase phase; int ticks; int faceResult; org.bukkit.scheduler.BukkitTask task; boolean transitioning; Session(long b, boolean[] p, Inventory i){ bet=b;picks=p;inv=i; phase=Phase.SELECT; }}

    public void startGame(Player p, long bet){
        if (bet<minBet || bet>maxBet){ p.sendMessage(plugin.tr("dice.bet_range", Map.of("min", minBet, "max", maxBet))); return; }
        if (!BetUtil.withdraw(plugin,p,bet)){ p.sendMessage(plugin.tr("dice.insufficient")); return; }
        boolean[] picks = new boolean[6];
        open(p, bet, picks);
    }

    // 선택 단계 인벤 클릭 처리 / 굴리기 등 이벤트 핸들러
    @EventHandler public void onClick(InventoryClickEvent e){
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId()); if (s==null) return;
        if (s.inv==null || e.getView().getTopInventory()!=s.inv) return; // 다른 인벤
        e.setCancelled(true);
        int raw = e.getRawSlot();
        if (raw<0) return;
        switch (s.phase){
            case SELECT -> handleSelectClick(p, s, raw);
            case READY -> { if (raw==22) beginRoll(p,s); else if (raw==EXIT_SLOT) exitSession(p); }
            case ROLLING -> { /* 무시 */ if (raw==EXIT_SLOT) exitSession(p); }
            case DONE -> handleDoneClick(p, s, raw);
        }
    }

    private void handleSelectClick(Player p, Session s, int slot){
        if (slot==EXIT_SLOT){ exitSession(p); return; }
        if (slot>=10 && slot<=15){ // 숫자 토글
            int idx = slot-10; s.picks[idx]= !s.picks[idx]; s.inv.setItem(slot, number(idx+1, s.picks[idx]));
            // selection phase: header 표시 없음
            updateConfirmButton(s);
        } else if (slot==22){ // 확정 버튼
            if (countPicks(s.picks)==0){ p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.6f, 0.7f); return; }
            openRollingInventory(p, s);
        }
    }

    private void handleDoneClick(Player p, Session s, int slot){
        if (slot==RETRY_SLOT){
            if (!BetUtil.withdraw(plugin,p,s.bet)){ p.sendMessage(plugin.tr("dice.insufficient")); return; }
            reopenSelection(p,s);
        } else if (slot==EXIT_SLOT){ exitSession(p); }
    }

    @EventHandler public void onDrag(InventoryDragEvent e){
        if (!(e.getWhoClicked() instanceof Player p)) return; Session s=sessions.get(p.getUniqueId()); if (s==null) return; if (e.getView().getTopInventory()==s.inv) e.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent e){
        if (!(e.getPlayer() instanceof Player p)) return; Session s=sessions.get(p.getUniqueId()); if (s==null) return; if (s.transitioning) return; // 전환중 무시
        if (s.phase==Phase.DONE){ sessions.remove(p.getUniqueId()); return; }
        // 진행 중 닫기 -> 재오픈 (무한루프 방지 지연 1틱, 강제 닫기 원하면 EXIT 버튼 사용)
        Bukkit.getScheduler().runTask(plugin, ()->{ if (p.isOnline() && sessions.containsKey(p.getUniqueId())) p.openInventory(s.inv); });
    }

    private void updateConfirmButton(Session s){ boolean enabled = countPicks(s.picks)>0; s.inv.setItem(22, confirmButton(enabled)); }
    private int countPicks(boolean[] picks){ int c=0; for (boolean b: picks) if (b) c++; return c; }

    private void beginRoll(Player p, Session s){
        if (s.phase!=Phase.READY) return; s.phase=Phase.ROLLING; s.ticks=0; s.faceResult=0;
        s.task = new BukkitRunnable(){
            @Override public void run(){
                s.ticks++;
                int face = 1 + new Random().nextInt(6);
                s.inv.setItem(13, numberLocked(face, false));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
                if (s.ticks>=animTicks){
                    s.faceResult = 1 + new Random().nextInt(6);
                    s.inv.setItem(13, numberLocked(s.faceResult, true));
                    cancel(); s.task=null; finishRoll(p,s);
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void finishRoll(Player p, Session s){
        s.phase=Phase.DONE;
        boolean win = s.picks[s.faceResult-1];
        long gross=0; long net=0;
        if (win){ int picks=countPicks(s.picks); double mult = baseMulti / Math.max(1,picks); gross=Math.round(s.bet * mult); net = BetUtil.applyTaxAndDeposit(plugin,p,gross); }
        // 결과 헤더 갱신: 선택한 숫자만 ✓(성공)/✗(실패) 표시, 미선택은 중립 표시 (1~6)
        for (int i=0;i<6;i++){
            boolean selected = s.picks[i];
            if (selected){
                boolean success = (s.faceResult==i+1);
                s.inv.setItem(1+i, numberHeaderResult(i+1, success));
            } else {
                s.inv.setItem(1+i, numberHeaderUnselected(i+1));
            }
        }
        // 두 번째 줄(슬롯 10~15)에도 동일한 성공/실패 표기 추가
        for (int i=0;i<6;i++){
            boolean selected = s.picks[i];
            if (selected){
                boolean success = (s.faceResult==i+1);
                s.inv.setItem(10+i, success? numberResultSuccess(i+1): numberResultFail(i+1));
            } else {
                s.inv.setItem(10+i, numberResultUnpicked(i+1));
            }
        }
        if (win){ p.sendMessage(plugin.tr("dice.win", Map.of("face", s.faceResult, "net", fmt(net)))); LoggerBridge.event(plugin,"dice","win", Map.of("player", p.getName(), "face", s.faceResult, "gross", gross, "net", net)); }
        else { p.sendMessage(plugin.tr("dice.lose", Map.of("face", s.faceResult))); LoggerBridge.event(plugin,"dice","lose", Map.of("player", p.getName(), "face", s.faceResult)); }
        s.inv.setItem(RETRY_SLOT, named(Material.EMERALD_BLOCK, plugin.tr("dice.retry_button", Map.of("bet", s.bet))));
        s.inv.setItem(EXIT_SLOT, named(Material.BARRIER, plugin.tr("dice.exit_button")));
        p.playSound(p.getLocation(), win? Sound.ENTITY_PLAYER_LEVELUP: Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, win?1.2f:0.6f);
    }

    private void exitSession(Player p){ Session s=sessions.remove(p.getUniqueId()); if (s!=null && s.task!=null) s.task.cancel(); p.closeInventory(); }
}
