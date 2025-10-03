package dev.lemon.projectmc.vanila.slot;

import org.bukkit.ChatColor;
import org.bukkit.Material;

public enum SlotSymbol {
    DIAMOND(Material.DIAMOND, ChatColor.AQUA + "다이아몬드"),
    EMERALD(Material.EMERALD, ChatColor.GREEN + "에메랄드"),
    GOLDEN_APPLE(Material.GOLDEN_APPLE, ChatColor.GOLD + "황금사과"),
    APPLE(Material.APPLE, ChatColor.RED + "사과"),
    COAL(Material.COAL, ChatColor.DARK_GRAY + "석탄");

    private final Material material;
    private final String displayName;

    SlotSymbol(Material material, String displayName) {
        this.material = material;
        this.displayName = displayName;
    }

    public Material getMaterial() { return material; }
    public String getDisplayName() { return displayName; }
}

