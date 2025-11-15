package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import dev.lemon.projectmc.vanila.util.LoggerBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.*;

public class LotteryManager {
    private final casino plugin;
    private int ticketPrice, intervalMin;
    private long poolBase; // 기존 baseJackpot -> pool
    private long jackpotPool;
    private long lastDraw;
    // 티어 퍼센트(풀 대비 %) 및 롤오버 옵션 (백워드 호환)
    private int pFirst, pSecond, pThird, pFourth, pFifth;
    private boolean rolloverUnpaid;

    private int maxTicketsPerPlayer; // 플레이어별 최대 티켓 수

    // 일반화된 규칙
    private int numberMax = 45; // 1..N
    private int pickCount = 6;  // 티켓 숫자 개수
    private boolean bonusEnabled = true;
    private List<TierDef> tierDefs = new ArrayList<>();

    private final List<TicketEntry> tickets = new ArrayList<>();

    public LotteryManager(casino plugin){ this.plugin=plugin; reload(); schedule(); }

    public void reload(){
        ticketPrice = plugin.getConfig().getInt("lottery.ticket-price", 500);
        intervalMin = plugin.getConfig().getInt("lottery.draw-interval-minutes", 60);
        poolBase = plugin.getConfig().getLong("lottery.pool", 100000);
        // 백워드 호환 비율
        pFirst  = plugin.getConfig().getInt("lottery.tiers.first", 75);
        pSecond = plugin.getConfig().getInt("lottery.tiers.second", 12);
        pThird  = plugin.getConfig().getInt("lottery.tiers.third", 7);
        pFourth = plugin.getConfig().getInt("lottery.tiers.fourth", 3);
        pFifth  = plugin.getConfig().getInt("lottery.tiers.fifth", 3);
        rolloverUnpaid = plugin.getConfig().getBoolean("lottery.rollover-unpaid", true);
        maxTicketsPerPlayer = plugin.getConfig().getInt("lottery.max-tickets-per-player", 10);
        numberMax = plugin.getConfig().getInt("lottery.number-range-max", 45);
        pickCount = plugin.getConfig().getInt("lottery.pick-count", 6);
        bonusEnabled = plugin.getConfig().getBoolean("lottery.bonus-enabled", true);
        tierDefs.clear();
        List<Map<?,?>> defs = plugin.getConfig().getMapList("lottery.tiers.definitions");
        for (Map<?,?> m: defs){
            Object nameObj = m.get("name"); String name = nameObj!=null? String.valueOf(nameObj):"등";
            int match = toInt(m.get("match"), 6);
            boolean bonusReq = Boolean.TRUE.equals(m.get("bonus-required"));
            Map<?,?> payout = (Map<?,?>) m.get("payout");
            String type = "pool-percent"; double value = 0.0;
            if (payout!=null){ Object typeObj = payout.get("type"); if (typeObj!=null) type=String.valueOf(typeObj); Object valObj = payout.get("value"); if (valObj instanceof Number) value=((Number)valObj).doubleValue(); }
            tierDefs.add(new TierDef(name, match, bonusReq, type, value));
        }
        if (jackpotPool < poolBase) jackpotPool = poolBase;
        LoggerBridge.infoKV(plugin, "lottery_reload", Map.ofEntries(
                Map.entry("ticketPrice", ticketPrice),
                Map.entry("intervalMin", intervalMin),
                Map.entry("poolBase", poolBase),
                Map.entry("numberMax", numberMax),
                Map.entry("pickCount", pickCount),
                Map.entry("bonusEnabled", bonusEnabled),
                Map.entry("tierCount", tierDefs.size())
        ));
    }

