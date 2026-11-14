package ru.ltotem;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

public class LTotemPlugin extends JavaPlugin implements Listener {

    private NamespacedKey totemTypeKey;
    private NamespacedKey healthModifierKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        totemTypeKey = new NamespacedKey(this, "totem_type");
        healthModifierKey = new NamespacedKey(this, "resistance_health_bonus");

        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("ltotem").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }

            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Only OP players can use this command.");
                return true;
            }

            openTotemMenu(player);
            return true;
        });

        for (Player player : Bukkit.getOnlinePlayers()) {
            updateHeldTotem(player);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeResistanceBonus(player);
        }
    }

    private void openTotemMenu(Player player) {
        String title = color(getConfig().getString("gui.title", "&8Custom Totems"));
        int size = getConfig().getInt("gui.size", 9);

        Inventory inventory = Bukkit.createInventory(null, size, Component.text(title));

        addGuiItem(inventory, "resistance");
        addGuiItem(inventory, "speed");
        addGuiItem(inventory, "judgment");
        addGuiItem(inventory, "healing");
        addGuiItem(inventory, "darkness");

        player.openInventory(inventory);
    }

    private void addGuiItem(Inventory inventory, String type) {
        String path = "items." + type;

        if (!getConfig().getBoolean(path + ".enabled", true)) {
            return;
        }

        int slot = getConfig().getInt(path + ".slot", 0);
        Material material = Material.matchMaterial(
                getConfig().getString(path + ".material", "TOTEM_OF_UNDYING")
        );

        if (material == null) {
            material = Material.TOTEM_OF_UNDYING;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(color(
                getConfig().getString(path + ".name", "&e" + type)
        )));

        List<String> lore = getConfig().getStringList(path + ".lore");
        if (!lore.isEmpty()) {
            List<Component> components = new ArrayList<>();
            for (String line : lore) {
                components.add(Component.text(color(line)));
            }
            meta.lore(components);
        }

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(
                totemTypeKey,
                org.bukkit.persistence.PersistentDataType.STRING,
                type
        );

        item.setItemMeta(meta);

        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private boolean isCustomTotem(ItemStack item, String type) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }

        String value = item.getItemMeta().getPersistentDataContainer().get(
                totemTypeKey,
                org.bukkit.persistence.PersistentDataType.STRING
        );

        return type.equals(value);
    }

    private String getHeldTotemType(Player player) {
        if (isCustomTotem(player.getInventory().getItemInMainHand(), "resistance")) {
            return "resistance";
        }
        if (isCustomTotem(player.getInventory().getItemInMainHand(), "speed")) {
            return "speed";
        }
        if (isCustomTotem(player.getInventory().getItemInMainHand(), "judgment")) {
            return "judgment";
        }
        if (isCustomTotem(player.getInventory().getItemInMainHand(), "healing")) {
            return "healing";
        }
        if (isCustomTotem(player.getInventory().getItemInMainHand(), "darkness")) {
            return "darkness";
        }

        if (isCustomTotem(player.getInventory().getItemInOffHand(), "resistance")) {
            return "resistance";
        }
        if (isCustomTotem(player.getInventory().getItemInOffHand(), "speed")) {
            return "speed";
        }
        if (isCustomTotem(player.getInventory().getItemInOffHand(), "judgment")) {
            return "judgment";
        }
        if (isCustomTotem(player.getInventory().getItemInOffHand(), "healing")) {
            return "healing";
        }
        if (isCustomTotem(player.getInventory().getItemInOffHand(), "darkness")) {
            return "darkness";
        }

        return null;
    }

    private void updateHeldTotem(Player player) {
        String type = getHeldTotemType(player);

        if ("resistance".equals(type)) {
            addResistanceBonus(player);
            applyHeldEffect(player, "resistance");
        } else {
            removeResistanceBonus(player);
            removeHeldEffects(player);
        }

        if ("speed".equals(type)) {
            applyHeldEffect(player, "speed");
        }
    }

    private void applyHeldEffect(Player player, String type) {
        String path = "items." + type;
        String effectName = getConfig().getString(path + ".effect", "");
        int amplifier = getConfig().getInt(path + ".amplifier", 0);
        int duration = getConfig().getInt(path + ".duration-ticks", 40);

        if (effectName.isEmpty()) {
            return;
        }

        PotionEffectType effectType = PotionEffectType.getByName(effectName);
        if (effectType == null) {
            getLogger().warning("Unknown potion effect: " + effectName);
            return;
        }

        player.addPotionEffect(new PotionEffect(
                effectType,
                duration,
                amplifier,
                true,
                false,
                false
        ));
    }

    private void removeHeldEffects(Player player) {
        removeConfiguredEffect(player, "resistance");
        removeConfiguredEffect(player, "speed");
    }

    private void removeConfiguredEffect(Player player, String type) {
        String effectName = getConfig().getString("items." + type + ".effect", "");

        if (effectName.isEmpty()) {
            return;
        }

        PotionEffectType effectType = PotionEffectType.getByName(effectName);
        if (effectType != null) {
            player.removePotionEffect(effectType);
        }
    }

    private void addResistanceBonus(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);

        // Paper 1.21.11 expects a Key or UUID here, not a String.
        if (attr == null || attr.getModifier(healthModifierKey) != null) {
            return;
        }

        double bonus = getConfig().getDouble(
                "items.resistance.health-bonus",
                4.0
        );

        AttributeModifier modifier = new AttributeModifier(
                healthModifierKey,
                bonus,
                AttributeModifier.Operation.ADD_NUMBER
        );

        attr.addTransientModifier(modifier);
        player.sendHealthUpdate();
    }

    private void removeResistanceBonus(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);

        if (attr == null) {
            return;
        }

        // Paper 1.21.11 expects a Key or UUID here, not a String.
        AttributeModifier modifier = attr.getModifier(healthModifierKey);

        if (modifier != null) {
            attr.removeModifier(modifier);

            if (player.getHealth() > attr.getValue()) {
                player.setHealth(attr.getValue());
            }

            player.sendHealthUpdate();
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(this, () -> updateHeldTotem(event.getPlayer()));
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(this, () -> updateHeldTotem(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(this, () -> updateHeldTotem(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeResistanceBonus(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        String type = getHeldTotemType(player);

        if ("judgment".equals(type)) {
            String effectName = getConfig().getString(
                    "items.judgment.effect",
                    "RESISTANCE"
            );

            int amplifier = getConfig().getInt(
                    "items.judgment.amplifier",
                    3
            );

            int duration = getConfig().getInt(
                    "items.judgment.duration-ticks",
                    400
            );

            PotionEffectType effectType = PotionEffectType.getByName(effectName);

            if (effectType != null) {
                player.addPotionEffect(new PotionEffect(
                        effectType,
                        duration,
                        amplifier,
                        true,
                        true,
                        true
                ));
            }
        }

        if ("healing".equals(type)) {
            double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) != null
                    ? player.getAttribute(Attribute.MAX_HEALTH).getValue()
                    : 20.0;

            player.setHealth(Math.min(maxHealth, player.getHealth() + getConfig()
                    .getDouble("items.healing.heal-amount", maxHealth)));
        }

        if ("darkness".equals(type)) {
            int radius = getConfig().getInt(
                    "items.darkness.radius",
                    15
            );

            int duration = getConfig().getInt(
                    "items.darkness.duration-ticks",
                    400
            );

            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (entity instanceof Player target
                        && target.getWorld().equals(player.getWorld())
                        && !target.equals(player)) {

                    target.addPotionEffect(new PotionEffect(
                            PotionEffectType.DARKNESS,
                            duration,
                            0,
                            true,
                            true,
                            true
                    ));
                }
            }
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = color(getConfig().getString(
                "gui.title",
                "&8Custom Totems"
        ));

        if (!event.getView().title().equals(Component.text(title))) {
            return;
        }

        event.setCancelled(true);

        if (!player.isOp()) {
            player.closeInventory();
            return;
        }

        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        String type = clicked.getItemMeta().getPersistentDataContainer().get(
                totemTypeKey,
                org.bukkit.persistence.PersistentDataType.STRING
        );

        if (type == null) {
            return;
        }

        player.getInventory().addItem(clicked.clone());
        player.sendMessage(ChatColor.GREEN + "You received: " + type);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        String title = color(getConfig().getString(
                "gui.title",
                "&8Custom Totems"
        ));

        if (event.getView().title().equals(Component.text(title))) {
            event.setCancelled(true);
        }
    }
}
