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
            if (args.length < 3) { sender.sendMessage(plugin.tr("rsp.usage_choose")); return; }
            Game g = activeGames.get(sender.getUniqueId());
            if (g==null) { sender.sendMessage(plugin.tr("rsp.no_game")); return; }
            Hand hand = parseHand(args[2]);
            if (hand == null) { sender.sendMessage(plugin.tr("rsp.invalid_hand")); return; }
            applyHandChoice(sender, g, hand);
            return;
        }

        if (args.length < 3) { sendHelp(sender); return; }

        String targetName = args[1];
        long bet = parseLong(args[2], -1);
        if (bet < minBet || bet > maxBet) {
            sender.sendMessage(plugin.tr("rsp.bet_range", Map.of("min", minBet, "max", maxBet)));
            return;
        }

        // 수락/거절: /casino rsp <challenger> <bet> accept|deny
        if (args.length >= 4) {
            String action = args[3].toLowerCase();
            Player challenger = Bukkit.getPlayerExact(targetName);
            if (challenger == null) { sender.sendMessage(plugin.tr("rsp.offline_sender")); return; }
            Challenge ch = pendingChallenges.get(sender.getUniqueId());
            if (ch == null || ch.sender != challenger.getUniqueId() || ch.bet != bet) {
                sender.sendMessage(plugin.tr("rsp.challenge_not_found"));
                return;
            }
            if ("deny".equals(action)) {
                pendingChallenges.remove(sender.getUniqueId());
                // challenger 환불
                BetUtil.depositRaw(plugin, challenger, ch.bet);
                sender.sendMessage(ChatColor.GRAY+plugin.tr("rsp.deny_ok_sender"));
                challenger.sendMessage(plugin.tr("rsp.deny_refund_sender"));
                return;
            } else if ("accept".equals(action)) {
                // 이미 게임 중인지 체크
                if (isInGame(challenger) || isInGame(sender)) {
                    sender.sendMessage(plugin.tr("rsp.deny_self_in_game"));
                    return;
                }
                // 수락자 베팅 인출
                if (!BetUtil.withdraw(plugin, sender, bet)) {
                    sender.sendMessage(plugin.tr("rsp.accept_withdraw_fail"));
                    BetUtil.depositRaw(plugin, challenger, ch.bet); // 챌린저 환불
                    pendingChallenges.remove(sender.getUniqueId());
                    return;
                }
                pendingChallenges.remove(sender.getUniqueId());
                startGamePvP(challenger, sender, bet);
                return;
            } else {
                sender.sendMessage(plugin.tr("rsp.usage_acceptdeny"));
                return;
            }
        }

        // 새로운 도전 생성 (/casino rsp <target|봇> <bet>)
        if (isInGame(sender)) { sender.sendMessage(plugin.tr("rsp.already_in_game")); return; }
        if (hasPendingOutgoing(sender)) { sender.sendMessage(plugin.tr("rsp.already_pending")); return; }

        // 봇 모드
        if ("봇".equalsIgnoreCase(targetName) || "bot".equalsIgnoreCase(targetName)) {
            if (!BetUtil.withdraw(plugin, sender, bet)) { sender.sendMessage(plugin.tr("rsp.insufficient")); return; }
            startGameBot(sender, bet); return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) { sender.sendMessage(plugin.tr("rsp.target_offline")); return; }
        if (sender.getUniqueId().equals(target.getUniqueId())) { sender.sendMessage(plugin.tr("rsp.cannot_challenge_self")); return; }

        if (!BetUtil.withdraw(plugin, sender, bet)) { sender.sendMessage(plugin.tr("rsp.insufficient")); return; }
        Challenge challenge = new Challenge(sender.getUniqueId(), target.getUniqueId(), bet, System.currentTimeMillis());
        pendingChallenges.put(target.getUniqueId(), challenge);
        sender.sendMessage(plugin.tr("rsp.challenge_sent", Map.of("target", target.getName(), "timeout", timeoutSec)));
        // 텍스트 + 클릭 버튼 동시 제공
        sendChallengePrompt(target, sender.getName(), bet);
        // 타임아웃 스케줄
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Challenge c = pendingChallenges.get(target.getUniqueId());
            if (c != null && c.sender == sender.getUniqueId()) {
                pendingChallenges.remove(target.getUniqueId());
                BetUtil.depositRaw(plugin, sender, bet);
                sender.sendMessage(ChatColor.GRAY+plugin.tr("rsp.timeout_both_refund"));
                target.sendMessage(ChatColor.GRAY+plugin.tr("rsp.timeout_both_refund"));
            }
        }, timeoutSec * 20L);
    }

    private void startGameBot(Player player, long bet) {
        Game g = new Game(GameType.BOT, bet, player.getUniqueId(), null);
        activeGames.put(player.getUniqueId(), g);
        player.sendMessage(plugin.tr("rsp.bot_start"));
        sendChoosePrompt(player);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.1f);
        scheduleGameTimeout(g);
    }

    private void startGamePvP(Player a, Player b, long bet) {
        Game g = new Game(GameType.PVP, bet, a.getUniqueId(), b.getUniqueId());
        activeGames.put(a.getUniqueId(), g);
        activeGames.put(b.getUniqueId(), g);
        a.sendMessage(plugin.tr("rsp.pvp_start"));
        b.sendMessage(plugin.tr("rsp.pvp_start"));
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
            p.sendMessage(plugin.tr("rsp.already_chosen"));
            return;
        }
        g.choices.put(id, hand);
        p.sendMessage(plugin.tr("rsp.choose_done", Map.of("hand", displayHand(hand))));
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
            player.sendMessage(plugin.tr("rsp.draw_refund"));
            plugin.getLogger().info("[rsp] draw bot player="+player.getName()+" bet="+g.bet);
        } else if (result > 0) {
            long net = BetUtil.applyTaxAndDeposit(plugin, player, g.bet*2);
            player.sendMessage(plugin.tr("rsp.win_net", Map.of("net", fmt(net))));
            plugin.getLogger().info("[rsp] win bot player="+player.getName()+" bet="+g.bet+" net="+net);
        } else {
            player.sendMessage(plugin.tr("rsp.lose"));
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
            a.sendMessage(plugin.tr("rsp.draw_refund"));
            b.sendMessage(plugin.tr("rsp.draw_refund"));
            plugin.getLogger().info("[rsp] draw pvp a="+a.getName()+" b="+b.getName()+" bet="+g.bet);
        } else {
            Player winner = (result>0)?a:b;
            Player loser = (winner==a)?b:a;
            long net = BetUtil.applyTaxAndDeposit(plugin, winner, g.bet*2);
            winner.sendMessage(plugin.tr("rsp.win_net", Map.of("net", fmt(net))));
            loser.sendMessage(plugin.tr("rsp.lose"));
            plugin.getLogger().info("[rsp] pvp result winner="+winner.getName()+" loser="+loser.getName()+" bet="+g.bet+" net="+net);
        }
        endGame(g);
    }

    private void handleTimeout(Game g) {
        if (g.finished) return;
        if (g.type == GameType.BOT) {
            Player player = Bukkit.getPlayer(g.p1);
            if (player != null) {
                player.sendMessage(plugin.tr("rsp.timeout_refund"));
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
            if (a!=null) a.sendMessage(plugin.tr("rsp.timeout_both_refund"));
            if (b!=null) b.sendMessage(plugin.tr("rsp.timeout_both_refund"));
            plugin.getLogger().info("[rsp] timeout pvp both no choose bet="+g.bet);
        } else if (size == 1) { // 한쪽만 선택 → 선택한 쪽 승리
            UUID winnerId = g.choices.keySet().iterator().next();
            Player winner = Bukkit.getPlayer(winnerId);
            Player loser = (winnerId.equals(g.p1)?b:a);
            if (winner!=null) {
                long net = BetUtil.applyTaxAndDeposit(plugin, winner, g.bet*2);
                winner.sendMessage(plugin.tr("rsp.timeout_partial_win", Map.of("net", fmt(net))));
            }
            if (loser!=null) loser.sendMessage(plugin.tr("rsp.timeout_partial_lose"));
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
                remaining.sendMessage(plugin.tr("rsp.disconnect_win", Map.of("net", fmt(net))));
                plugin.getLogger().info("[rsp] disconnect win player="+remaining.getName()+" bet="+g.bet);
            } else {
                BetUtil.depositRaw(plugin, remaining, g.bet);
                remaining.sendMessage(plugin.tr("rsp.disconnect_refund"));
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
                challenger.sendMessage(ChatColor.GRAY+plugin.tr("rsp.disconnect_refund"));
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
        p.sendMessage(ChatColor.GOLD+plugin.tr("rsp.help_header"));
        p.sendMessage(ChatColor.YELLOW+plugin.tr("rsp.usage_challenge"));
        p.sendMessage(ChatColor.YELLOW+plugin.tr("rsp.usage_acceptdeny"));
        p.sendMessage(ChatColor.YELLOW+plugin.tr("rsp.usage_choose"));
    }

    private Hand parseHand(String raw) {
        if (raw == null) return null; String s = raw.toLowerCase();
        if (s.contains("가위") || s.contains("scissor")) return Hand.SCISSOR;
        if (s.contains("바위") || s.contains("rock")) return Hand.ROCK;
        if (s.contains("보") || s.contains("paper")) return Hand.PAPER;
        return null;
    }

    private String displayHand(Hand h) { return switch (h){ case ROCK->plugin.tr("rsp.hand_rock"); case PAPER->plugin.tr("rsp.hand_paper"); case SCISSOR->plugin.tr("rsp.hand_scissor"); }; }
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
        Component acceptBtn = button(plugin.tr("rsp.accept_button"), NamedTextColor.GREEN, "/casino rsp "+challengerName+" "+bet+" accept", plugin.tr("rsp.accept_hover"));
        Component denyBtn = button(plugin.tr("rsp.deny_button"), NamedTextColor.RED, "/casino rsp "+challengerName+" "+bet+" deny", plugin.tr("rsp.deny_hover"));
        target.sendMessage(header);
        target.sendMessage(acceptBtn.append(Component.space()).append(denyBtn));
    }

    private void sendChoosePrompt(Player p){
        Component header = Component.text("[RSP] ", NamedTextColor.GOLD)
                .append(Component.text(plugin.tr("rsp.choose_prompt").replace("&", "§"), NamedTextColor.WHITE));
        // 개별 클릭 버튼 제공
        Component rock = Component.text(plugin.tr("rsp.hand_rock"), NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/casino rsp choose "+plugin.tr("rsp.hand_rock"))).hoverEvent(HoverEvent.showText(Component.text("바위를 선택", NamedTextColor.WHITE)));
        Component paper = Component.text(plugin.tr("rsp.hand_paper"), NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/casino rsp choose "+plugin.tr("rsp.hand_paper"))).hoverEvent(HoverEvent.showText(Component.text("보를 선택", NamedTextColor.WHITE)));
        Component scissor = Component.text(plugin.tr("rsp.hand_scissor"), NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/casino rsp choose "+plugin.tr("rsp.hand_scissor"))).hoverEvent(HoverEvent.showText(Component.text("가위를 선택", NamedTextColor.WHITE)));
        p.sendMessage(header.append(Component.space()).append(rock).append(Component.space()).append(paper).append(Component.space()).append(scissor));
    }

    private Component button(String label, NamedTextColor color, String command, String hover){
        return Component.text(label, color)
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.WHITE)))
                .clickEvent(ClickEvent.runCommand(command));
    }
}
// EOF touch