    public void handleCommand(Player p, String[] args){
        if (args.length>=2 && args[1].equalsIgnoreCase("draw")){
            if (!p.hasPermission("casino.admin")){ p.sendMessage(plugin.tr("common.no_permission")); return; }
            LoggerBridge.event(plugin, "lottery", "force_draw_cmd", Map.of(
                    "admin", p.getName(),
                    "pool", jackpotPool,
                    "tickets", tickets.size()
            ));
            forceDraw(p.getName()); return;
        }
        if (args.length>=2 && args[1].equalsIgnoreCase("drawset")){
            if (!p.hasPermission("casino.admin")){ p.sendMessage(plugin.tr("common.no_permission")); return; }
            int need = pickCount;
            if (args.length < 2 + need){
                p.sendMessage(ChatColor.RED+"사용법: /casino lottery drawset "+need+"개 숫자" + (bonusEnabled? " [보너스]":""));
                return;
            }
            Set<Integer> set = new HashSet<>(); int[] win = new int[need];
            try{
                for (int i=0;i<need;i++){
                    int v = Integer.parseInt(args[2+i]);
                    if (v<1 || v>numberMax) { p.sendMessage(ChatColor.RED+"범위 1.."+numberMax+" 벗어남: "+v); return; }
                    if (!set.add(v)) { p.sendMessage(ChatColor.RED+"중복 숫자: "+v); return; }
                    win[i]=v;
                }
            }catch(Exception ex){ p.sendMessage(ChatColor.RED+"숫자 파싱 실패"); return; }
            Arrays.sort(win);
            int bonus = -1;
            if (bonusEnabled){
                if (args.length >= 2 + need + 1){
                    try{
                        bonus = Integer.parseInt(args[2+need]);
                        if (bonus<1 || bonus>numberMax) { p.sendMessage(ChatColor.RED+"보너스 범위 1.."+numberMax+" 벗어남: "+bonus); return; }
                        for (int w: win) if (w==bonus){ p.sendMessage(ChatColor.RED+"보너스가 당첨번호와 중복됩니다"); return; }
                    }catch(Exception ex){ p.sendMessage(ChatColor.RED+"보너스 숫자 파싱 실패"); return; }
                } else {
                    bonus = rollBonusNotIn(win);
                }
            }
            LoggerBridge.event(plugin, "lottery", "draw_fixed_start", Map.of(
                    "admin", p.getName(),
                    "win", Arrays.toString(win),
                    "bonus", bonus,
                    "pool", jackpotPool,
                    "tickets", tickets.size()
            ));
            performDraw(win, bonus, true);
            return;
        }
        // buy 서브커맨드 명시 처리
        if (args.length>=2 && args[1].equalsIgnoreCase("buy")){
            // numbers 시작 인덱스 = 2
            processPurchase(p, Arrays.copyOfRange(args,2,args.length));
            return;
        }
        // 기존(숫자 바로 뒤에 오는 구 방식) 처리
        processPurchase(p, Arrays.copyOfRange(args,1,args.length));
    }

    private void processPurchase(Player p, String[] numberArgs){
        long owned = tickets.stream().filter(t -> t.player.equals(p.getUniqueId())).count();
        if (owned >= maxTicketsPerPlayer){
            p.sendMessage(plugin.tr("lottery.max_reached", Map.of("max", maxTicketsPerPlayer)));
            LoggerBridge.event(plugin, "lottery", "purchase_denied_max", Map.of("player", p.getName(), "owned", owned, "max", maxTicketsPerPlayer));
            return; }
        int[] nums = parseNumbers(numberArgs);
        boolean randomPick = (nums==null);
        if (!BetUtil.withdraw(plugin,p,ticketPrice)) {
            p.sendMessage(plugin.tr("lottery.insufficient"));
            LoggerBridge.event(plugin, "lottery", "purchase_denied_balance", Map.of("player", p.getName(), "price", ticketPrice, "owned", owned));
            return; }
        // 새 차감 메시지
        p.sendMessage(plugin.tr("lottery.spent", Map.of("price", ticketPrice)));
        if (nums==null) nums=randomNumbers();
        long poolBefore = jackpotPool;
        tickets.add(new TicketEntry(p.getUniqueId(), nums));
        p.sendMessage(plugin.tr("lottery.purchase", Map.of("numbers", Arrays.toString(nums), "owned", owned+1, "max", maxTicketsPerPlayer)));
        jackpotPool += ticketPrice; // 티켓비 누적
        LoggerBridge.event(plugin, "lottery", "purchase", Map.of("player", p.getName(), "numbers", Arrays.toString(nums), "random", randomPick, "price", ticketPrice, "pool_before", poolBefore, "pool_after", jackpotPool, "owned_after", owned+1));
    }

    private int[] parseNumbers(String[] args){ if (args==null || args.length==0) return null; try { if (args.length < pickCount) return null; int[] a=new int[pickCount]; for(int i=0;i<pickCount;i++){ a[i]=Integer.parseInt(args[i]); if (a[i]<1||a[i]>numberMax) return null; } Arrays.sort(a); return a; } catch(Exception e){ return null; } }

    private void schedule(){
        Bukkit.getScheduler().runTaskTimer(plugin, task->{
            long now=System.currentTimeMillis(); if (lastDraw==0) lastDraw=now; if (now-lastDraw >= intervalMin*60_000L){ draw(); lastDraw=now; }
        }, 20L, 20L*60L);
    }

