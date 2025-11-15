package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import dev.lemon.projectmc.vanila.util.LoggerBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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

public class HorseManager implements Listener {
    private final casino plugin; private int minBet,maxBet,trackLen; private List<Horse> horses;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private volatile boolean shuttingDown = false;
    // 모드/타이 설정
    private Mode mode = Mode.FATE; private TiePolicy tiePolicy = TiePolicy.RANDOM; private int predTickInterval = 2; private int visualSMin=1, visualSMax=3, visualChance=70;
    private boolean showChanceWinProb = true; // chance 모드 승률 표시 여부

    // 말 색상 팔레트 (인덱스 순회)
    private static final Material[] COLOR_MATS = new Material[]{
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.LIGHT_BLUE_WOOL, Material.YELLOW_WOOL,
            Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL, Material.CYAN_WOOL,
            Material.PURPLE_WOOL, Material.BLUE_WOOL, Material.BROWN_WOOL, Material.GREEN_WOOL
    };
    private static final int RETRY_SLOT = 53; // 인벤토리 마지막 칸
    private static final int EXIT_SLOT = 52;  // 재시작 옆 칸
    private static final int TRACK_START_COL = 1; // 트랙 시작 열
    private static final int TRACK_END_COL = 8;   // 트랙 끝 열(전체 사용)

    // 내부 비주얼 패턴(설정 파일 무관)
    private enum RacePattern { RANDOM, DOMINANT, COMEBACK, NECK_AND_NECK }

    // 가시화 설정 캐시
    private int visualPeriodTicks = 1;
    private int dominantEarlyBoost = 20, dominantLateBoost = 10, dominantOthersPenaltyEarly = 10, dominantOthersPenaltyLate = 5;
    private int comebackEarlyPenalty = 15, comebackLateBoost = 25, comebackOthersEarlyBoost = 10, comebackOthersLatePenalty = 10;
    private int neckBaseChance = 60, neckJitter = 5, neckMaxExtraSpeed = 2;
    private int randomJitter = 3;
    private int probDominant = 30, probComeback = 25, probNeck = 35, probRandom = 10;
    // chance 모드 시각 파라미터
    private int cfgVisualChance = 70, cfgVisualSMin = 1, cfgVisualSMax = 3;

