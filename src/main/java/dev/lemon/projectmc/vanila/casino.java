package dev.lemon.projectmc.vanila;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import dev.lemon.projectmc.vanila.slot.SlotMachineManager;

public final class casino extends JavaPlugin {

    private Economy economy; // Vault Economy 인스턴스
    private SlotMachineManager slotMachineManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().warning("Vault Economy 를 찾지 못해 플러그인을 비활성화합니다.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 슬롯머신 매니저 초기화
        slotMachineManager = new SlotMachineManager(this);
        PluginCommand cmd = getCommand("slots");
        if (cmd != null) {
            cmd.setExecutor(slotMachineManager.getCommandExecutor());
        } else {
            getLogger().severe("/slots 명령 등록 실패 (plugin.yml 확인 요망)");
        }
        Bukkit.getPluginManager().registerEvents(slotMachineManager.getGuiListener(), this);
        getLogger().info("Economy Hook 성공: " + economy.getName());
        getLogger().info("SlotMachine 기능 활성화 완료");
    }

    @Override
    public void onDisable() {
        if (slotMachineManager != null) slotMachineManager.shutdown();
    }

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

    public Economy getEconomy() {
        return economy;
    }

    public SlotMachineManager getSlotMachineManager() { return slotMachineManager; }
}
