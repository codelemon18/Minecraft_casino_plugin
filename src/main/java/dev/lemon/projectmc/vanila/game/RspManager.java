package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

public class RspManager implements Listener {
    private final casino plugin;
    private int minBet, maxBet, timeoutSec;
    // 봇 결과 가중치(설정값). win/draw/lose 합은 내부에서 정규화하여 사용
    private int botWeightWin = 50, botWeightDraw = 15, botWeightLose = 35;

    public RspManager(casino plugin) {
        this.plugin = plugin; reload(); Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    public void reload() {
        minBet = plugin.getConfig().getInt("rsp.min-bet", 10);
        maxBet = plugin.getConfig().getInt("rsp.max-bet", 20000);
        timeoutSec = plugin.getConfig().getInt("rsp.challenge-timeout-seconds", 60);
        // 가중치 읽기(없으면 기본값 유지)
        botWeightWin = plugin.getConfig().getInt("rsp.bot-weights.win", botWeightWin);
        botWeightDraw = plugin.getConfig().getInt("rsp.bot-weights.draw", botWeightDraw);
        botWeightLose = plugin.getConfig().getInt("rsp.bot-weights.lose", botWeightLose);
        // 구키 존재 시 경고 (더 이상 사용하지 않음을 알림)
        if (plugin.getConfig().contains("rsp.bot-win-rate-percent")) {
            plugin.getLogger().warning("[RSP] 'rsp.bot-win-rate-percent'는 더 이상 사용되지 않습니다. 'rsp.bot-weights'를 사용하세요.");
        }
    }

    public void shutdown(){
        // 대기/진행 중 상태 정리
        pendingChallenges.clear();
        activeGames.clear();
    }

    // pendingChallenges: 대상(Player B) -> Challenge(보낸 Player A)
    private final Map<UUID, Challenge> pendingChallenges = new ConcurrentHashMap<>();
    // activeGames: 참여자 UUID -> Game
    private final Map<UUID, Game> activeGames = new ConcurrentHashMap<>();

    // ------------- Command Entry Point -------------
    public void handleCommand(Player sender, String[] args) {
        if (args.length < 2) { sendHelp(sender); return; }

        // /casino rsp choose <hand>
        if (args.length >= 2 && "choose".equalsIgnoreCase(args[1])) {
            if (args.length < 3) { sender.sendMessage(ChatColor.RED+"[RSP] 사용법: /casino rsp choose <가위|바위|보>"); return; }
            Game g = activeGames.get(sender.getUniqueId());
            if (g==null) { sender.sendMessage(ChatColor.RED+"[RSP] 진행 중인 게임이 없습니다."); return; }
            Hand hand = parseHand(args[2]);
            if (hand == null) { sender.sendMessage(ChatColor.RED+"[RSP] 잘못된 손 모양 입력"); return; }
            applyHandChoice(sender, g, hand);
            return;
        }

        if (args.length < 3) { sendHelp(sender); return; }

        String targetName = args[1];
        long bet = parseLong(args[2], -1);
        if (bet < minBet || bet > maxBet) {
            sender.sendMessage(ChatColor.RED+"[RSP] 베팅 범위("+minBet+"~"+maxBet+")");
            return;
        }

        // 수락/거절: /casino rsp <challenger> <bet> accept|deny
        if (args.length >= 4) {
            String action = args[3].toLowerCase();
            Player challenger = Bukkit.getPlayerExact(targetName);
            if (challenger == null) { sender.sendMessage(ChatColor.RED+"[RSP] 보낸 플레이어 오프라인"); return; }
            Challenge ch = pendingChallenges.get(sender.getUniqueId());
            if (ch == null || ch.sender != challenger.getUniqueId() || ch.bet != bet) {
                sender.sendMessage(ChatColor.RED+"[RSP] 해당 도전을 찾을 수 없습니다.");
                return;
            }
            if ("deny".equals(action)) {
                pendingChallenges.remove(sender.getUniqueId());
                // challenger 환불
                BetUtil.depositRaw(plugin, challenger, ch.bet);
                sender.sendMessage(ChatColor.GRAY+"[RSP] 도전 거절. 환불 완료");
                challenger.sendMessage(ChatColor.RED+"[RSP] 상대가 도전을 거절했습니다. 베팅 반환");
                return;
            } else if ("accept".equals(action)) {
                // 이미 게임 중인지 체크
                if (isInGame(challenger) || isInGame(sender)) {
                    sender.sendMessage(ChatColor.RED+"[RSP] 한쪽이 이미 다른 게임 중입니다.");
                    return;
                }
                // 수락자 베팅 인출
                if (!BetUtil.withdraw(plugin, sender, bet)) {
                    sender.sendMessage(ChatColor.RED+"[RSP] 잔액 부족. 수락 실패");
                    BetUtil.depositRaw(plugin, challenger, ch.bet); // 챌린저 환불
                    pendingChallenges.remove(sender.getUniqueId());
                    return;
                }
                pendingChallenges.remove(sender.getUniqueId());
                startGamePvP(challenger, sender, bet);
                return;
            } else {
                sender.sendMessage(ChatColor.RED+"[RSP] 사용법: /casino rsp <challenger> <bet> accept|deny");
                return;
            }
        }

        // 새로운 도전 생성 (/casino rsp <target|봇> <bet>)
        if (isInGame(sender)) { sender.sendMessage(ChatColor.RED+"[RSP] 이미 게임 중입니다."); return; }
        if (hasPendingOutgoing(sender)) { sender.sendMessage(ChatColor.RED+"[RSP] 이미 보낸 도전이 처리 대기 중입니다."); return; }

        // 봇 모드
        if ("봇".equalsIgnoreCase(targetName) || "bot".equalsIgnoreCase(targetName)) {
            if (!BetUtil.withdraw(plugin, sender, bet)) { sender.sendMessage(ChatColor.RED+"[RSP] 잔액 부족"); return; }
            startGameBot(sender, bet); return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) { sender.sendMessage(ChatColor.RED+"[RSP] 대상 오프라인"); return; }
        if (sender.getUniqueId().equals(target.getUniqueId())) { sender.sendMessage(ChatColor.RED+"[RSP] 자기 자신에게 도전할 수 없습니다."); return; }

        if (!BetUtil.withdraw(plugin, sender, bet)) { sender.sendMessage(ChatColor.RED+"[RSP] 잔액 부족"); return; }
        Challenge challenge = new Challenge(sender.getUniqueId(), target.getUniqueId(), bet, System.currentTimeMillis());
        pendingChallenges.put(target.getUniqueId(), challenge);
        sender.sendMessage(ChatColor.YELLOW+"[RSP] "+target.getName()+"에게 도전 전송. "+timeoutSec+"초 유효");
        // 텍스트 + 클릭 버튼 동시 제공
        sendChallengePrompt(target, sender.getName(), bet);
        // 타임아웃 스케줄
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Challenge c = pendingChallenges.get(target.getUniqueId());
            if (c != null && c.sender == sender.getUniqueId()) {
                pendingChallenges.remove(target.getUniqueId());
                BetUtil.depositRaw(plugin, sender, bet);
                sender.sendMessage(ChatColor.GRAY+"[RSP] 도전 시간 초과. 환불 완료");
                target.sendMessage(ChatColor.GRAY+"[RSP] 도전 시간 초과");
            }
        }, timeoutSec * 20L);
    }

    private void startGameBot(Player player, long bet) {
        Game g = new Game(GameType.BOT, bet, player.getUniqueId(), null);
        activeGames.put(player.getUniqueId(), g);
        player.sendMessage(ChatColor.GRAY+"[RSP] 봇 대결 시작. '/casino rsp choose <가위|바위|보>'로 선택");
        sendChoosePrompt(player);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.1f);
        scheduleGameTimeout(g);
    }

    private void startGamePvP(Player a, Player b, long bet) {
        Game g = new Game(GameType.PVP, bet, a.getUniqueId(), b.getUniqueId());
        activeGames.put(a.getUniqueId(), g);
        activeGames.put(b.getUniqueId(), g);
        a.sendMessage(ChatColor.GOLD+"[RSP] 대결 시작! '/casino rsp choose <가위|바위|보>'로 선택");
        b.sendMessage(ChatColor.GOLD+"[RSP] 대결 시작! '/casino rsp choose <가위|바위|보>'로 선택");
        sendChoosePrompt(a);
        sendChoosePrompt(b);
        a.playSound(a.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.05f);
        b.playSound(b.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.05f);
        scheduleGameTimeout(g);
    }

    private void scheduleGameTimeout(Game g) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!g.finished) handleTimeout(g);
        }, timeoutSec * 20L);
    }

    // 플레이어의 손 선택을 적용하고 필요 시 즉시 판정 수행
    private void applyHandChoice(Player p, Game g, Hand hand) {
        if (g.finished) return;
        UUID id = p.getUniqueId();
        if (g.choices.containsKey(id)) {
            p.sendMessage(ChatColor.GRAY+"[RSP] 이미 선택했습니다.");
            return;
        }
        g.choices.put(id, hand);
        p.sendMessage(ChatColor.YELLOW+"[RSP] 선택 완료: "+displayHand(hand));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.3f);
        if (g.type == GameType.BOT) {
            // 봇 대결은 플레이어가 선택하면 즉시 판정
            resolveBot(g, p);
        } else {
            // PVP는 양쪽 모두 선택 시 판정
            if (g.choices.size() >= 2) resolvePvp(g);
        }
    }

    // ------------- Resolution -------------
    private void resolveBot(Game g, Player player) {
        Hand playerHand = g.choices.get(player.getUniqueId());
        Hand botHand = chooseBotHandWeighted(playerHand);
        int result = judge(playerHand, botHand);
        if (result == 0) { // draw → 환불
            BetUtil.depositRaw(plugin, player, g.bet);
            player.sendMessage(ChatColor.GRAY+"[RSP] 비김! 베팅 환불");
            plugin.getLogger().info("[rsp] draw bot player="+player.getName()+" bet="+g.bet);
        } else if (result > 0) {
            long net = BetUtil.applyTaxAndDeposit(plugin, player, g.bet*2);
            player.sendMessage(ChatColor.GREEN+"[RSP] 승리! 세후 ₩"+fmt(net));
            plugin.getLogger().info("[rsp] win bot player="+player.getName()+" bet="+g.bet+" net="+net);
        } else {
            player.sendMessage(ChatColor.RED+"[RSP] 패배!");
            plugin.getLogger().info("[rsp] lose bot player="+player.getName()+" bet="+g.bet);
        }
        endGame(g);
    }

    private void resolvePvp(Game g) {
        Player a = Bukkit.getPlayer(g.p1);
        Player b = Bukkit.getPlayer(g.p2);
        if (a==null || b==null) { handleDisconnect(g, a, b); return; }
        Hand ha = g.choices.get(g.p1);
        Hand hb = g.choices.get(g.p2);
        if (ha==null || hb==null) { handleTimeout(g); return; }
        int result = judge(ha, hb);
        if (result == 0) {
            BetUtil.depositRaw(plugin, a, g.bet);
            BetUtil.depositRaw(plugin, b, g.bet);
            a.sendMessage(ChatColor.GRAY+"[RSP] 비김! 환불");
            b.sendMessage(ChatColor.GRAY+"[RSP] 비김! 환불");
            plugin.getLogger().info("[rsp] draw pvp a="+a.getName()+" b="+b.getName()+" bet="+g.bet);
        } else {
            Player winner = (result>0)?a:b;
            Player loser = (winner==a)?b:a;
            long net = BetUtil.applyTaxAndDeposit(plugin, winner, g.bet*2);
            winner.sendMessage(ChatColor.GREEN+"[RSP] 승리! 세후 ₩"+fmt(net));
            loser.sendMessage(ChatColor.RED+"[RSP] 패배!");
            plugin.getLogger().info("[rsp] pvp result winner="+winner.getName()+" loser="+loser.getName()+" bet="+g.bet+" net="+net);
        }
        endGame(g);
    }

    private void handleTimeout(Game g) {
        if (g.finished) return;
        if (g.type == GameType.BOT) {
            Player player = Bukkit.getPlayer(g.p1);
            if (player != null) {
                player.sendMessage(ChatColor.RED+"[RSP] 시간 초과. 베팅 환불");
                BetUtil.depositRaw(plugin, player, g.bet);
            }
            plugin.getLogger().info("[rsp] timeout bot player="+(player!=null?player.getName():"null")+" bet="+g.bet);
            endGame(g);
            return;
        }
        Player a = Bukkit.getPlayer(g.p1);
        Player b = Bukkit.getPlayer(g.p2);
        int size = g.choices.size();
        if (size == 0) { // 모두 미선택 → 환불
            if (a!=null) BetUtil.depositRaw(plugin,a,g.bet);
            if (b!=null) BetUtil.depositRaw(plugin,b,g.bet);
            if (a!=null) a.sendMessage(ChatColor.GRAY+"[RSP] 시간 초과 환불");
            if (b!=null) b.sendMessage(ChatColor.GRAY+"[RSP] 시간 초과 환불");
            plugin.getLogger().info("[rsp] timeout pvp both no choose bet="+g.bet);
        } else if (size == 1) { // 한쪽만 선택 → 선택한 쪽 승리
            UUID winnerId = g.choices.keySet().iterator().next();
            Player winner = Bukkit.getPlayer(winnerId);
            Player loser = (winnerId.equals(g.p1)?b:a);
            if (winner!=null) {
                long net = BetUtil.applyTaxAndDeposit(plugin, winner, g.bet*2);
                winner.sendMessage(ChatColor.GREEN+"[RSP] 상대 미선택 승리! 세후 ₩"+fmt(net));
            }
            if (loser!=null) loser.sendMessage(ChatColor.RED+"[RSP] 시간 초과 패배");
            plugin.getLogger().info("[rsp] timeout pvp partial winner="+(winner!=null?winner.getName():"null")+" bet="+g.bet);
        }
        endGame(g);
    }

    private void handleDisconnect(Game g, Player a, Player b) {
        // 한 명 또는 두 명 모두 이탈한 경우 처리: 남아있는 플레이어가 선택했으면 승리, 아니면 환불
        if (g.type == GameType.BOT) { handleTimeout(g); return; }
        Player remaining = a==null?b:a;
        if (remaining != null) {
            if (g.choices.containsKey(remaining.getUniqueId())) {
                long net = BetUtil.applyTaxAndDeposit(plugin, remaining, g.bet*2);
                remaining.sendMessage(ChatColor.GREEN+"[RSP] 상대 접속 종료로 승리! 세후 ₩"+fmt(net));
                plugin.getLogger().info("[rsp] disconnect win player="+remaining.getName()+" bet="+g.bet);
            } else {
                BetUtil.depositRaw(plugin, remaining, g.bet);
                remaining.sendMessage(ChatColor.GRAY+"[RSP] 상대 접속 종료. 환불");
                plugin.getLogger().info("[rsp] disconnect refund player="+remaining.getName()+" bet="+g.bet);
            }
        }
        endGame(g);
    }

    private Hand chooseBotHandWeighted(Hand playerHand) {
        // 설정된 가중치 사용. 전부 0 이하이면 기본 가중치(50/15/35) 사용
        int wWin = Math.max(0, botWeightWin);
        int wDraw = Math.max(0, botWeightDraw);
        int wLose = Math.max(0, botWeightLose);
        if (wWin + wDraw + wLose <= 0) { wWin = 50; wDraw = 15; wLose = 35; }
        int sum = wWin + wDraw + wLose;
        int r = new Random().nextInt(sum);
        if (r < wWin) return counter(playerHand);            // 봇 승리
        r -= wWin;
        if (r < wDraw) return playerHand;                    // 무승부
        return losingTo(playerHand);                          // 봇 패배
    }

    private Hand counter(Hand h) { return switch (h) { case ROCK -> Hand.PAPER; case PAPER -> Hand.SCISSOR; case SCISSOR -> Hand.ROCK; }; }
    private Hand losingTo(Hand player) { return switch (player) { case ROCK -> Hand.SCISSOR; case PAPER -> Hand.ROCK; case SCISSOR -> Hand.PAPER; }; }

    private int judge(Hand a, Hand b) {
        if (a == b) return 0;
        return switch (a) {
            case ROCK -> (b == Hand.SCISSOR) ? 1 : -1;
            case PAPER -> (b == Hand.ROCK) ? 1 : -1;
            case SCISSOR -> (b == Hand.PAPER) ? 1 : -1;
        };
    }

    // ------------- Utility / Cleanup -------------
    private void endGame(Game g) {
        g.finished = true;
        activeGames.remove(g.p1);
        if (g.p2 != null) activeGames.remove(g.p2);
    }

    private boolean isInGame(Player p) { return activeGames.containsKey(p.getUniqueId()); }
    private boolean hasPendingOutgoing(Player sender) {
        for (Challenge c : pendingChallenges.values()) if (c.sender == sender.getUniqueId()) return true; return false;
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        // Pending challenge 대상이 나가면 챌린지 삭제 & 챌린저 환불
        Challenge ch = pendingChallenges.remove(id);
        if (ch != null) {
            Player challenger = Bukkit.getPlayer(ch.sender);
            if (challenger != null) {
                BetUtil.depositRaw(plugin, challenger, ch.bet);
                challenger.sendMessage(ChatColor.GRAY+"[RSP] 상대 퇴장. 도전 취소 환불");
            }
        }
        // 게임 중이면 즉시 종료 처리
        Game g = activeGames.get(id);
        if (g != null && !g.finished) {
            if (g.type == GameType.BOT) {
                BetUtil.depositRaw(plugin, Bukkit.getPlayer(g.p1), g.bet); // 플레이어 환불 (p1이 바로 봇 상대 플레이어)
                plugin.getLogger().info("[rsp] quit bot refund player="+e.getPlayer().getName()+" bet="+g.bet);
                endGame(g);
            } else {
                Player a = Bukkit.getPlayer(g.p1); Player b = Bukkit.getPlayer(g.p2);
                handleDisconnect(g, a, b);
            }
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage(ChatColor.GOLD+"[RSP] 사용법");
        p.sendMessage(ChatColor.YELLOW+"/casino rsp <player|봇> <베팅>"+ChatColor.GRAY+" - 도전 전송");
        p.sendMessage(ChatColor.YELLOW+"/casino rsp <보낸이> <베팅> accept|deny"+ChatColor.GRAY+" - 도전 수락/거절");
        p.sendMessage(ChatColor.YELLOW+"/casino rsp choose <가위|바위|보>"+ChatColor.GRAY+" - 손 선택");
    }

    private Hand parseHand(String raw) {
        if (raw == null) return null; String s = raw.toLowerCase();
        if (s.contains("가위") || s.contains("scissor")) return Hand.SCISSOR;
        if (s.contains("바위") || s.contains("rock")) return Hand.ROCK;
        if (s.contains("보") || s.contains("paper")) return Hand.PAPER;
        return null;
    }

    private String displayHand(Hand h) { return switch (h){ case ROCK->"바위"; case PAPER->"보"; case SCISSOR->"가위"; }; }
    private long parseLong(String s, long def){ try { return Long.parseLong(s);} catch(Exception e){ return def; }}
    private String fmt(long v){ return String.format("%,d", v); }

    // Data Structures
    enum GameType { BOT, PVP }
    static class Game {
        final GameType type; final long bet; final UUID p1; final UUID p2; final Map<UUID, Hand> choices = new HashMap<>(); boolean finished;
        Game(GameType t, long bet, UUID a, UUID b){ this.type=t; this.bet=bet; this.p1=a; this.p2=b; }
    }
    static class Challenge { final UUID sender; final UUID target; final long bet; final long ts; Challenge(UUID s, UUID t, long b, long ts){ sender=s; target=t; bet=b; this.ts=ts; } }
    enum Hand { ROCK, PAPER, SCISSOR }

    private void sendChallengePrompt(Player target, String challengerName, long bet){
        Component header = Component.text("[RSP] ", NamedTextColor.GOLD)
                .append(Component.text(challengerName+"의 도전! ", NamedTextColor.WHITE))
                .append(Component.text("베팅 ₩"+fmt(bet), NamedTextColor.AQUA));
        Component acceptBtn = button("[수락]", NamedTextColor.GREEN, "/casino rsp "+challengerName+" "+bet+" accept", "도전 수락");
        Component denyBtn = button("[거절]", NamedTextColor.RED, "/casino rsp "+challengerName+" "+bet+" deny", "도전 거절");
        target.sendMessage(header);
        target.sendMessage(acceptBtn.append(Component.space()).append(denyBtn));
    }

    private void sendChoosePrompt(Player p){
        Component header = Component.text("[RSP] 선택: ", NamedTextColor.GOLD)
                .append(Component.text("가위", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/casino rsp choose 가위")).hoverEvent(HoverEvent.showText(Component.text("가위를 선택", NamedTextColor.WHITE))))
                .append(Component.space())
                .append(Component.text("바위", NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/casino rsp choose 바위")).hoverEvent(HoverEvent.showText(Component.text("바위를 선택", NamedTextColor.WHITE))))
                .append(Component.space())
                .append(Component.text("보", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/casino rsp choose 보")).hoverEvent(HoverEvent.showText(Component.text("보를 선택", NamedTextColor.WHITE))));
        p.sendMessage(header);
    }

    private Component button(String label, NamedTextColor color, String command, String hover){
        return Component.text(label, color)
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.WHITE)))
                .clickEvent(ClickEvent.runCommand(command));
    }
}
// EOF touch