    private void draw(){
        int[] win = randomNumbers();
        int bonus = bonusEnabled ? rollBonusNotIn(win) : -1;
        LoggerBridge.event(plugin, "lottery", "draw_scheduled_start", Map.of(
                "win", Arrays.toString(win),
                "bonus", bonus,
                "pool", jackpotPool,
                "tickets", tickets.size()
        ));
        performDraw(win, bonus, false);
    }

    // 관리자 강제 추첨
    private void forceDraw(String admin){ int[] win = randomNumbers(); int bonus = bonusEnabled ? rollBonusNotIn(win) : -1; LoggerBridge.event(plugin, "lottery", "draw_forced_start", Map.of("admin", admin, "win", Arrays.toString(win), "bonus", bonus, "pool", jackpotPool, "tickets", tickets.size())); performDraw(win, bonus, true); }

    private long floorPercent(long base, double percent){ return (long)Math.floor(base * (percent/100.0)); }

    private void performDraw(int[] win, int bonus, boolean forced){
        Arrays.sort(win);
        String forcedSuffix = forced? plugin.tr("lottery.forced_suffix"):"";
        Bukkit.broadcastMessage(plugin.tr("lottery.result_broadcast", Map.of("win", Arrays.toString(win), "bonus", bonus, "forced", forcedSuffix)));
        Map<TierDef, List<TicketEntry>> tierWins = new LinkedHashMap<>(); for (TierDef td: tierDefs){ tierWins.put(td, new ArrayList<>()); }
        List<TicketEntry> t1=new ArrayList<>(), t2=new ArrayList<>(), t3=new ArrayList<>(), t4=new ArrayList<>(), t5=new ArrayList<>();
        for (TicketEntry te: tickets){ int match = countMatch(win, te.nums); boolean bonusHit = bonusEnabled && contains(te.nums, bonus); boolean matchedAny=false; for (TierDef td: tierDefs){ if (match==td.match && (!td.bonusRequired || bonusHit)){ tierWins.get(td).add(te); matchedAny=true; break; } } if (!matchedAny){ if (match==pickCount) t1.add(te); else if (match==pickCount-1 && bonusHit) t2.add(te); else if (match==pickCount-1) t3.add(te); else if (match==pickCount-2) t4.add(te); else if (match==pickCount-3) t5.add(te); } }
        long pool = jackpotPool; long totalPaid=0L;
        for (Map.Entry<TierDef,List<TicketEntry>> ent: tierWins.entrySet()){
            TierDef td=ent.getKey(); List<TicketEntry> winners=ent.getValue(); long alloc; switch(td.payoutType){ case POOL_PERCENT -> alloc=floorPercent(pool, td.value); case FIXED -> alloc=Math.round(td.value)*winners.size(); default -> alloc=0; } totalPaid+=payTier(td.name,winners,alloc); }
        if (tierDefs.isEmpty()){
            long alloc1=floorPercent(pool, pFirst); long alloc2=floorPercent(pool, pSecond); long alloc3=floorPercent(pool, pThird); long alloc4=floorPercent(pool, pFourth); long alloc5=floorPercent(pool, pFifth);
            LoggerBridge.infoKV(plugin,"lottery_draw_alloc", Map.ofEntries(
                    Map.entry("pool", pool),
                    Map.entry("alloc1", alloc1),
                    Map.entry("alloc2", alloc2),
                    Map.entry("alloc3", alloc3),
                    Map.entry("alloc4", alloc4),
                    Map.entry("alloc5", alloc5),
                    Map.entry("c1", t1.size()),
                    Map.entry("c2", t2.size()),
                    Map.entry("c3", t3.size()),
                    Map.entry("c4", t4.size()),
                    Map.entry("c5", t5.size())
            ));
            totalPaid+=payTier("1등", t1, alloc1); totalPaid+=payTier("2등", t2, alloc2); totalPaid+=payTier("3등", t3, alloc3); totalPaid+=payTier("4등", t4, alloc4); totalPaid+=payTier("5등", t5, alloc5);
        }
        if (totalPaid > pool){ LoggerBridge.warn(plugin, "paid_exceeds_pool totalPaid="+totalPaid+" pool="+pool); totalPaid = pool; }
        boolean jackpotHit = (!tierDefs.isEmpty())? tickets.stream().anyMatch(te -> countMatch(win, te.nums)==pickCount) : !t1.isEmpty();
        long leftover = Math.max(0, pool - totalPaid);
        long nextPool = jackpotHit ? poolBase : (rolloverUnpaid ? (poolBase + leftover) : poolBase);
        LoggerBridge.event(plugin, "lottery", "draw_end", Map.of(
                "forced", forced,
                "win", Arrays.toString(win),
                "bonus", bonus,
                "tickets", tickets.size(),
                "paid", totalPaid,
                "leftover", leftover,
                "jackpot_hit", jackpotHit,
                "pool_before", pool,
                "pool_next", nextPool
        ));
        jackpotPool = nextPool;
        tickets.clear();
    }

