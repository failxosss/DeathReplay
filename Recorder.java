package cz.deathreplay;

import com.destroystokyo.paper.profile.ProfileProperty;
import cz.deathreplay.Model.Frame;
import cz.deathreplay.Model.Replay;
import cz.deathreplay.Model.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Continuously records a ring buffer of snapshots around every player.
 * When a player dies, the buffer is saved as a {@link Replay}.
 */
public final class Recorder implements Listener {
    private final DeathReplayPlugin plugin;
    private final ReplayStore store;

    private final Map<UUID, ArrayDeque<Frame>> rings = new HashMap<>();
    /** Events collected since the last snapshot; cleared after every snapshot. */
    private final Set<UUID> pendingSwing = new HashSet<>();
    private final Set<UUID> pendingHurt = new HashSet<>();

    private int interval;
    private int maxFrames;
    private double radius;
    private int maxEntities;

    public Recorder(DeathReplayPlugin plugin, ReplayStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void start() {
        var cfg = plugin.getConfig();
        interval = Math.max(1, cfg.getInt("record.interval-ticks", 2));
        int seconds = Math.max(3, cfg.getInt("record.duration-seconds", 10));
        maxFrames = Math.max(2, seconds * 20 / interval);
        radius = Math.max(4, cfg.getDouble("record.radius", 24));
        maxEntities = Math.max(2, cfg.getInt("record.max-entities", 20));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    // ---------------------------------------------------------------- recording

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) {
                // spectators (including admins currently watching a replay) are not recorded
                rings.remove(p.getUniqueId());
                continue;
            }
            ArrayDeque<Frame> ring = rings.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            ring.addLast(capture(p, null));
            while (ring.size() > maxFrames) {
                ring.removeFirst();
            }
        }
        pendingSwing.clear();
        pendingHurt.clear();
    }

    private Frame capture(Player center, UUID dead) {
        List<State> list = new ArrayList<>();
        list.add(stateOf(center, dead)); // the victim is always first
        for (Entity e : center.getNearbyEntities(radius, radius, radius)) {
            if (list.size() >= maxEntities) {
                break;
            }
            if (e == center || plugin.isFake(e) || !e.isValid() || !tracked(e)) {
                continue;
            }
            list.add(stateOf(e, dead));
        }
        return new Frame(list);
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
        for (MetadataValue v : p.getMetadata("vanished")) { // convention used by SuperVanish/PremiumVanish/Essentials
            if (v.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private State stateOf(Entity e, UUID dead) {
        UUID id = e.getUniqueId();
        Location l = e.getLocation();
        int flags = 0;
        byte kind = e instanceof Player ? Model.PLAYER : (e instanceof LivingEntity ? Model.MOB : Model.ARROW);
        String name = e instanceof Player pl ? pl.getName() : e.getType().name();
        String hand = "AIR", helmet = "AIR", chest = "AIR", legs = "AIR", boots = "AIR";

        if (e instanceof Player pl) {
            if (pl.isSneaking()) flags |= Model.SNEAK;
            if (pl.isSprinting()) flags |= Model.SPRINT;
        }
        if (e instanceof LivingEntity le) {
            if (le.isSwimming()) flags |= Model.SWIM;
            if (le.isGliding()) flags |= Model.GLIDE;
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                hand = mat(eq.getItemInMainHand());
                helmet = mat(eq.getHelmet());
                chest = mat(eq.getChestplate());
                legs = mat(eq.getLeggings());
                boots = mat(eq.getBoots());
            }
        }
        if (pendingSwing.contains(id)) flags |= Model.SWING;
        if (pendingHurt.contains(id)) flags |= Model.HURT;
        if (id.equals(dead)) flags |= Model.DEAD;

        return new State(id, kind, e.getType().name(), name,
                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), flags,
                hand, helmet, chest, legs, boots);
    }

    private static String mat(ItemStack item) {
        return item == null ? "AIR" : item.getType().name();
    }

    // ---------------------------------------------------------------- events

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

    /** After a teleport to another world or far away the replay would jump, so the buffer is dropped. */
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

        List<Frame> frames = new ArrayList<>();
        ArrayDeque<Frame> ring = rings.remove(victim.getUniqueId());
        if (ring != null) {
            frames.addAll(ring);
        }
        frames.add(capture(victim, victim.getUniqueId())); // last frame = the moment of death

        // who killed the player
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
        String killerName = killer == null ? cause
                : (killer instanceof Player kp ? kp.getName() : killer.getType().name());

        // player skins (so the replay still shows their heads after they disconnect)
        Map<UUID, String> skins = new HashMap<>();
        for (Frame f : frames) {
            for (State s : f.states()) {
                if (s.kind() == Model.PLAYER && !skins.containsKey(s.id())) {
                    Player p = Bukkit.getPlayer(s.id());
                    skins.put(s.id(), p == null ? "" : skinOf(p));
                }
            }
        }

        Replay r = new Replay(store.newId(), System.currentTimeMillis(), victim.getWorld().getName(),
                victim.getUniqueId(), victim.getName(),
                killer == null ? null : killer.getUniqueId(), killerName, cause,
                interval, frames, skins);
        store.add(r);

        if (plugin.getConfig().getBoolean("notify-staff", true)) {
            Component msg = Component.text("☠ ", NamedTextColor.RED)
                    .append(Component.text(r.victimName(), NamedTextColor.WHITE))
                    .append(Component.text(" died (" + r.killerName() + ") ", NamedTextColor.GRAY))
                    .append(Component.text("[▶ Play]", NamedTextColor.GREEN)
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