    public HorseManager(casino plugin) {
        this.plugin = plugin;
        this.reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown(){
        shuttingDown = true;
        // Horse는 진행 중 타이머 식별자가 세션에 없으므로 별도 취소 핸들 없음
        sessions.clear();
    }

    public void reload() {
        minBet=plugin.getConfig().getInt("horse.min-bet",50);
        maxBet=plugin.getConfig().getInt("horse.max-bet",50000);
        trackLen=plugin.getConfig().getInt("horse.track-length",30);
        // 모드 및 타이 정책 읽기
        String m = plugin.getConfig().getString("horse.mode", "fate");
        mode = "chance".equalsIgnoreCase(m) ? Mode.PREDETERMINED : Mode.FATE;
        String tie = plugin.getConfig().getString("horse.tie-policy", "random");
        tiePolicy = switch (tie.toLowerCase(Locale.ROOT)){
            case "lowest-index" -> TiePolicy.LOWEST_INDEX;
            case "all" -> TiePolicy.ALL;
            default -> TiePolicy.RANDOM;
        };
        // horses 구성
        horses=new ArrayList<>();
        if (mode==Mode.FATE){
            List<Map<?,?>> list = plugin.getConfig().getMapList("horse.fate.horses");
            for (Map<?,?> mObj: list){
                Object nameObj = mObj.get("name"); String name = nameObj!=null? String.valueOf(nameObj) : "말";
                int smin = toInt(mObj.get("speed-min"), 1);
                int smax = toInt(mObj.get("speed-max"), 3);
                int chance = toInt(mObj.get("move-chance-percent"), 60);
                double payout = toDouble(mObj.get("payout-multiplier"), 2.0);
                horses.add(new Horse(name,smin,smax,chance,payout));
            }
            if (horses.isEmpty()) horses.add(new Horse("레드",1,3,70,2.0));
            // 추정 승률(간이)
            double totalScore=0.0; for (Horse h: horses){ h.score = (h.chance/100.0) * ((h.smin+h.smax)/2.0); totalScore += h.score; }
            for (Horse h: horses){ h.winProb = totalScore>0? (h.score/totalScore):0.0; }
            predTickInterval = 2; visualSMin=1; visualSMax=3; visualChance=70; // 내부 기본값
        } else { // CHANCE (사전 우승자 결정 모드)
            // 내부 기본값으로 비주얼 파라미터 고정 (config 미사용)
            predTickInterval = 2; visualSMin = 1; visualSMax = 3; visualChance = 70;
            showChanceWinProb = plugin.getConfig().getBoolean("horse.chance.show-win-probabilities", true);
            List<Map<?,?>> list = plugin.getConfig().getMapList("horse.chance.win-probabilities");
            double totalP=0.0; for (Map<?,?> mObj: list){ totalP += toDouble(mObj.get("probability"), 0.0); }
            if (totalP<=0) totalP=1.0;
            for (Map<?,?> mObj: list){
                Object nameObj = mObj.get("name"); String name = nameObj!=null? String.valueOf(nameObj) : "말";
                double prob = toDouble(mObj.get("probability"), 0.0) / totalP; // 정규화
                double payout = toDouble(mObj.get("payout-multiplier"), 2.0);
                Horse h = new Horse(name, visualSMin, visualSMax, visualChance, payout);
                h.winProb = prob; h.score = prob; horses.add(h);
            }
            if (horses.isEmpty()){
                Horse h = new Horse("레드",visualSMin,visualSMax,visualChance,2.0); h.winProb=1.0; horses.add(h);
            }
        }
        // trackLen는 config 값을 그대로 사용합니다. 마지막 칸 도달 즉시 우승은 렌더/판정 매핑으로 보장됩니다.

        // visual 설정 읽기 (chance 하위)
        ConfigurationSection v = plugin.getConfig().getConfigurationSection("horse.chance.visual");
        if (v != null) {
            visualPeriodTicks = v.getInt("period-ticks", visualPeriodTicks);
            ConfigurationSection probs = v.getConfigurationSection("pattern-probabilities");
            if (probs != null) {
                probDominant = probs.getInt("dominant", probDominant);
                probComeback = probs.getInt("comeback", probComeback);
                probNeck = probs.getInt("neck-and-neck", probNeck);
                probRandom = probs.getInt("random", probRandom);
            }
            ConfigurationSection dom = v.getConfigurationSection("dominant");
            if (dom != null) {
                dominantEarlyBoost = dom.getInt("early-boost", dominantEarlyBoost);
                dominantLateBoost = dom.getInt("late-boost", dominantLateBoost);
                dominantOthersPenaltyEarly = dom.getInt("others-penalty-early", dominantOthersPenaltyEarly);
                dominantOthersPenaltyLate = dom.getInt("others-penalty-late", dominantOthersPenaltyLate);
            }
            ConfigurationSection cb = v.getConfigurationSection("comeback");
            if (cb != null) {
                comebackEarlyPenalty = cb.getInt("early-penalty", comebackEarlyPenalty);
                comebackLateBoost = cb.getInt("late-boost", comebackLateBoost);
                comebackOthersEarlyBoost = cb.getInt("others-early-boost", comebackOthersEarlyBoost);
                comebackOthersLatePenalty = cb.getInt("others-late-penalty", comebackOthersLatePenalty);
            }
            ConfigurationSection nk = v.getConfigurationSection("neck-and-neck");
            if (nk != null) {
                neckBaseChance = nk.getInt("base-chance", neckBaseChance);
                neckJitter = nk.getInt("jitter", neckJitter);
                neckMaxExtraSpeed = nk.getInt("max-extra-speed", neckMaxExtraSpeed);
            }
            ConfigurationSection rnd = v.getConfigurationSection("random");
            if (rnd != null) {
                randomJitter = rnd.getInt("jitter", randomJitter);
            }
            ConfigurationSection cm = v.getConfigurationSection("chance-mode");
            if (cm != null) {
                cfgVisualChance = cm.getInt("visual-chance", cfgVisualChance);
                cfgVisualSMin = cm.getInt("visual-speed-min", cfgVisualSMin);
                cfgVisualSMax = cm.getInt("visual-speed-max", cfgVisualSMax);
            }
        }
        // chance 모드에서 사용할 시각 파라미터 적용
        if (mode == Mode.PREDETERMINED) {
            visualChance = cfgVisualChance;
            visualSMin = cfgVisualSMin;
            visualSMax = cfgVisualSMax;
        }
    }

    public void open(Player p, long bet){
        if (bet<minBet || bet>maxBet){ p.sendMessage(plugin.tr("common.bet_range", Map.of("min", minBet, "max", maxBet))); return; }
        if (!BetUtil.withdraw(plugin,p,bet)){ p.sendMessage(plugin.tr("common.insufficient")); return; }
        Inventory inv=Bukkit.createInventory(p, 54, plugin.tr("horse.gui_title", Map.of("bet", bet)));
        p.sendMessage(plugin.tr("horse.info_header"));
        int displayCount = Math.min(5, horses.size());
        for (int i=0;i<displayCount;i++){
            Horse h=horses.get(i);
            if (mode==Mode.PREDETERMINED){
                if (showChanceWinProb){
                    p.sendMessage(plugin.tr("horse.info_line_chance", Map.of("name", h.name, "payout", h.payout, "winProb", String.format(Locale.KOREA, "%.1f", h.winProb*100.0))));
                } else {
                    p.sendMessage(plugin.tr("horse.info_line_chance_simple", Map.of("name", h.name, "payout", h.payout)));
                }
            } else {
                p.sendMessage(plugin.tr("horse.info_line", Map.of("name", h.name, "chance", h.chance, "smin", h.smin, "smax", h.smax, "payout", h.payout, "winProb", String.format(Locale.KOREA, "%.1f", h.winProb*100.0))));
            }
        }
        // 트랙 초기화
        for (int row=0; row<displayCount; row++){
            Horse h=horses.get(row);
            inv.setItem(row*9, horseBaseItem(h,false));
            for (int col=TRACK_START_COL; col<=TRACK_END_COL; col++) inv.setItem(row*9+col, pane());
        }
        p.openInventory(inv);
        sessions.put(p.getUniqueId(), new Session(bet, inv, displayCount));
    }

    // 말 기본 정보 아이템: 항상 흰색 양털
    private ItemStack horseBaseItem(Horse h, boolean selected){
        ItemStack is = new ItemStack(Material.WHITE_WOOL);
        ItemMeta im = is.getItemMeta();
        if (im!=null){
            String nameColor = selected? ChatColor.GOLD.toString(): ChatColor.WHITE.toString();
            im.setDisplayName(nameColor + h.name + (selected? ChatColor.GRAY+" (선택)":""));
            List<String> lore = new ArrayList<>();
            if (mode!=Mode.PREDETERMINED){
                lore.add(ChatColor.GRAY+"이동확률: "+h.chance+"%");
                lore.add(ChatColor.GRAY+"속도범위: "+h.smin+"~"+h.smax);
            }
            lore.add(ChatColor.AQUA+"배당: x"+h.payout);
            if (!(mode==Mode.PREDETERMINED && !showChanceWinProb)){
                lore.add(ChatColor.GREEN+"추정승률: "+fmtPct(h.winProb));
            }
            im.setLore(lore);
            is.setItemMeta(im);
        }
        return is;
    }
    // 진행 마커용 말 색상 결정 (이름 기반 매핑)
    private Material horseColor(Horse h, int index){
        String name = h.name.toLowerCase(Locale.ROOT);
        if (name.contains("레드") || name.contains("red")) return Material.RED_WOOL;
        if (name.contains("블루") || name.contains("blue")) return Material.BLUE_WOOL;
        if (name.contains("그린") || name.contains("green")) return Material.GREEN_WOOL;
        if (name.contains("블랙") || name.contains("black")) return Material.BLACK_WOOL;
        if (name.contains("옐로") || name.contains("yellow")) return Material.YELLOW_WOOL;
        if (name.contains("핑크") || name.contains("pink")) return Material.PINK_WOOL;
        if (name.contains("퍼플") || name.contains("purple")) return Material.PURPLE_WOOL;
        if (name.contains("브라운") || name.contains("brown")) return Material.BROWN_WOOL;
        if (name.contains("라임") || name.contains("lime")) return Material.LIME_WOOL;
        if (name.contains("오렌지") || name.contains("orange")) return Material.ORANGE_WOOL;
        if (name.contains("시안") || name.contains("cyan")) return Material.CYAN_WOOL;
        if (name.contains("회색") || name.contains("gray") || name.contains("grey")) return Material.GRAY_WOOL;
        // 매핑 실패 시 기존 팔레트 인덱스 활용
        return COLOR_MATS[index % COLOR_MATS.length];
    }
    // 진행 마커: 말 고유 이름 색상 양털
    private ItemStack progressMarker(int horseIndex){
        Horse h = horses.get(horseIndex);
        Material mat = horseColor(h, horseIndex);
        ItemStack is = new ItemStack(mat);
        ItemMeta im = is.getItemMeta();
        if (im!=null){ im.setDisplayName(ChatColor.WHITE+"▶"); is.setItemMeta(im); }
        return is;
    }

    @EventHandler public void onClick(InventoryClickEvent e){
        if (!(e.getWhoClicked() instanceof Player p)) return; String title=e.getView().getTitle(); if (!title.startsWith(ChatColor.GOLD+"경마")) return; e.setCancelled(true);
        Session s=sessions.get(p.getUniqueId()); if (s==null) return;
        int slot=e.getRawSlot(); if (slot<0 || slot>=54) return;
        if (s.state==2){
            if (slot==RETRY_SLOT){
                if (!BetUtil.withdraw(plugin,p,s.bet)){ p.sendMessage(ChatColor.RED+"[Horse] 잔액 부족 – 재시작 실패"); return; }
                resetSession(s); p.sendMessage(ChatColor.YELLOW+"[Horse] 재시작! 말 선택 후 경주 진행"); return; }
            if (slot==EXIT_SLOT){ p.closeInventory(); sessions.remove(p.getUniqueId()); return; }
            return;
        }
        if (s.state!=0){ return; }
        int row=slot/9; if (row>=s.pos.length) return; if (slot%9!=0) return; // 이름 칸(좌측)만 선택 허용
        s.pick=row; s.state=1;
        int base=row*9; Horse h=horses.get(row); e.getView().getTopInventory().setItem(base, horseBaseItem(h,true));
        start(p,s);
    }
    @EventHandler public void onDrag(InventoryDragEvent e){ String title=e.getView().getTitle(); if (title.startsWith(ChatColor.GOLD+"경마")) e.setCancelled(true);}
    @EventHandler public void onClose(InventoryCloseEvent e){ if (!(e.getPlayer() instanceof Player p)) return; String title=e.getView().getTitle(); if (!title.startsWith(ChatColor.GOLD+"경마")) return; if (shuttingDown) { sessions.remove(p.getUniqueId()); return; } Session s=sessions.get(p.getUniqueId()); if (s!=null && s.state!=2) Bukkit.getScheduler().runTask(plugin,()->p.openInventory(s.inv)); }

    private void start(Player p, Session s) {
        if (this.mode == Mode.PREDETERMINED) {
            s.predWinner = this.pickWeightedWinner();
            s.pattern = this.pickPattern();
        } else {
            s.pattern = RacePattern.RANDOM; // FATE 모드도 비주얼만 가볍게 적용
        }
        long period = Math.max(1L, this.visualPeriodTicks);
        // 디버그 로그: 현재 설정 확인용
        LoggerBridge.info(plugin, "horse", "race start mode=" + this.mode + " pattern=" + s.pattern + " period=" + period + " horses=" + s.pos.length + " bet=" + s.bet);
        Bukkit.getScheduler().runTaskTimer(this.plugin, task -> {
            s.ticks++;
            this.step(s); // 매 틱마다 스텝 진행
            this.render(s);
            int winIdx = this.winner(s);
            if (winIdx >= 0) {
                task.cancel();
                this.finish(p, s, winIdx);
            }
        }, 0L, period);
    }

    private RacePattern pickPattern() {
        int sum = Math.max(1, probDominant + probComeback + probNeck + probRandom);
        int r = new Random().nextInt(sum);
        if (r < probDominant) return RacePattern.DOMINANT;
        r -= probDominant;
        if (r < probComeback) return RacePattern.COMEBACK;
        r -= probComeback;
        if (r < probNeck) return RacePattern.NECK_AND_NECK;
        return RacePattern.RANDOM;
    }

    private void step(Session s) {
        Random r = new Random();
        double avgPos = 0.0;
        for (int v : s.pos) avgPos += v;
        avgPos /= Math.max(1, s.pos.length);
        double prog = avgPos / Math.max(1, this.trackLen);

        for (int i = 0; i < s.pos.length; i++) {
            Horse h = this.horses.get(i);
            int baseChance = (this.mode == Mode.PREDETERMINED) ? this.visualChance : h.chance;
            int smin = (this.mode == Mode.PREDETERMINED) ? this.visualSMin : h.smin;
            int smax = (this.mode == Mode.PREDETERMINED) ? this.visualSMax : h.smax;

            switch (s.pattern) {
                case DOMINANT -> {
                    if (i == s.predWinner) {
                        baseChance = clamp(baseChance + boostByPhase(prog, dominantEarlyBoost, dominantLateBoost));
                        smax = Math.max(smin, smax + 1);
                    } else {
                        baseChance = clamp(baseChance - boostByPhase(prog, dominantOthersPenaltyEarly, dominantOthersPenaltyLate));
                    }
                }
                case COMEBACK -> {
                    if (i == s.predWinner) {
                        if (prog < 0.5) baseChance = clamp(baseChance - comebackEarlyPenalty);
                        else baseChance = clamp(baseChance + comebackLateBoost);
                    } else {
                        if (prog < 0.5) baseChance = clamp(baseChance + comebackOthersEarlyBoost);
                        else baseChance = clamp(baseChance - comebackOthersLatePenalty);
                    }
                }
                case NECK_AND_NECK -> {
                    baseChance = clamp(neckBaseChance + (r.nextInt(neckJitter * 2 + 1) - neckJitter));
                    smin = Math.max(1, smin);
                    smax = Math.max(smin, Math.min(smin + neckMaxExtraSpeed, smax));
                }
                case RANDOM -> {
                    baseChance = clamp(baseChance + (r.nextInt(randomJitter * 2 + 1) - randomJitter));
                }
            }

            if (r.nextInt(100) >= baseChance) continue;
            int step = smin + r.nextInt(Math.max(1, smax - smin + 1));

            if (this.mode == Mode.PREDETERMINED && s.predWinner != i) {
                int next = Math.min(this.trackLen, s.pos[i] + step);
                int winPos = s.pos[s.predWinner];
                int finishGuard = this.trackLen - 0; // 우승자는 마지막 칸 즉시 승리 보장
                // 비우승 말이 우승자보다 먼저 결승선 도달하는 것 방지
                if (next >= this.trackLen && winPos < finishGuard) {
                    next = Math.min(this.trackLen - 1, s.pos[i] + Math.max(1, step / 2));
                }
                s.pos[i] = next;
            } else {
                s.pos[i] = Math.min(this.trackLen, s.pos[i] + step);
            }
        }
    }

    private int boostByPhase(double prog, int early, int late) {
        // 진행률에 따라 보정량 변형 (초반/후반)
        double t = Math.max(0.0, Math.min(1.0, prog));
        int b = (int) Math.round(early * (1.0 - t) + late * t);
        return b;
    }

    private int clamp(int chance) { return Math.max(1, Math.min(95, chance)); }

    private void render(Session s) {
        for (int i = 0; i < s.pos.length; i++) {
            int base = i * 9;
            Horse h = this.horses.get(i);
            s.inv.setItem(base, this.horseBaseItem(h, s.pick == i));
            for (int col = TRACK_START_COL; col <= TRACK_END_COL; col++) {
                s.inv.setItem(base + col, this.pane());
            }
            int trackCols = TRACK_END_COL - TRACK_START_COL + 1;
            int col = TRACK_START_COL + s.pos[i] * (trackCols - 1) / Math.max(1, this.trackLen);
            s.inv.setItem(base + col, this.progressMarker(i));
        }
    }

    private int winner(Session s) {
        // 결승선(마지막 칸) 도달 즉시 우승
        int trackCols = TRACK_END_COL - TRACK_START_COL + 1;
        for (int i = 0; i < s.pos.length; i++) {
            int col = TRACK_START_COL + s.pos[i] * (trackCols - 1) / Math.max(1, this.trackLen);
            if (col >= TRACK_END_COL) {
                if (this.mode == Mode.PREDETERMINED) {
                    // 예정보다 빠른 비우승자 결승선 통과 방지 (보정)
                    if (s.predWinner >= 0 && i != s.predWinner) continue;
                }
                return i;
            }
        }
        if (this.mode == Mode.PREDETERMINED) {
            if (s.predWinner >= 0 && s.pos[s.predWinner] >= this.trackLen) return s.predWinner;
            return -1;
        }
        // FATE 모드 동시 결승 시 정책 적용
        java.util.List<Integer> winners = new java.util.ArrayList<>();
        for (int i = 0; i < s.pos.length; i++) {
            int col = TRACK_START_COL + s.pos[i] * (trackCols - 1) / Math.max(1, this.trackLen);
            if (col >= TRACK_END_COL) winners.add(i);
        }
        s.tieWinners = winners; // 세션에 저장하여 finish에서 활용
        if (winners.isEmpty()) return -1;
        if (winners.size() == 1) return winners.get(0);
        return switch (this.tiePolicy) {
            case LOWEST_INDEX -> java.util.Collections.min(winners);
            case ALL -> winners.get(0);
            case RANDOM -> winners.get(new java.util.Random().nextInt(winners.size()));
        };
    }

    private void finish(Player p, Session s, int winIdx){
        boolean win;
        if (this.mode==Mode.FATE && this.tiePolicy==TiePolicy.ALL && s.tieWinners!=null && !s.tieWinners.isEmpty()){
            win = s.tieWinners.contains(s.pick);
        } else {
            win = (s.pick==winIdx);
        }
        long net=0L; Horse hWin=horses.get(winIdx);
        if (win){ long gross=Math.round(s.bet * hWin.payout); net=BetUtil.applyTaxAndDeposit(plugin,p,gross); }
        if (win){ p.sendMessage(plugin.tr("horse.win", Map.of("net", net))); LoggerBridge.info(plugin,"horse","win player="+p.getName()+" bet="+s.bet+" net="+net); }
        else { p.sendMessage(plugin.tr("horse.lose", Map.of("winner", hWin.name))); LoggerBridge.info(plugin,"horse","lose player="+p.getName()+" bet="+s.bet); }
        p.sendMessage(plugin.tr("horse.winner_line", Map.of("winner", hWin.name, "winProb", String.format(Locale.KOREA, "%.1f", hWin.winProb*100.0), "payout", hWin.payout)));
        s.state=2;
        // 우승 라인 플래시
        flashWinnerRow(s, winIdx);
        s.inv.setItem(EXIT_SLOT, named(Material.BARRIER, plugin.tr("horse.exit_button")));
        s.inv.setItem(RETRY_SLOT, named(Material.EMERALD_BLOCK, plugin.tr("horse.retry_button", Map.of("bet", s.bet))));
        int base = winIdx*9; s.inv.setItem(base, horseBaseItem(hWin, s.pick==winIdx));
    }

    private void flashWinnerRow(Session s, int row){
        final int cycles = 8; final int[] count = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task->{
            boolean on = (count[0] % 2)==0;
            for (int col=TRACK_START_COL; col<=TRACK_END_COL; col++) s.inv.setItem(row*9+col, named(on? Material.LIME_STAINED_GLASS_PANE: Material.GRAY_STAINED_GLASS_PANE, " "));
            if (++count[0] >= cycles) task.cancel();
        }, 0L, 2L);
    }

