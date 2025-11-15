package dev.lemon.projectmc.vanila.game;

import dev.lemon.projectmc.vanila.casino;

public class GameRegistry {
    private final casino plugin;
    private final CoinFlipManager coinFlip;
    private final RspManager rsp;
    private final DiceManager dice;
    private final ScratchManager scratch;
    private final LotteryManager lottery;
    private final HorseManager horse;

    public GameRegistry(casino plugin){
        this.plugin=plugin;
        this.coinFlip=new CoinFlipManager(plugin);
        this.rsp=new RspManager(plugin);
        this.dice=new DiceManager(plugin);
        this.scratch=new ScratchManager(plugin);
        this.lottery=new LotteryManager(plugin);
        this.horse=new HorseManager(plugin);
    }

    public CoinFlipManager coin(){ return coinFlip; }
    public RspManager rsp(){ return rsp; }
    public DiceManager dice(){ return dice; }
    public ScratchManager scratch(){ return scratch; }
    public LotteryManager lottery(){ return lottery; }
    public HorseManager horse(){ return horse; }

    // 모든 게임 종료 훅
    public void shutdown(){
        try { coinFlip.shutdown(); } catch (Throwable ignored) {}
        try { rspShutdownSafe(); } catch (Throwable ignored) {}
        try { dice.shutdown(); } catch (Throwable ignored) {}
        try { scratch.shutdown(); } catch (Throwable ignored) {}
        // Lottery는 주기 태스크가 플러그인 disable 시 자동 취소되므로 추가 작업 없음
        try { horse.shutdown(); } catch (Throwable ignored) {}
    }

    private void rspShutdownSafe(){
        // RSP에 별도 shutdown이 없다면 향후 호환을 위해 존재 확인 없이 무시
        try {
            java.lang.reflect.Method m = RspManager.class.getDeclaredMethod("shutdown");
            if (m != null) m.invoke(rsp);
        } catch (Exception ignored) {}
    }
}
