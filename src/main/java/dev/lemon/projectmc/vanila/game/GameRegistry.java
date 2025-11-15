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
}