    private void resetSession(Session s){
        Arrays.fill(s.pos,0); s.ticks=0; s.pick=-1; s.state=0; s.predWinner=-1; s.tieWinners=null;
        // 트랙 초기화
        for (int row=0; row<s.pos.length; row++){
            Horse h=horses.get(row); s.inv.setItem(row*9, horseBaseItem(h,false));
            for (int col=TRACK_START_COL; col<=TRACK_END_COL; col++) s.inv.setItem(row*9+col, pane());
        }
        s.inv.setItem(EXIT_SLOT, pane()); s.inv.setItem(RETRY_SLOT, pane());
    }

    static class Session{ final long bet; final Inventory inv; final int[] pos; int state; int ticks; int pick=-1; int predWinner=-1; RacePattern pattern = RacePattern.RANDOM; java.util.List<Integer> tieWinners; Session(long b, Inventory i, int horseCount){ bet=b;inv=i;pos=new int[horseCount]; }}

    // ===== 복구된 내부 타입 및 유틸 메서드 시작 =====
    // 말 정보 객체
    static class Horse{
        final String name; // 표시 이름
        final int smin;    // (FATE) 최소 이동 속도
        final int smax;    // (FATE) 최대 이동 속도
        final int chance;  // (FATE) 이동 시도 확률 %
        final double payout; // 배당倍率
        double winProb;    // 계산/지정된 승률 (0~1)
        double score;      // 내부 계산 점수
        Horse(String name,int smin,int smax,int chance,double payout){
            this.name=name; this.smin=smin; this.smax=smax; this.chance=chance; this.payout=payout;
        }
    }
    // 경주 모드: FATE(난수 기반), PREDETERMINED(사전 우승자 확정 후 연출)
    enum Mode { FATE, PREDETERMINED }
    // 동시 결승 처리 정책
    enum TiePolicy { LOWEST_INDEX, ALL, RANDOM }

