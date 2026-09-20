package cz.deathreplay;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitTask;

public final class Recorder implements Listener {
    private final DeathReplayPlugin plugin;
    private final ReplayStore store;
    private final Map<UUID, ArrayDeque<Model.Frame>> rings = new HashMap<>();
    private final Set<UUID> pendingSwing = new HashSet<>();
    private final Set<UUID> pendingHurt = new HashSet<>();
    private final Map<ItemStack, String> encCache = new HashMap<>();
    private BukkitTask task;
    private int interval;
    private int maxFrames;
    private double radius;
    private int maxEntities;

    public Recorder(DeathReplayPlugin plugin, ReplayStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** Reads the record.* settings and (re)starts the recording timer. Safe to call again on reload. */
    public void start() {
        if (task != null) {
            task.cancel();
        }
        rings.clear();
        FileConfiguration cfg = plugin.getConfig();
        interval = Math.max(1, cfg.getInt("record.interval-ticks", 2));
        int seconds = Math.max(3, cfg.getInt("record.duration-seconds", 10));
        maxFrames = Math.max(2, seconds * 20 / interval);
        radius = Math.max(4.0, cfg.getDouble("record.radius", 24.0));
        maxEntities = Math.max(2, cfg.getInt("record.max-entities", 20));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) {
                rings.remove(p.getUniqueId());
                continue;
            }
            ArrayDeque<Model.Frame> ring = rings.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            ring.addLast(capture(p, null));
            while (ring.size() > maxFrames) {
                ring.removeFirst();
            }
        }
        pendingSwing.clear();
        pendingHurt.clear();
    }

    private Model.Frame capture(Player center, UUID dead) {
        ArrayList<Model.State> list = new ArrayList<>();
        list.add(stateOf(center, dead));
        for (Entity e : center.getNearbyEntities(radius, radius, radius)) {
            if (list.size() >= maxEntities) {
                break;
            }
            if (e == center || plugin.isFake(e) || !e.isValid() || !tracked(e)) {
                continue;
            }
            list.add(stateOf(e, dead));
        }
        return new Model.Frame(list);
    }

    private boolean tracked(Entity e) {
        if (e instanceof Player pl) {
            return pl.getGameMode() != GameMode.SPECTATOR && !isVanished(pl);
        }
        if (e instanceof ArmorStand) {
            return false;
        }
        if (e instanceof LivingEntity) {
            return e.getType().isSpawnable();
        }
        return e instanceof AbstractArrow;
    }

    private static boolean isVanished(Player p) {
        for (MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private Model.State stateOf(Entity e, UUID dead) {
        UUID id = e.getUniqueId();
        Location l = e.getLocation();
        int flags = 0;
        byte kind = e instanceof Player ? Model.PLAYER : (e instanceof LivingEntity ? Model.MOB : Model.ARROW);
        String name = e instanceof Player pl ? pl.getName() : e.getType().name();
        String hand = "AIR";
        String helmet = "AIR";
        String chest = "AIR";
        String legs = "AIR";
        String boots = "AIR";
        float health = 0f;
        float maxHealth = 0f;
        float armor = 0f;

        if (e instanceof Player pl) {
            if (pl.isSneaking()) {
                flags |= Model.SNEAK;
            }
            if (pl.isSprinting()) {
                flags |= Model.SPRINT;
            }
        }
        if (e instanceof LivingEntity le) {
            if (le.isSwimming()) {
                flags |= Model.SWIM;
            }
            if (le.isGliding()) {
                flags |= Model.GLIDE;
            }
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                hand = enc(eq.getItemInMainHand());
                helmet = enc(eq.getHelmet());
                chest = enc(eq.getChestplate());
                legs = enc(eq.getLeggings());
                boots = enc(eq.getBoots());
            }
            health = (float) le.getHealth();
            AttributeInstance maxAttr = le.getAttribute(Attribute.MAX_HEALTH);
            maxHealth = maxAttr == null ? 0f : (float) maxAttr.getValue();
            AttributeInstance armorAttr = le.getAttribute(Attribute.ARMOR);
            armor = armorAttr == null ? 0f : (float) armorAttr.getValue();
        }
        if (pendingSwing.contains(id)) {
            flags |= Model.SWING;
        }
        if (pendingHurt.contains(id)) {
            flags |= Model.HURT;
        }
        if (id.equals(dead)) {
            flags |= Model.DEAD;
        }
        return new Model.State(id, kind, e.getType().name(), name,
                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), flags,
                hand, helmet, chest, legs, boots, health, maxHealth, armor);
    }

    /**
     * Encodes an item as "B64:" + base64 of the serialized ItemStack so the replay can show
     * the exact item (enchant glint, armor trims, leather dye...). The same String instance is
     * reused for identical items, so it is stored only once in memory and in the .replay file.
     */
    private String enc(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "AIR";
        }
        ItemStack one = item.asOne();
        String s = encCache.get(one);
        if (s == null) {
            if (encCache.size() > 1024) {
                encCache.clear();
            }
            s = "B64:" + Base64.getEncoder().encodeToString(one.serializeAsBytes());
            encCache.put(one, s);
        }
        return s;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            pendingSwing.add(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        pendingHurt.add(e.getEntity().getUniqueId());
        if (e instanceof EntityDamageByEntityEvent ev && ev.getDamager() instanceof LivingEntity attacker) {
            pendingSwing.add(attacker.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        rings.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld() || from.distanceSquared(to) > radius * radius) {
            rings.remove(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        ArrayList<Model.Frame> frames = new ArrayList<>();
        ArrayDeque<Model.Frame> ring = rings.remove(victim.getUniqueId());
        if (ring != null) {
            frames.addAll(ring);
        }
        frames.add(capture(victim, victim.getUniqueId()));

        Entity killer = null;
        String cause = "UNKNOWN";
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last != null) {
            cause = last.getCause().name();
            if (last instanceof EntityDamageByEntityEvent ev) {
                Entity d = ev.getDamager();
                if (d instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) {
                    d = shooter;
                }
                killer = d;
            }
        }
        String killerName;
        if (killer == null) {
            killerName = cause;
        } else if (killer instanceof Player kp) {
            killerName = kp.getName();
        } else {
            killerName = killer.getType().name();
        }

        HashMap<UUID, String> skins = new HashMap<>();
        for (Model.Frame f : frames) {
            for (Model.State s : f.states()) {
                if (s.kind() != Model.PLAYER || skins.containsKey(s.id())) {
                    continue;
                }
                Player p = Bukkit.getPlayer(s.id());
                skins.put(s.id(), p == null ? "" : skinOf(p));
            }
        }

        Model.Replay r = new Model.Replay(store.newId(), System.currentTimeMillis(),
                victim.getWorld().getName(), victim.getUniqueId(), victim.getName(),
                killer == null ? null : killer.getUniqueId(), killerName, cause, interval, frames, skins);
        store.add(r);

        if (plugin.getConfig().getBoolean("notify-staff", true)) {
            Component msg = Component.text("\u2620 ", NamedTextColor.RED)
                    .append(Component.text(r.victimName(), NamedTextColor.WHITE))
                    .append(Component.text(" died (" + r.killerName() + ") ", NamedTextColor.GRAY))
                    .append(Component.text("[\u25b6 Play]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/replay play " + r.id()))
                            .hoverEvent(HoverEvent.showText(Component.text("Play replay #" + r.id()))));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(DeathReplayPlugin.PERM)) {
                    p.sendMessage(msg);
                }
            }
        }
    }

    private static String skinOf(Player p) {
        for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
            if (pp.getName().equals("textures")) {
                return pp.getValue();
            }
        }
        return "";
    }
}
