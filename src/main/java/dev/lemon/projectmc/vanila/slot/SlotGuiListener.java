package dev.lemon.projectmc.vanila.slot;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class SlotGuiListener implements Listener {
    private final SlotMachineManager manager;

    public SlotGuiListener(SlotMachineManager manager) {
        this.manager = manager;
    }

    private boolean isSlotInventory(Component titleComponent) {
        if (titleComponent == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(titleComponent);
        return plain.startsWith("슬롯머신");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!isSlotInventory(e.getView().title())) return;
        Inventory top = e.getView().getTopInventory();
        e.setCancelled(true); // 기본적으로 클릭 방지
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return; // 하단 인벤토리 무시
        int slot = e.getRawSlot();
        SlotSpinSession session = manager.getSession(player.getUniqueId());
        if (session == null) return;
        switch (slot) {
            case SlotMachineManager.BET_MINUS_SLOT -> manager.adjustBet(player, false);
            case SlotMachineManager.BET_PLUS_SLOT -> manager.adjustBet(player, true);
            case SlotMachineManager.SPIN_SLOT -> manager.startSpin(player);
            case SlotMachineManager.EXIT_SLOT -> player.closeInventory();
            default -> {}
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (isSlotInventory(e.getView().title())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (!isSlotInventory(e.getView().title())) return;
        SlotSpinSession session = manager.getSession(player.getUniqueId());
        if (session != null) {
            session.setInventory(null);
        }
    }
}