    private long payTier(String tierName, List<TicketEntry> winners, long allocation){
        if (winners.isEmpty() || allocation<=0){ if (!winners.isEmpty()) broadcastTier(tierName, winners.size(), 0, 0); return 0L; }
        long each = Math.max(1, allocation / Math.max(1, winners.size())); // 내림 분배
        LoggerBridge.event(plugin, "lottery", "tier_alloc", Map.of(
                "tier", tierName,
                "winners", winners.size(),
                "allocation", allocation,
                "each", each
        ));
        long total = 0L;
        for (TicketEntry te: winners){
            Player p = Bukkit.getPlayer(te.player);
            if (p!=null){
                long net = BetUtil.applyTaxAndDeposit(plugin,p,each);
                total += each;
                LoggerBridge.event(plugin, "lottery", "payout", Map.of(
                        "tier", tierName,
                        "player", p.getName(),
                        "ticket", Arrays.toString(te.nums),
                        "gross", each,
                        "net", net,
                        "tax", each - net
                ));
                p.sendMessage(plugin.tr("lottery.tier_win_player", Map.of("tier", tierName, "net", net)));
            }
        }
        broadcastTier(tierName, winners.size(), allocation, each);
        return total;
    }

    private void broadcastTier(String tierName, int ticketCount, long allocation, long each){ if (ticketCount<=0) return; String alloc = allocation>0? plugin.tr("lottery.tier_alloc_suffix", Map.of("alloc", fmt(allocation), "each", fmt(each))):""; Bukkit.broadcastMessage(plugin.tr("lottery.tier_broadcast", Map.of("tier", tierName, "count", ticketCount, "alloc", alloc))); }

    private int[] argsToNumbers(String[] args){ if (args.length<1+pickCount) return null; try { int[] a=new int[pickCount]; for(int i=1;i<=pickCount;i++){ a[i-1]=Integer.parseInt(args[i]); if (a[i-1]<1||a[i-1]>numberMax) return null; } Arrays.sort(a); return a; } catch(Exception e){ return null; } }
    private int[] randomNumbers(){ Random r=new Random(); Set<Integer> s=new HashSet<>(); while(s.size()<pickCount) s.add(1+r.nextInt(numberMax)); return s.stream().mapToInt(i->i).sorted().toArray(); }
    private int rollBonusNotIn(int[] win){ Random r=new Random(); Set<Integer> set=new HashSet<>(); for (int w: win) set.add(w); int b; do { b=1+r.nextInt(numberMax);} while(set.contains(b)); return b; }
    private int countMatch(int[] win, int[] pick){ int c=0; for (int v: pick) for (int w: win) if (v==w) c++; return c; }
    private boolean contains(int[] a, int v){ if (v<0) return false; for (int x: a) if (x==v) return true; return false; }
    private String fmt(long v){ return String.format("%,d", v); }

    private int toInt(Object o, int d){ return (o instanceof Number)? ((Number)o).intValue() : d; }
    private double toDouble(Object o, double d){ return (o instanceof Number)? ((Number)o).doubleValue() : d; }

    public int getPickCountSafe(){ return pickCount; }
    public boolean isBonusEnabledSafe(){ return bonusEnabled; }

    static class TicketEntry { final UUID player; final int[] nums; TicketEntry(UUID u,int[] n){ player=u; nums=n; } }
    static class TierDef{
        final String name; final int match; final boolean bonusRequired; final PayoutType payoutType; final double value;
        TierDef(String n,int m, boolean b, String t, double v){ name=n; match=m; bonusRequired=b; payoutType = switch (t.toLowerCase(Locale.ROOT)){
            case "fixed" -> PayoutType.FIXED; default -> PayoutType.POOL_PERCENT; }; value=v; }
    }
    enum PayoutType{ POOL_PERCENT, FIXED }
}

// EOF marker
