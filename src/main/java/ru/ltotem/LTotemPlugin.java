package ru.ltotem;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LTotemPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey totemKey;
    private NamespacedKey healthModifierKey;
    private BukkitTask holderTask;
    private final String menuMarker = "LTOTEM_MENU";

    private static final List<String> IDS = List.of("resistance", "speed", "judgment", "healing", "darkness");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        totemKey = new NamespacedKey(this, "totem_type");
        healthModifierKey = new NamespacedKey(this, "totem_resistance_health");

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("ltotem") != null) {
            getCommand("ltotem").setExecutor(this);
            getCommand("ltotem").setTabCompleter(this);
        }

        holderTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateHeldTotems(player);
            }
        }, 1L, 10L);
    }

    @Override
    public void onDisable() {
        if (holderTask != null) holderTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) removeResistanceBonus(player);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только игрок может открыть это меню.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(color("&cУ вас нет прав на эту команду."));
            return true;
        }
        openMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void openMenu(Player player) {
        int size = Math.max(9, Math.min(54, getConfig().getInt("gui.size", 27)));
        Inventory inv = Bukkit.createInventory(null, size, Component.text(color(getConfig().getString("gui.title", "&8Меню тотемов"))));

        Material filler = material(getConfig().getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack glass = new ItemStack(filler);
        ItemMeta gm = glass.getItemMeta();
        gm.displayName(Component.text(" "));
        glass.setItemMeta(gm);
        for (int i = 0; i < size; i++) inv.setItem(i, glass);

        for (String id : IDS) {
            if (!getConfig().getBoolean("items." + id + ".enabled", true)) continue;
            int slot = getConfig().getInt("items." + id + ".slot", -1);
            if (slot >= 0 && slot < size) inv.setItem(slot, createTotem(id));
        }
        player.openInventory(inv);
    }

    private ItemStack createTotem(String id) {
        String path = "items." + id;
        Material mat = material(getConfig().getString(path + ".material", "TOTEM_OF_UNDYING"), Material.TOTEM_OF_UNDYING);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(color(getConfig().getString(path + ".name", id))));
        meta.getPersistentDataContainer().set(totemKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private String getTotemId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(totemKey, PersistentDataType.STRING);
    }

    private void updateHeldTotems(Player player) {
        String main = getTotemId(player.getInventory().getItemInMainHand());
        String off = getTotemId(player.getInventory().getItemInOffHand());

        boolean resistance = "resistance".equals(main) || "resistance".equals(off);
        if (resistance) {
            addResistanceBonus(player);
            applyHeldEffect(player, "resistance", "items.resistance.effect");
        } else {
            removeResistanceBonus(player);
            removeConfiguredEffectIfOwned(player, "resistance", "items.resistance.effect");
        }

        if ("speed".equals(main) || "speed".equals(off)) {
            applyHeldEffect(player, "speed", "items.speed.effect");
        } else {
            removeConfiguredEffectIfOwned(player, "speed", "items.speed.effect");
        }
    }

    private void applyHeldEffect(Player player, String id, String path) {
        PotionEffectType type = potionType(getConfig().getString(path + ".type", "SPEED"));
        if (type == null) return;
        int amplifier = Math.max(0, getConfig().getInt(path + ".amplifier", 0));
        int seconds = getConfig().getInt(path + ".duration-seconds", -1);
        int ticks = seconds < 0 ? 20 * 60 * 60 : Math.max(1, seconds * 20);
        PotionEffect current = player.getPotionEffect(type);
        if (current == null || current.getAmplifier() != amplifier || current.getDuration() < 30) {
            player.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, false, true));
        }
    }

    private void removeConfiguredEffectIfOwned(Player player, String id, String path) {
        PotionEffectType type = potionType(getConfig().getString(path + ".type", "SPEED"));
        if (type == null) return;
        int amplifier = Math.max(0, getConfig().getInt(path + ".amplifier", 0));
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && current.getAmplifier() == amplifier) player.removePotionEffect(type);
    }

    private void addResistanceBonus(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null || attr.getModifier(healthModifierKey.getKey()) != null) return;
        double bonus = getConfig().getDouble("items.resistance.health-bonus", 4.0);
        AttributeModifier modifier = new AttributeModifier(healthModifierKey, bonus, AttributeModifier.Operation.ADD_NUMBER);
        attr.addTransientModifier(modifier);
        player.sendHealthUpdate();
    }

    private void removeResistanceBonus(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        AttributeModifier modifier = attr.getModifier(healthModifierKey.getKey());
        if (modifier != null) {
            attr.removeModifier(modifier);
            if (player.getHealth() > attr.getValue()) player.setHealth(attr.getValue());
            player.sendHealthUpdate();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        ItemStack item = hand == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        String id = getTotemId(item);
        if (id == null) return;

        Bukkit.getScheduler().runTask(this, () -> triggerAfterConsumption(player, id));
    }

    private void triggerAfterConsumption(Player player, String id) {
        switch (id) {
            case "resistance" -> updateHeldTotems(player);
            case "speed" -> updateHeldTotems(player);
            case "judgment" -> applyConfiguredOneShot(player, "items.judgment.effect");
            case "healing" -> {
                AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
                if (attr != null) player.setHealth(attr.getValue());
            }
            case "darkness" -> applyDarkness(player);
        }
    }

    private void applyConfiguredOneShot(Player player, String path) {
        PotionEffectType type = potionType(getConfig().getString(path + ".type", "DAMAGE_RESISTANCE"));
        if (type == null) return;
        int amplifier = Math.max(0, getConfig().getInt(path + ".amplifier", 3));
        int seconds = Math.max(1, getConfig().getInt(path + ".duration-seconds", 20));
        player.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier, false, true, true));
    }

    private void applyDarkness(Player owner) {
        String path = "items.darkness.effect";
        PotionEffectType type = potionType(getConfig().getString(path + ".type", "DARKNESS"));
        if (type == null) return;
        int amplifier = Math.max(0, getConfig().getInt(path + ".amplifier", 0));
        int seconds = Math.max(1, getConfig().getInt(path + ".duration-seconds", 20));
        double radius = Math.max(0, getConfig().getDouble("items.darkness.radius", 15.0));
        double radiusSq = radius * radius;

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(owner)) continue;
            if (!target.getWorld().equals(owner.getWorld())) continue;
            if (target.getLocation().distanceSquared(owner.getLocation()) <= radiusSq) {
                target.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier, false, true, true));
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(Component.text(color(getConfig().getString("gui.title", "&8Меню тотемов"))))) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        for (String id : IDS) {
            int slot = getConfig().getInt("items." + id + ".slot", -1);
            if (slot == event.getRawSlot() && getConfig().getBoolean("items." + id + ".enabled", true)) {
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createTotem(id));
                leftovers.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                player.closeInventory();
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().title().equals(Component.text(color(getConfig().getString("gui.title", "&8Меню тотемов"))))) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeResistanceBonus(event.getPlayer());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(this, () -> updateHeldTotems(event.getPlayer()));
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(this, () -> updateHeldTotems(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(this, () -> updateHeldTotems(event.getPlayer()));
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private static Material material(String name, Material fallback) {
        try {
            Material m = Material.valueOf(name.toUpperCase(Locale.ROOT));
            return m == null ? fallback : m;
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static PotionEffectType potionType(String name) {
        if (name == null) return null;
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "SPEED" -> PotionEffectType.SPEED;
            case "DAMAGE_RESISTANCE", "RESISTANCE" -> PotionEffectType.RESISTANCE;
            case "DARKNESS" -> PotionEffectType.DARKNESS;
            default -> PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        };
    }
}
