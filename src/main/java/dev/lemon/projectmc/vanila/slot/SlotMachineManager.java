package dev.lemon.projectmc.vanila.slot;

import dev.lemon.projectmc.vanila.casino;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SlotMachineManager {
    private final casino plugin;
    private final Economy economy;

    private final Map<UUID, SlotSpinSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private final List<SlotSymbol> reelList = new ArrayList<>();
    private final List<SlotSymbol> animationReelList = new ArrayList<>(); // 시각화 전용 (고등급 편향)
    private boolean animationNoConsecutive;

    private int minBet, maxBet, betStep;
    private double twoMatchMultiplier;
    private int cooldownSeconds;
    private int leftFrames, middleDelay, rightDelay, frameInterval;
    private boolean jackpotAnnounce, jackpotAdminLog;
    private int jackpotMinBroadcastPayout;

    public static final int INV_SIZE = 45;
    public static final int LEFT_TOP = 11, LEFT_CENTER = 20, LEFT_BOTTOM = 29;
    public static final int MID_TOP = 13, MID_CENTER = 22, MID_BOTTOM = 31;
    public static final int RIGHT_TOP = 15, RIGHT_CENTER = 24, RIGHT_BOTTOM = 33;
    // 하단 버튼 슬롯 구성 (3감소, 스핀, 표시 종이(4번 유지), 3증가, 취소)
    public static final int BET_DEC_SLOT_BIG = 36;   // - step*100
    public static final int BET_DEC_SLOT_MID = 37;   // - step*10
    public static final int BET_DEC_SLOT_SMALL = 38; // - step
    public static final int SPIN_SLOT = 39;          // 스핀 버튼을 39로 이동
    public static final int BET_DISPLAY_SLOT = 4;    // 종이는 4번 슬롯 유지
    public static final int BET_INC_SLOT_SMALL = 41; // + step
    public static final int BET_INC_SLOT_MID = 42;   // + step*10
    public static final int BET_INC_SLOT_BIG = 43;   // + step*100
    public static final int EXIT_SLOT = 44;          // 취소/닫기

    private final SlotCommandExecutor commandExecutor;
    private final SlotGuiListener guiListener;

    public SlotMachineManager(casino plugin) {
        this.plugin = plugin;
        this.economy = plugin.getEconomy();
        reload();
        commandExecutor = new SlotCommandExecutor(this);
        guiListener = new SlotGuiListener(this);
    }

    public void reload() {
        plugin.reloadConfig();
        minBet = plugin.getConfig().getInt("slotmachine.bet.min", 10);
        maxBet = plugin.getConfig().getInt("slotmachine.bet.max", 10000);
        betStep = plugin.getConfig().getInt("slotmachine.bet.step", 10);
        twoMatchMultiplier = plugin.getConfig().getDouble("slotmachine.two-match-multiplier", 0.5);
        cooldownSeconds = plugin.getConfig().getInt("slotmachine.cooldown-seconds", 3);

        frameInterval = plugin.getConfig().getInt("slotmachine.animation.frame-interval-ticks", 2);
        leftFrames = plugin.getConfig().getInt("slotmachine.animation.left-frames", 22);
        middleDelay = plugin.getConfig().getInt("slotmachine.animation.middle-frames-delay", 6);
        rightDelay = plugin.getConfig().getInt("slotmachine.animation.right-frames-delay", 6);

        jackpotAnnounce = plugin.getConfig().getBoolean("slotmachine.jackpot.announce", true);
        jackpotAdminLog = plugin.getConfig().getBoolean("slotmachine.jackpot.admin-log", true);
        jackpotMinBroadcastPayout = plugin.getConfig().getInt("slotmachine.jackpot.minimum-broadcast-payout", 0);
        animationNoConsecutive = plugin.getConfig().getBoolean("slotmachine.animation.no-consecutive-center-duplicate", true);
        buildReelList();
    }

    private void buildReelList() {
        reelList.clear();
        double scale = 10.0; // 0.1% 정밀도
        for (SlotSymbol symbol : SlotSymbol.values()) {
            double p = plugin.getConfig().getDouble("slotmachine.probabilities." + symbol.name(), 0.0);
            int count = (int) Math.round(p * scale);
            for (int i = 0; i < count; i++) reelList.add(symbol);
        }
        if (reelList.isEmpty()) {
            for (SlotSymbol s : SlotSymbol.values()) for (int i = 0; i < 50; i++) reelList.add(s);
        }
        Collections.shuffle(reelList, new Random());
        buildAnimationReelList();
    }

    private void buildAnimationReelList() {
        animationReelList.clear();
        // config 기반 가중치 수집
        int total = 0;
        for (SlotSymbol s : SlotSymbol.values()) {
            int weight = plugin.getConfig().getInt("slotmachine.animation.visual-probabilities." + s.name(), -1);
            if (weight > 0) {
                total += weight;
                for (int i = 0; i < weight; i++) animationReelList.add(s);
            }
        }
        if (animationReelList.isEmpty()) {
            // fallback 기본값
            Map<SlotSymbol, Integer> weights = new LinkedHashMap<>();
            weights.put(SlotSymbol.DIAMOND, 40);
            weights.put(SlotSymbol.EMERALD, 25);
            weights.put(SlotSymbol.GOLDEN_APPLE, 15);
            weights.put(SlotSymbol.APPLE, 10);
            weights.put(SlotSymbol.COAL, 10);
            for (Map.Entry<SlotSymbol, Integer> e : weights.entrySet()) {
                for (int i = 0; i < e.getValue(); i++) animationReelList.add(e.getKey());
            }
        }
        Collections.shuffle(animationReelList, new Random());
    }

    public Inventory openGui(Player player) {
        SlotSpinSession existing = sessions.get(player.getUniqueId());
        int bet = existing != null ? existing.getBet() : Math.min(Math.max(minBet, betStep), maxBet);
        Inventory inv = Bukkit.createInventory(player, INV_SIZE, ChatColor.GOLD + "슬롯머신 " + ChatColor.GRAY + "- ₩" + fmt(bet));
        // 기본 회색 필러 세팅
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null, true);
        for (int i = 0; i < INV_SIZE; i++) inv.setItem(i, filler);
        // 중앙 포커스 라인(행 2: slots 18~26) 강조 색상 (연두색 유리판) - 실제 창 20,22,24 제외
        ItemStack focus = createItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, ChatColor.BLUE + "중앙", null, true);
        for (int slot = 18; slot <= 26; slot++) {
            if (slot == LEFT_CENTER || slot == MID_CENTER || slot == RIGHT_CENTER) continue;
            inv.setItem(slot, focus);
        }
        // 초기 릴: 다이아몬드 고정 (위/중앙/아래 모두 다이아)
        placeReelInitial(inv, LEFT_TOP, LEFT_CENTER, LEFT_BOTTOM);
        placeReelInitial(inv, MID_TOP, MID_CENTER, MID_BOTTOM);
        placeReelInitial(inv, RIGHT_TOP, RIGHT_CENTER, RIGHT_BOTTOM);
        // 증감 버튼(3개씩)과 스핀/표시/취소 배치
        int s = betStep;
        int s10 = s * 10;
        int s100 = s * 100;
        inv.setItem(BET_DEC_SLOT_BIG, createItem(Material.RED_CONCRETE, ChatColor.RED + "베팅 - " + fmt(s100), List.of(ChatColor.GRAY + "최소: " + fmt(minBet)), false));
        inv.setItem(BET_DEC_SLOT_MID, createItem(Material.RED_CONCRETE, ChatColor.RED + "베팅 - " + fmt(s10), List.of(ChatColor.GRAY + "최소: " + fmt(minBet)), false));
        inv.setItem(BET_DEC_SLOT_SMALL, createItem(Material.RED_CONCRETE, ChatColor.RED + "베팅 - " + fmt(s), List.of(ChatColor.GRAY + "최소: " + fmt(minBet)), false));
        inv.setItem(SPIN_SLOT, createItem(Material.EMERALD_BLOCK, ChatColor.GOLD + "스핀 시작", List.of(ChatColor.GRAY + "클릭하여 회전"), false));
        inv.setItem(BET_DISPLAY_SLOT, createItem(Material.PAPER, ChatColor.YELLOW + "베팅 금액", List.of(ChatColor.WHITE + "현재: ₩" + fmt(bet)), false));
        inv.setItem(BET_INC_SLOT_SMALL, createItem(Material.LIME_CONCRETE, ChatColor.GREEN + "베팅 + " + fmt(s), List.of(ChatColor.GRAY + "최대: " + fmt(maxBet)), false));
        inv.setItem(BET_INC_SLOT_MID, createItem(Material.LIME_CONCRETE, ChatColor.GREEN + "베팅 + " + fmt(s10), List.of(ChatColor.GRAY + "최대: " + fmt(maxBet)), false));
        inv.setItem(BET_INC_SLOT_BIG, createItem(Material.LIME_CONCRETE, ChatColor.GREEN + "베팅 + " + fmt(s100), List.of(ChatColor.GRAY + "최대: " + fmt(maxBet)), false));
        inv.setItem(EXIT_SLOT, createItem(Material.REDSTONE, ChatColor.RED + "취소", null, false));
        player.openInventory(inv);
        if (existing == null) {
            SlotSpinSession session = new SlotSpinSession(this, player.getUniqueId(), bet, inv);
            sessions.put(player.getUniqueId(), session);
        } else {
            existing.setInventory(inv);
            existing.setState(SlotSpinSession.State.IDLE);
        }
        return inv;
    }

    private void placeReelInitial(Inventory inv, int top, int center, int bottom) {
        ItemStack diamond = symbolItem(SlotSymbol.DIAMOND);
        inv.setItem(top, diamond);
        inv.setItem(center, diamond);
        inv.setItem(bottom, diamond);
    }

    public SlotSymbol randomSymbol(Random r) { return reelList.get(r.nextInt(reelList.size())); }
    public SlotSymbol randomAnimationSymbol(Random r) { return animationReelList.get(r.nextInt(animationReelList.size())); }
    public List<SlotSymbol> getAnimationReelList() { return animationReelList; }
    public boolean isAnimationNoConsecutive() { return animationNoConsecutive; }

    public ItemStack symbolItem(SlotSymbol symbol) { return createItem(symbol.getMaterial(), symbol.getDisplayName(), null, false); }

    public ItemStack createItem(Material mat, String name, List<String> lore, boolean hideNameIfEmpty) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isEmpty()) meta.setDisplayName(name); else if (!hideNameIfEmpty) meta.setDisplayName(" ");
            if (lore != null) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // delta(증가/감소량) 기반 베팅 조정
    public void adjustBet(Player player, int delta) {
        SlotSpinSession session = sessions.get(player.getUniqueId());
        if (session == null || session.getState() != SlotSpinSession.State.IDLE) return;
        int bet = session.getBet();
        long next = (long) bet + delta; // overflow 방지
        if (next < minBet) next = minBet;
        if (next > maxBet) next = maxBet;
        session.setBet((int) next);
        Inventory inv = session.getInventory();
        if (inv != null) {
            inv.setItem(BET_DISPLAY_SLOT, createItem(Material.PAPER, ChatColor.YELLOW + "베팅 금액", List.of(ChatColor.WHITE + "현재: ₩" + fmt(session.getBet())), false));
            // UI를 깔끔히 갱신하기 위해 재오픈
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> openGui(player));
        }
    }

    public void startSpin(Player player) {
        SlotSpinSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (session.getState() == SlotSpinSession.State.SPINNING) return;
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null) {
            long remain = (cooldownSeconds * 1000L - (now - last));
            if (remain > 0) {
                player.sendMessage(ChatColor.GRAY + "[슬롯머신] " + ChatColor.WHITE + "쿨다운 중입니다. " + (remain / 1000.0) + "s 남음.");
                return;
            }
        }
        int bet = session.getBet();
        if (economy.getBalance(player) < bet) {
            player.sendMessage(ChatColor.RED + "[슬롯머신] 잔액이 부족합니다. 현재 잔액: ₩" + fmt((long) economy.getBalance(player)));
            return;
        }
        economy.withdrawPlayer(player, bet);
        player.sendMessage(ChatColor.YELLOW + "[슬롯머신] " + ChatColor.WHITE + "스핀을 시작합니다... ₩" + fmt(bet) + "가 베팅되었습니다.");
        cooldowns.put(player.getUniqueId(), now);
        session.beginSpin(frameInterval, leftFrames, middleDelay, rightDelay);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    public void finishSpin(SlotSpinSession session, SlotSymbol left, SlotSymbol mid, SlotSymbol right) {
        Player player = Bukkit.getPlayer(session.getPlayerUUID());
        if (player == null) return;
        int bet = session.getBet();
        double payoutMultiplier = 0.0;
        boolean triple = left == mid && mid == right;
        boolean twoMatch = !triple && (left == mid || left == right || mid == right);
        if (triple) {
            payoutMultiplier = plugin.getConfig().getDouble("slotmachine.payouts." + left.name(), 0.0);
        } else if (twoMatch) {
            payoutMultiplier = twoMatchMultiplier;
        }
        long payout = Math.round(bet * payoutMultiplier);
        if (payout > 0) economy.depositPlayer(player, payout);
        if (triple) {
            if (payoutMultiplier >= 100 && jackpotAnnounce && payout >= jackpotMinBroadcastPayout) {
                Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "[카지노] " + ChatColor.AQUA + player.getName() + ChatColor.WHITE + "님이 슬롯머신에서 " + ChatColor.AQUA + "다이아몬드 3개" + ChatColor.WHITE + "로 ₩" + fmt(payout) + "를 획득했습니다!");
            }
            if (left == SlotSymbol.DIAMOND) player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f); else player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            player.sendMessage(ChatColor.GOLD + "[슬롯머신] " + ChatColor.WHITE + "축하합니다! " + left.getDisplayName() + ChatColor.WHITE + " 3개 일치 — 상금 ₩" + fmt(payout) + " 지급!");
            if (jackpotAdminLog && left == SlotSymbol.DIAMOND) plugin.getLogger().info("JACKPOT | player:" + player.getName() + " uuid:" + player.getUniqueId() + " bet:" + bet + " payout:" + payout + " result:DIAMONDx3");
        } else if (twoMatch) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
            player.sendMessage(ChatColor.YELLOW + "[슬롯머신] " + ChatColor.WHITE + "2개 일치! 환급 ₩" + fmt(payout) + " 지급.");
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            player.sendMessage(ChatColor.RED + "[슬롯머신] " + ChatColor.WHITE + "꽝! 다음에 다시 도전해보세요.");
        }
        session.setState(SlotSpinSession.State.IDLE);
    }

    public SlotCommandExecutor getCommandExecutor() { return commandExecutor; }
    public SlotGuiListener getGuiListener() { return guiListener; }

    public List<SlotSymbol> getReelList() { return reelList; }
    public casino getPlugin() { return plugin; }
    public SlotSpinSession getSession(UUID uuid) { return sessions.get(uuid); }

    public int getMinBet() { return minBet; }
    public int getMaxBet() { return maxBet; }
    public int getBetStep() { return betStep; }

    public boolean toggleJackpotAnnounce(boolean value) {
        jackpotAnnounce = value;
        plugin.getConfig().set("slotmachine.jackpot.announce", value);
        plugin.saveConfig();
        return jackpotAnnounce;
    }

    public void shutdown() {
        sessions.values().forEach(SlotSpinSession::cancelTaskSafe);
        sessions.clear();
    }

    private String fmt(long amount) {
        return String.format("%,d", amount);
    }
    private String fmt(int amount) { return String.format("%,d", amount); }
}