    // 안전한 Object->int 변환
    private int toInt(Object o, int def){ if (o==null) return def; if (o instanceof Number n) return n.intValue(); try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e){ return def; } }
    // 안전한 Object->double 변환
    private double toDouble(Object o, double def){ if (o==null) return def; if (o instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e){ return def; } }
    // 퍼센트 포맷 (xx.x%)
    private String fmtPct(double v){ return String.format(Locale.KOREA, "%.1f%%", v*100.0); }
    // 회색 유리판 기본 아이템
    private ItemStack pane(){ return named(Material.GRAY_STAINED_GLASS_PANE, " "); }
    // 이름 지정 단축 유틸
    private ItemStack named(Material mat, String name){ ItemStack is=new ItemStack(mat); ItemMeta im=is.getItemMeta(); if (im!=null){ im.setDisplayName(name); is.setItemMeta(im);} return is; }

    // 가중치 기반 승자 선택 (chance 모드 전용)
    private int pickWeightedWinner(){
        if (horses.isEmpty()) return -1; double sum=0.0; for (Horse h: horses) sum += (h.winProb>0? h.winProb:0); if (sum<=0){ return new Random().nextInt(horses.size()); }
        double r=Math.random()*sum; double acc=0.0; for (int i=0;i<horses.size();i++){ Horse h=horses.get(i); acc += (h.winProb>0? h.winProb:0); if (r<=acc) return i; } return horses.size()-1;
    }
    // ===== 복구된 내부 타입 및 유틸 메서드 끝 =====
}
