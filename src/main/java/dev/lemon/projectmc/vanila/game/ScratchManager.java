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

import java.util.*;

public class ScratchManager implements Listener {
    private final casino plugin;
    private long ticketPrice;
    private List<Prize> prizes; // amount=0 => 꽝
    private List<Material> winSymbols;
    private List<Material> loseSymbols;
    private int matchWinPercent; // 3심볼 일치 승리 확률(%)

    private final Map<UUID, Session> sessions = new HashMap<>();
    private List<Prize> previewPrizes; // 패배 시 표시용 프리뷰 상금 테이블
    private int gameCount; // 설정된 게임 수(1~10)
    private static final int MAX_GAMES_PER_COLUMN = 8; // 최대 8개(1~4행 * 2/행)
    private static final int CONTROL_ROW_INDEX = 5; // 마지막 줄
    private static final int REVEAL_ALL_SLOT = CONTROL_ROW_INDEX*9 + 3; // 48
    private static final int RETRY_SLOT = CONTROL_ROW_INDEX*9 + 4;       // 49
    private static final int EXIT_SLOT = CONTROL_ROW_INDEX*9 + 5;        // 50
    private volatile boolean shuttingDown = false;

    public ScratchManager(casino plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown(){
        shuttingDown = true;
        sessions.clear();
    }

    public void reload() {
        ticketPrice = plugin.getConfig().getLong("scratch.ticket-price", 100);
        gameCount = Math.max(1, Math.min(MAX_GAMES_PER_COLUMN, plugin.getConfig().getInt("scratch.game-count", 6)));
        prizes = new ArrayList<>();
        for (Map<?, ?> map : plugin.getConfig().getMapList("scratch.prizes")) {
            try {
                Object amountObj = map.get("amount");
                long amount = (amountObj instanceof Number) ? ((Number) amountObj).longValue() : 0L;
                Object probObj = map.get("probability");
                int prob = (probObj instanceof Number) ? ((Number) probObj).intValue() : 0;
                if (amount > 0 && prob > 0) prizes.add(new Prize(amount, prob)); // 0 제거
            } catch (Exception ignored) {
            }
        }
        if (prizes.isEmpty()) {
            prizes.add(new Prize(1000, 50));
            prizes.add(new Prize(5000, 30));
            prizes.add(new Prize(20000, 15));
            prizes.add(new Prize(50000, 5));
        }
        winSymbols = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("scratch.win-symbols")) {
            try {
                winSymbols.add(Material.valueOf(s));
            } catch (Exception ignored) {
            }
        }
        if (winSymbols.isEmpty()) winSymbols = List.of(Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT);
        loseSymbols = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("scratch.lose-symbols")) {
            try {
                loseSymbols.add(Material.valueOf(s));
            } catch (Exception ignored) {
            }
        }
        if (loseSymbols.isEmpty()) loseSymbols = List.of(Material.COAL, Material.LAPIS_LAZULI, Material.REDSTONE);
        matchWinPercent = plugin.getConfig().getInt("scratch.match-win-percent", 20); // 기본 20%
        // preview-prizes 파싱 (패배 시 표시용). 없으면 winning prizes를 그대로 사용
        previewPrizes = new ArrayList<>();
        for (Map<?, ?> map : plugin.getConfig().getMapList("scratch.preview-prizes")) {
            try {
                Object amountObj = map.get("amount");
                long amount = (amountObj instanceof Number) ? ((Number) amountObj).longValue() : 0L;
                Object probObj = map.get("probability");
                int prob = (probObj instanceof Number) ? ((Number) probObj).intValue() : 0;
                if (amount > 0 && prob > 0) previewPrizes.add(new Prize(amount, prob));
            } catch (Exception ignored) {
            }
        }
        if (previewPrizes.isEmpty()) previewPrizes.addAll(prizes); // fallback
    }

    public void open(Player p) {
        double before = plugin.getEconomy().getBalance(p);
        if (!BetUtil.withdraw(plugin, p, ticketPrice)) {
            p.sendMessage(plugin.tr("scratch.insufficient"));
            return;
        }
        double after = plugin.getEconomy().getBalance(p);
        p.sendMessage(plugin.tr("scratch.spent", Map.of(
                "price", ticketPrice,
                "before", String.format("%,.0f", before),
                "after", String.format("%,.0f", after)
        )));
        Inventory inv = Bukkit.createInventory(p, 54, plugin.tr("scratch.gui_title", Map.of("price", ticketPrice))); // 6줄
        fill(inv, pane());
        // 맨 윗줄: 당첨금/심볼 종이 배치
        placeTopRowInfo(inv);
        // 가로 레이아웃: 한 줄에 최대 2게임, 그룹 간 1칸 공백(열 0..3 / gap 4 / 5..8)
        List<int[]> layout = new ArrayList<>();
        int games = Math.min(gameCount, MAX_GAMES_PER_COLUMN);
        for (int i=0;i<games;i++){
            int row = 1 + (i / 2);           // 행 1..4
            int colStart = (i % 2 == 0) ? 0 : 5; // 첫 게임 0..3, 두번째 5..8 (열4 공백)
            int sym1 = row*9 + (colStart + 0);
            int sym2 = row*9 + (colStart + 1);
            int sym3 = row*9 + (colStart + 2);
            int prize = row*9 + (colStart + 3);
            layout.add(new int[]{ sym1, sym2, sym3, prize });
        }
        Session s = new Session(inv);
        Random r = new Random();
        for (int[] slots : layout){
            Prize pr = rollPrize(r);
            boolean winPattern = r.nextInt(100) < matchWinPercent;
            Material winMat = winSymbols.get(r.nextInt(winSymbols.size()));
            Material[] symbols = new Material[3];
            if (winPattern) { symbols[0] = symbols[1] = symbols[2] = winMat; }
            else {
                for (int k = 0; k < 3; k++) symbols[k] = randomMixedSymbol(r);
                if (symbols[0] == symbols[1] && symbols[1] == symbols[2]) symbols[2] = randomMixedSymbol(r, symbols[2]);
            }
            long displayAmount = pr.amount;
            if (!winPattern) {
                Prize preview = rollPreviewPrize(r);
                if (preview != null) displayAmount = preview.amount;
            }
            SubGame g = new SubGame(slots, symbols, pr.amount, displayAmount, winPattern, winMat);
            s.games.add(g);
            for (int pos : slots) inv.setItem(pos, cover());
        }
        // 하단 설명 아이템 (슬롯 45)
        inv.setItem(45, buildInfoItem());
        // 컨트롤 버튼
        inv.setItem(REVEAL_ALL_SLOT, named(Material.GOLD_BLOCK, plugin.tr("scratch.reveal_all")));
        inv.setItem(RETRY_SLOT, pane()); // 완료 전 숨김
        inv.setItem(EXIT_SLOT, named(Material.BARRIER, plugin.tr("scratch.exit_button")));
        s.transitioning = true;
        p.openInventory(inv);
        Bukkit.getScheduler().runTask(plugin, () -> s.transitioning = false);
        sessions.put(p.getUniqueId(), s);
    }

    private ItemStack buildInfoItem(){
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.tr("scratch.info_prizes_title"));
        // 상금 목록 (최대 8개)
        int limit = Math.min(8, prizes.size());
        for (int i=0;i<limit;i++){
            Prize pr = prizes.get(i);
            lore.add(ChatColor.YELLOW + "- " + ChatColor.GOLD + "₩" + fmt(pr.amount));
        }
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + plugin.tr("scratch.info_symbols_title"));
        lore.add(ChatColor.GREEN + "승리 심볼: " + joinMaterials(winSymbols));
        lore.add(ChatColor.RED + "일반 심볼: " + joinMaterials(loseSymbols));
        ItemStack is = new ItemStack(Material.PAPER);
        ItemMeta im = is.getItemMeta();
        if (im!=null){
            im.setDisplayName(ChatColor.YELLOW + plugin.tr("scratch.info_rule"));
            im.setLore(lore);
            is.setItemMeta(im);
        }
        return is;
    }

    private String joinMaterials(List<Material> list){
        StringBuilder sb=new StringBuilder();
        int limit=Math.min(list.size(), 6);
        for (int i=0;i<limit;i++){
            if (i>0) sb.append(ChatColor.GRAY).append(", ");
            sb.append(ChatColor.WHITE).append(list.get(i).name());
        }
        if (list.size()>limit) sb.append(ChatColor.GRAY).append(" ...");
        return sb.toString();
    }

    private Material randomMixedSymbol(Random r) {
        return r.nextBoolean() ? winSymbols.get(r.nextInt(winSymbols.size())) : loseSymbols.get(r.nextInt(loseSymbols.size()));
    }

    private Material randomMixedSymbol(Random r, Material avoid) {
        Material m;
        int tries = 0;
        do {
            m = randomMixedSymbol(r);
        } while (m == avoid && tries++ < 5);
        return m;
    }

    private Prize rollPrize(Random r) {
        int total = prizes.stream().mapToInt(p -> p.prob).sum();
        int roll = r.nextInt(Math.max(1, total));
        int acc = 0;
        for (Prize p : prizes) {
            acc += p.prob;
            if (roll < acc) return p;
        }
        return prizes.get(prizes.size() - 1);
    }

    private Prize rollPreviewPrize(Random r) {
        int total = previewPrizes.stream().mapToInt(p -> p.prob).sum();
        if (total <= 0) return null;
        int roll = r.nextInt(total);
        int acc = 0;
        for (Prize p : previewPrizes) {
            acc += p.prob;
            if (roll < acc) return p;
        }
        return previewPrizes.get(previewPrizes.size() - 1);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().startsWith(ChatColor.GOLD + "즉석 복권")) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        int raw = e.getRawSlot();
        if (raw == EXIT_SLOT) {
            sessions.remove(p.getUniqueId());
            p.closeInventory();
            return;
        }
        if (raw == REVEAL_ALL_SLOT) {
            if (!s.done) {
                revealAll(p, s);
            }
            return;
        }
        if (raw == RETRY_SLOT) {
            if (!s.done) return;
            if (!BetUtil.withdraw(plugin, p, ticketPrice)) {
                p.sendMessage(plugin.tr("scratch.insufficient"));
                return;
            }
            s.transitioning = true;
            open(p);
            return;
        }
        if (s.done) return;
        for (SubGame g : s.games) {
            for (int idx = 0; idx < 4; idx++) {
                if (raw == g.slots[idx] && !g.revealed[idx]) {
                    g.revealed[idx] = true;
                    if (idx < 3) {
                        e.getView().getTopInventory().setItem(raw, new ItemStack(g.symbols[idx]));
                    } else {
                        e.getView().getTopInventory().setItem(raw, prizeItem(g.displayAmount));
                    }
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.6f);
                    if (allRevealed(s)) finish(p, s);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().startsWith(ChatColor.GOLD + "즉석 복권")) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!e.getView().getTitle().startsWith(ChatColor.GOLD + "즉석 복권")) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        if (shuttingDown) { sessions.remove(p.getUniqueId()); return; }
        if (!s.done && !s.transitioning)
            Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(s.inv));
        else
            sessions.remove(p.getUniqueId());
    }

    private void revealAll(Player p, Session s) {
        Inventory top = s.inv;
        if (top == null) return;
        for (SubGame g : s.games) {
            for (int idx = 0; idx < 4; idx++) {
                if (!g.revealed[idx]) {
                    g.revealed[idx] = true;
                    int slot = g.slots[idx];
                    if (idx < 3) top.setItem(slot, new ItemStack(g.symbols[idx]));
                    else top.setItem(slot, prizeItem(g.displayAmount));
                }
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
        if (allRevealed(s)) finish(p, s);
    }

    private boolean allRevealed(Session s) {
        for (SubGame g : s.games) {
            for (boolean b : g.revealed) if (!b) return false;
        }
        return true;
    }

    private void finish(Player p, Session s) {
        s.done = true;
        long gross = 0L;
        int winCount = 0;
        for (SubGame g : s.games) {
            boolean triple = g.symbols[0] == g.symbols[1] && g.symbols[1] == g.symbols[2];
            if (triple && g.winPattern) {
                gross += g.amount;
                winCount++;
            }
        }
        long net = gross > 0 ? BetUtil.applyTaxAndDeposit(plugin, p, gross) : 0;
        // 인벤토리 유지, 다시하기 버튼 노출
        if (s.inv != null) {
            s.inv.setItem(RETRY_SLOT, named(Material.EMERALD_BLOCK, plugin.tr("scratch.retry", Map.of("price", ticketPrice))));
        }
        if (gross > 0) {
            p.sendMessage(plugin.tr("scratch.win_summary", Map.of("wins", winCount, "gross", gross, "net", net)));
            LoggerBridge.info(plugin, "scratch", "win player=" + p.getName() + " gross=" + gross + " net=" + net + " wins=" + winCount);
        } else {
            p.sendMessage(plugin.tr("scratch.lose_summary"));
            LoggerBridge.info(plugin, "scratch", "lose player=" + p.getName());
        }
        // 세션은 onClose에서 정리
    }

    private ItemStack prizeItem(long amount) {
        return named(amount > 0 ? Material.EMERALD_BLOCK : Material.GOLD_BLOCK, (amount > 0 ? ChatColor.GREEN : ChatColor.YELLOW) + "₩" + fmt(amount));
    }

    private ItemStack cover() {
        return named(Material.GRAY_CONCRETE, ChatColor.DARK_GRAY + "긁어보기");
    }

    private ItemStack pane() {
        return named(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    private ItemStack named(Material m, String name) {
        ItemStack is = new ItemStack(m);
        ItemMeta im = is.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            is.setItemMeta(im);
        }
        return is;
    }

    private void fill(Inventory inv, ItemStack is) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, is);
    }

    private String fmt(long v) {
        return String.format("%,d", v);
    }

    private void placeTopRowInfo(Inventory inv){
        // 좌측 그룹 헤더: 0~2 심볼, 3 상금
        ItemStack sym = named(Material.PAPER, plugin.tr("scratch.header_symbol"));
        ItemStack prize = named(Material.PAPER, plugin.tr("scratch.header_prize"));
        inv.setItem(0, sym);
        inv.setItem(1, sym);
        inv.setItem(2, sym);
        inv.setItem(3, prize);
        // 가운데 4는 공백 유지
        // 우측 그룹 헤더(게임이 2개 이상인 경우): 5~7 심볼, 8 상금
        if (gameCount >= 2){
            inv.setItem(5, sym);
            inv.setItem(6, sym);
            inv.setItem(7, sym);
            inv.setItem(8, prize);
        } else {
            // 게임이 하나면 우측은 비워둠
            inv.setItem(5, pane());
            inv.setItem(6, pane());
            inv.setItem(7, pane());
            inv.setItem(8, pane());
        }
    }

    private String joinSymbolNamesShort(List<Material> list, int limit){
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, list.size());
        for (int i=0;i<n;i++){
            if (i>0) sb.append(", ");
            sb.append(list.get(i).name());
        }
        if (list.size() > limit) sb.append(" ...");
        return sb.toString();
    }

    static class Prize {
        final long amount;
        final int prob;

        Prize(long a, int p) {
            amount = a;
            prob = p;
        }
    }

    static class SubGame {
        final int[] slots;
        final Material[] symbols;
        final long amount;
        final long displayAmount;
        final boolean winPattern;
        final Material winMat;
        final boolean[] revealed = new boolean[4];

        SubGame(int[] s, Material[] sym, long amt, long disp, boolean win, Material wm) {
            slots = s;
            symbols = sym;
            amount = amt;
            displayAmount = disp;
            winPattern = win;
            winMat = wm;
        }
    }

    static class Session {
        final Inventory inv;
        final List<SubGame> games = new ArrayList<>();
        boolean done;
        boolean transitioning;

        Session(Inventory i) {
            inv = i;
        }
    }
}
