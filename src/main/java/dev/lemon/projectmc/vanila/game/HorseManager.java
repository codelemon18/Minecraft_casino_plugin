package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import dev.lemon.projectmc.vanila.util.LoggerBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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

    public HorseManager(casino plugin){ this.plugin=plugin; reload(); Bukkit.getPluginManager().registerEvents(this, plugin);}
    public void reload(){
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
            predTickInterval = 2; visualSMin=1; visualSMax=3; visualChance=70; // 사용 안함
        } else { // CHANCE
            // 원본 로직 복구: config 의 visual.* 파라미터를 사용
            predTickInterval = plugin.getConfig().getInt("horse.chance.visual.tick-interval", 2);
            visualSMin = plugin.getConfig().getInt("horse.chance.visual.speed-min", 1);
            visualSMax = plugin.getConfig().getInt("horse.chance.visual.speed-max", 3);
            visualChance = plugin.getConfig().getInt("horse.chance.visual.move-chance-percent", 70);
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
    @EventHandler public void onClose(InventoryCloseEvent e){ if (!(e.getPlayer() instanceof Player p)) return; String title=e.getView().getTitle(); if (!title.startsWith(ChatColor.GOLD+"경마")) return; Session s=sessions.get(p.getUniqueId()); if (s!=null && s.state!=2) Bukkit.getScheduler().runTask(plugin,()->p.openInventory(s.inv)); }

    private void start(Player p, Session s){
        // chance 모드라면 사전 승자 결정 + 시각 파라미터 초기화
        if (mode==Mode.PREDETERMINED){ s.predWinner = pickWeightedWinner(); initChanceVisuals(s); }
        long period = (mode==Mode.PREDETERMINED)? predTickInterval : 2L;
        Bukkit.getScheduler().runTaskTimer(plugin, task->{
            s.ticks++;
            // chance: 매 틱, fate: 2틱에 한 번 이동
            if (mode==Mode.PREDETERMINED || s.ticks%2==0) step(s);
            render(s);
            int winIdx = winner(s);
            if (winIdx>=0){ task.cancel(); finish(p,s,winIdx);}
        }, 0L, period);
    }

    // chance 모드 시각 파라미터 초기화
    private void initChanceVisuals(Session s){
        s.totalTicks = Math.max(50, trackLen * 3);
        s.targetFrac = new double[s.pos.length];
        s.speedFactor = new double[s.pos.length];
        s.prog = new double[s.pos.length];
        s.tempo = new double[s.pos.length];
        s.noiseAmp = new double[s.pos.length];
        s.noisePhase = new double[s.pos.length];
        Random r = new Random(System.nanoTime() ^ s.hashCode());
        for (int i=0;i<s.pos.length;i++){
            s.prog[i] = 0.0;
            if (i==s.predWinner){
                s.targetFrac[i]=1.0; s.speedFactor[i]=1.0; s.tempo[i]=1.0; s.noiseAmp[i]=0.04; s.noisePhase[i]=r.nextDouble()*Math.PI*2;
            } else {
                double tf = 0.70 + r.nextDouble()*0.25; if (tf>0.92) tf = 0.90 + r.nextDouble()*0.02;
                s.targetFrac[i] = tf; s.speedFactor[i] = 0.80 + r.nextDouble()*0.18;
                s.tempo[i] = 0.8 + r.nextDouble()*0.6; s.noiseAmp[i] = 0.02 + r.nextDouble()*0.05; s.noisePhase[i] = r.nextDouble()*Math.PI*2;
            }
        }
    }

    private double easeOutCubic(double t){ double x=Math.max(0.0, Math.min(1.0,t)); return 1 - Math.pow(1 - x, 3); }
    private double easeInOutQuad(double t){ double x=Math.max(0.0, Math.min(1.0,t)); return x<0.5? 2*x*x : 1 - Math.pow(-2*x+2,2)/2; }

    private void step(Session s){
        if (mode==Mode.PREDETERMINED){
            double base = (double)s.ticks / Math.max(1,s.totalTicks);
            for (int i=0;i<s.pos.length;i++){
                double ease = (i==s.predWinner)? easeOutCubic(base) : easeInOutQuad(base);
                double rhythmic = 1.0 + (s.noiseAmp!=null? s.noiseAmp[i]:0.0) * Math.sin((base*2*Math.PI*(s.tempo!=null? s.tempo[i]:1.0)) + (s.noisePhase!=null? s.noisePhase[i]:0.0));
                double desired = ease * (s.speedFactor!=null? s.speedFactor[i]:1.0) * rhythmic;
                double cap = (s.targetFrac!=null? s.targetFrac[i]:1.0); if (i==s.predWinner) cap = 1.0;
                double next = Math.max(s.prog!=null? s.prog[i]:0.0, Math.min(cap, desired));
                if (s.prog!=null) s.prog[i] = next;
                int pos = (int)Math.round(trackLen * next); s.pos[i] = Math.min(trackLen, Math.max(0, pos));
            }
            return;
        }
        // fate: 기존 난수 이동
        Random r=new Random();
        for (int i=0;i<s.pos.length;i++){
            Horse h=horses.get(i);
            if (r.nextInt(100) < h.chance){
                int step = h.smin + r.nextInt(Math.max(1,h.smax-h.smin+1));
                s.pos[i] = Math.min(trackLen, s.pos[i] + step);
            }
        }
    }

    private void finish(Player p, Session s, int winIdx){
        boolean win = (s.pick==winIdx); long net=0L; Horse hWin=horses.get(winIdx);
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
        Arrays.fill(s.pos,0); s.ticks=0; s.pick=-1; s.state=0; s.predWinner=-1;
        s.totalTicks=0; s.targetFrac=null; s.speedFactor=null; s.prog=null; s.tempo=null; s.noiseAmp=null; s.noisePhase=null;
        for (int row=0; row<s.pos.length; row++){
            Horse h=horses.get(row); s.inv.setItem(row*9, horseBaseItem(h,false));
            for (int col=TRACK_START_COL; col<=TRACK_END_COL; col++) s.inv.setItem(row*9+col, pane());
        }
        s.inv.setItem(EXIT_SLOT, pane()); s.inv.setItem(RETRY_SLOT, pane());
    }

    static class Session{ final long bet; final Inventory inv; final int[] pos; int state; int ticks; int pick=-1; int predWinner=-1; int totalTicks; double[] targetFrac; double[] speedFactor; double[] prog; double[] tempo; double[] noiseAmp; double[] noisePhase; Session(long b, Inventory i, int horseCount){ bet=b;inv=i;pos=new int[horseCount]; }}

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

    // 현재 진행 상태를 인벤토리에 렌더링
    private void render(Session s){
        Inventory inv = s.inv; if (inv==null) return; int rows = s.pos.length;
        for (int row=0; row<rows; row++){
            // 트랙 칸 초기화 (마커 지우기)
            for (int col=TRACK_START_COL; col<=TRACK_END_COL; col++){
                // 이미 마커면 지우고 기본 pane으로
                ItemStack cur = inv.getItem(row*9+col);
                if (cur==null || cur.getType()!=Material.GRAY_STAINED_GLASS_PANE && cur.getType()!=Material.LIME_STAINED_GLASS_PANE) {
                    // 아무것도 아니면 덮어쓰기
                }
                inv.setItem(row*9+col, pane());
            }
            // 진행 위치 -> 칼럼 매핑
            int pos = s.pos[row];
            int col = TRACK_START_COL + (int)Math.round((double)pos / Math.max(1, trackLen) * (TRACK_END_COL-TRACK_START_COL));
            if (col>TRACK_END_COL) col = TRACK_END_COL;
            inv.setItem(row*9+col, progressMarker(row));
        }
    }

    // 승자 결정 (트랙 끝 도달). 타이 정책 적용.
    private int winner(Session s){
        List<Integer> reached = new ArrayList<>();
        for (int i=0;i<s.pos.length;i++){ if (s.pos[i] >= trackLen) reached.add(i); }
        if (reached.isEmpty()) return -1;
        if (reached.size()==1) return reached.get(0);
        return switch (tiePolicy){
            case LOWEST_INDEX -> Collections.min(reached);
            case RANDOM -> reached.get(new Random().nextInt(reached.size()));
            case ALL -> reached.get(new Random().nextInt(reached.size())); // 단일 인덱스로 처리
        };
    }
    // ===== 복구된 내부 타입 및 유틸 메서드 끝 =====
}
