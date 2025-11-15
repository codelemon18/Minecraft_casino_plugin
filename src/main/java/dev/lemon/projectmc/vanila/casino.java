package dev.lemon.projectmc.vanila;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import dev.lemon.projectmc.vanila.slot.SlotMachineManager;
import dev.lemon.projectmc.vanila.game.*;
import dev.lemon.projectmc.vanila.slot.SlotCommandExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import dev.lemon.projectmc.vanila.util.LoggerBridge;

public final class casino extends JavaPlugin {

    private Economy economy; // Vault Economy 인스턴스
    private SlotMachineManager slotMachineManager;
    private double houseTaxPercent;
    private GameRegistry games;
    private YamlConfiguration lang; private Map<String,String> langCache = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().warning("Vault Economy 를 찾지 못해 플러그인을 비활성화합니다.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadLang();
        reloadHouseSettings();
        // 매니저 초기화
        slotMachineManager = new SlotMachineManager(this);
        games = new GameRegistry(this);
        // /casino
        PluginCommand casinoCmd = getCommand("casino");
        if (casinoCmd != null) {
            MainCasinoCommand mainCmd = new MainCasinoCommand(this);
            casinoCmd.setExecutor(mainCmd);
            casinoCmd.setTabCompleter(mainCmd);
        }
        // /slots
        PluginCommand slotsCmd = getCommand("slots");
        if (slotsCmd != null) {
            slotsCmd.setExecutor(new SlotCommandExecutor(slotMachineManager));
        }
        // /casinoreload
        PluginCommand reloadCmd = getCommand("casinoreload");
        if (reloadCmd != null) reloadCmd.setExecutor(new dev.lemon.projectmc.vanila.game.ReloadCommand(this));
        // 슬롯 GUI 리스너 등록 유지
        Bukkit.getPluginManager().registerEvents(slotMachineManager.getGuiListener(), this);
        getLogger().info("Economy Hook 성공: " + economy.getName());
        getLogger().info("Casino 기능 활성화 완료 (houseTax=" + houseTaxPercent + "%)");
    }

    @Override
    public void onDisable() {
        if (slotMachineManager != null) slotMachineManager.shutdown();
    }

    public void reloadHouseSettings() {
        reloadConfig();
        loadLang();
        houseTaxPercent = getConfig().getDouble("house.tax-percent", 5.0);
        boolean debug = getConfig().getBoolean("logging.debug", false);
        boolean includeThread = getConfig().getBoolean("logging.include-thread", false);
        String format = getConfig().getString("logging.format", "{ts} [{level}] {plugin} {summary} | {message}");
        String tsPattern = getConfig().getString("logging.timestamp-pattern", "ISO_INSTANT");
        if ("ISO_INSTANT".equalsIgnoreCase(tsPattern)) tsPattern = "";
        Map<String,Object> templatesRoot = new HashMap<>();
        if (getConfig().isConfigurationSection("logging.templates")){
            for (String k : getConfig().getConfigurationSection("logging.templates").getKeys(false)){
                templatesRoot.put(k, getConfig().get("logging.templates."+k));
            }
        }
        // legacy event-templates 병합
        if (getConfig().isConfigurationSection("logging.event-templates")){
            Map<String,Object> eventSection = (Map) getConfig().getConfigurationSection("logging.event-templates").getValues(false);
            // templatesRoot.event.by-key에 합치기
            Object ev = templatesRoot.get("event");
            Map<String,Object> eventNode;
            if (ev instanceof Map<?,?> m){ eventNode = new HashMap<>((Map)m); }
            else { eventNode = new HashMap<>(); }
            Object byKey = eventNode.get("by-key"); Map<String,Object> byKeyMap;
            if (byKey instanceof Map<?,?> m2){ byKeyMap = new HashMap<>((Map)m2); }
            else { byKeyMap = new HashMap<>(); }
            byKeyMap.putAll(eventSection);
            eventNode.put("by-key", byKeyMap);
            templatesRoot.put("event", eventNode);
        }
        LoggerBridge.configureFull(debug, includeThread, format, tsPattern, templatesRoot);
    }

    private void loadLang(){
        File f = new File(getDataFolder(), "lang.yml");
        if (!f.exists()) saveResource("lang.yml", false);
        YamlConfiguration user = YamlConfiguration.loadConfiguration(f);
        // 기본 리소스 로드
        YamlConfiguration def = new YamlConfiguration();
        try (InputStream in = getResource("lang.yml")){
            if (in != null) def.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        // 캐시 빌드: 기본 + 사용자 키 합집합
        lang = user; // 기존 필드 유지
        langCache.clear();
        Set<String> keys = new LinkedHashSet<>();
        if (def != null) keys.addAll(def.getKeys(true));
        keys.addAll(user.getKeys(true));
        for (String k : keys){
            if (user.isConfigurationSection(k) || (def!=null && def.isConfigurationSection(k))) continue;
            String val = user.contains(k) ? user.getString(k, def!=null? def.getString(k, k):k)
                                          : (def!=null? def.getString(k, k):k);
            langCache.put(k, val);
        }
    }

    public String tr(String key){ return color(applyPlaceholders(langCache.getOrDefault(key, key), Map.of())); }
    public String tr(String key, Map<String,?> ph){ return color(applyPlaceholders(langCache.getOrDefault(key, key), ph)); }

    private String applyPlaceholders(String raw, Map<String,?> ph){ if (raw==null) return keyMissing(raw); String out=raw; for (Map.Entry<String,?> e: ph.entrySet()){ out = out.replace("{"+e.getKey()+"}", String.valueOf(e.getValue())); } return out; }
    private String keyMissing(String k){ return "§c<missing:"+k+">"; }
    private String color(String s){ return s==null?"":s.replace('&','§'); }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public double getHouseTaxPercent() { return houseTaxPercent; }

    public double applyHouseTax(double grossAmount) {
        double tax = Math.max(0.0, houseTaxPercent);
        double net = grossAmount * (1.0 - tax / 100.0);
        return Math.max(0.0, net);
    }

    public Economy getEconomy() { return economy; }
    public SlotMachineManager getSlotMachineManager() { return slotMachineManager; }

    // 게임 레지스트리 exposing
    public CoinFlipManager getCoinFlipManager(){ return games.coin(); }
    public RspManager getRspManager(){ return games.rsp(); }
    public DiceManager getDiceManager(){ return games.dice(); }
    public ScratchManager getScratchManager(){ return games.scratch(); }
    public LotteryManager getLotteryManager(){ return games.lottery(); }
    public HorseManager getHorseManager(){ return games.horse(); }
}
