package cz.deathreplay;

import com.destroystokyo.paper.profile.PlayerProfile;
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
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One playback for one admin.
 * All "actors" are real entities that only {@link #viewer} can see (setVisibleByDefault(false) + showEntity).
 * Players are shown as armor stands wearing the player's head and armor; mobs are the same mob type without AI.
 */
public final class ReplaySession {
    private static final int SWING_TICKS = 4;

    private final DeathReplayPlugin plugin;
    private final Player viewer;
    private final Replay replay;
    private final List<Frame> frames;
    private final List<Map<UUID, State>> index = new ArrayList<>();

    private final Map<UUID, Entity> actors = new HashMap<>();
    private final Map<UUID, State> gear = new HashMap<>();
    private final Map<UUID, Integer> swing = new HashMap<>();
    private final Map<UUID, ItemStack> heads = new HashMap<>();
    private final Set<UUID> failed = new HashSet<>();
    private final Set<UUID> deadShown = new HashSet<>();

    private final Location origin;
    private final GameMode originMode;

    private BukkitTask task;
    private double cursor = 0;
    private double speed = 1;
    private boolean paused = false;
    private boolean stopped = false;
    private int lastIdx = -1;
    private int ticks = 0;
    private UUID cameraTarget;
    private String cameraName = "victim";

    public ReplaySession(DeathReplayPlugin plugin, Player viewer, Replay replay) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.replay = replay;
        this.frames = replay.frames();
        for (Frame f : frames) {
            Map<UUID, State> m = new HashMap<>();
            for (State s : f.states()) {
                m.put(s.id(), s);
            }
            index.add(m);
        }
        this.origin = viewer.getLocation().clone();
        this.originMode = viewer.getGameMode();
        this.cameraTarget = replay.victim();
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        World w = Bukkit.getWorld(replay.world());
        if (w == null) {
            throw new IllegalStateException("World '" + replay.world() + "' is not loaded");
        }
        State first = frames.get(0).states().get(0);
        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.teleport(new Location(w, first.x(), first.y() + 2, first.z(), first.yaw(), first.pitch()));

        render();
        sendControls();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (task != null) {
            task.cancel();
        }
        for (Entity e : actors.values()) {
            discard(e);
        }
        actors.clear();
        if (viewer.isOnline()) {
            viewer.setSpectatorTarget(null);
            viewer.setGameMode(originMode);
            viewer.teleport(origin);
            viewer.sendActionBar(Component.empty());
        }
        plugin.released(viewer.getUniqueId(), this);
    }

    private void discard(Entity e) {
        plugin.unmarkFake(e.getUniqueId());
        e.remove();
    }

    // ---------------------------------------------------------------- controls

    public boolean togglePause() {
        paused = !paused;
        return paused;
    }

    public void setSpeed(double s) {
        speed = Math.max(0.1, Math.min(4.0, s));
    }

    public void seek(double seconds) {
        int last = frames.size() - 1;
        cursor = Math.max(0, Math.min(last, cursor + seconds * 20.0 / replay.interval()));
        lastIdx = (int) Math.floor(cursor); // do not re-fire old events after seeking
    }

    public void restart() {
        cursor = 0;
        lastIdx = -1;
        paused = false;
    }

    /** @return false if that camera does not exist (e.g. the killer was not an entity). */
    public boolean camera(String mode) {
        switch (mode) {
            case "victim" -> {
                cameraTarget = replay.victim();
                cameraName = "victim";
            }
            case "killer" -> {
                if (replay.killer() == null) {
                    return false;
                }
                cameraTarget = replay.killer();
                cameraName = "killer";
            }
            case "free" -> {
                cameraTarget = null;
                cameraName = "free";
            }
            default -> {
                return false;
            }
        }
        Entity e = cameraTarget == null ? null : actors.get(cameraTarget);
        viewer.setSpectatorTarget(e); // null = free camera
        return true;
    }

    private void attachLater(Entity actor) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!stopped && actor.isValid()) {
                viewer.setSpectatorTarget(actor);
            }
        }, 2L);
    }

    // ---------------------------------------------------------------- playback

    private void tick() {
        if (!viewer.isOnline()) {
            stop();
            return;
        }
        int last = frames.size() - 1;
        if (!paused) {
            cursor = Math.min(last, cursor + speed / replay.interval());
            if (cursor >= last) {
                paused = true; // stops at the end; you can still seek backwards
            }
            swing.replaceAll((k, v) -> Math.max(0, v - 1));
        }
        render();
        if (ticks++ % 4 == 0) {
            sendTimeline(last);
        }
    }

    private void render() {
        int last = frames.size() - 1;
        int idx = Math.min((int) Math.floor(cursor), last);
        double t = idx >= last ? 0 : cursor - idx;
        Frame a = frames.get(idx);
        Map<UUID, State> next = index.get(Math.min(idx + 1, last));

        Set<UUID> present = new HashSet<>();
        for (State s : a.states()) {
            UUID id = s.id();
            if (failed.contains(id)) {
                continue;
            }
            Entity actor = actors.get(id);
            if (actor == null || !actor.isValid()) {
                actor = spawn(s);
                if (actor == null) {
                    failed.add(id); // spawn was cancelled by another plugin or failed; do not retry every tick
                    continue;
                }
                actors.put(id, actor);
                if (id.equals(cameraTarget)) {
                    attachLater(actor);
                }
            }
            present.add(id);
            place(actor, s, next.getOrDefault(id, s), t);
            State applied = gear.get(id);
            if (applied == null || !applied.sameGear(s)) {
                applyGear(actor, s);
            }
        }

        // entities that are not in this frame (they left the recorded area)
        for (Iterator<Map.Entry<UUID, Entity>> it = actors.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Entity> en = it.next();
            if (!present.contains(en.getKey())) {
                discard(en.getValue());
                gear.remove(en.getKey());
                it.remove();
            }
        }

        // events of the frames we just passed
        if (idx > lastIdx) {
            for (int k = lastIdx + 1; k <= idx; k++) {
                for (State s : frames.get(k).states()) {
                    Entity actor = actors.get(s.id());
                    if (actor != null) {
                        fire(actor, s);
                    }
                }
            }
            lastIdx = idx;
        }
    }

    private void place(Entity actor, State a, State b, double t) {
        Location loc = new Location(actor.getWorld(),
                lerp(a.x(), b.x(), t), lerp(a.y(), b.y(), t), lerp(a.z(), b.z(), t),
                lerpAngle(a.yaw(), b.yaw(), t), (float) lerp(a.pitch(), b.pitch(), t));
        actor.teleport(loc);

        if (actor instanceof ArmorStand stand) {
            pose(stand, a, b, loc.getPitch());
            boolean dead = a.has(Model.DEAD);
            if (dead && deadShown.add(a.id())) {
                stand.customName(label(a, true));
            } else if (!dead && deadShown.remove(a.id())) {
                stand.customName(label(a, false));
            }
        }
    }

    private void pose(ArmorStand stand, State a, State b, float pitch) {
        boolean moving = Math.hypot(b.x() - a.x(), b.z() - a.z()) > 0.05;
        double phase = moving ? Math.sin(cursor * replay.interval() * 0.6) * 0.7 : 0;
        double lean = (a.has(Model.GLIDE) || a.has(Model.SWIM)) ? 90 : (a.has(Model.SNEAK) ? 25 : 0);
        int sw = swing.getOrDefault(a.id(), 0);
        double rightArm = sw > 0 ? Math.toRadians(-100 + (SWING_TICKS - sw) * 10) : -phase;

        stand.setHeadPose(new EulerAngle(Math.toRadians(pitch), 0, 0));
        stand.setBodyPose(new EulerAngle(Math.toRadians(lean), 0, 0));
        stand.setLeftLegPose(new EulerAngle(-phase, 0, 0));
        stand.setRightLegPose(new EulerAngle(phase, 0, 0));
        stand.setLeftArmPose(new EulerAngle(phase, 0, 0));
        stand.setRightArmPose(new EulerAngle(rightArm, 0, 0));
    }

    private void fire(Entity actor, State s) {
        Location l = actor.getLocation().add(0, 1, 0);
        if (s.has(Model.SWING)) {
            if (actor instanceof ArmorStand) {
                swing.put(s.id(), SWING_TICKS);
            } else if (actor instanceof LivingEntity le) {
                le.swingMainHand();
            }
        }
        if (s.has(Model.HURT)) {
            viewer.spawnParticle(Particle.DAMAGE_INDICATOR, l, 6, 0.25, 0.3, 0.25, 0.1);
            viewer.playSound(l, Sound.ENTITY_PLAYER_HURT, 0.8f, 1f);
            if (actor instanceof LivingEntity le && !(actor instanceof ArmorStand)) {
                le.playHurtAnimation(0f);
            }
        }
        if (s.has(Model.DEAD)) {
            viewer.playSound(l, Sound.ENTITY_PLAYER_DEATH, 0.8f, 1f);
        }
    }

    // ---------------------------------------------------------------- entities

    private Entity spawn(State s) {
        World w = Bukkit.getWorld(replay.world());
        if (w == null) {
            return null;
        }
        Location loc = new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
        try {
            Entity e;
            if (s.kind() == Model.PLAYER) {
                e = w.spawn(loc, ArmorStand.class, stand -> {
                    prepare(stand);
                    stand.setArms(true);
                    stand.setBasePlate(false);
                    stand.setMarker(true);
                    stand.customName(label(s, s.has(Model.DEAD)));
                    stand.setCustomNameVisible(true);
                });
            } else {
                EntityType type = EntityType.valueOf(s.type());
                e = w.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.CUSTOM, this::prepare);
            }
            viewer.showEntity(plugin, e);
            applyGear(e, s);
            return e;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Called before the entity is added to the world, so nobody else ever sees it. */
    private void prepare(Entity en) {
        plugin.markFake(en.getUniqueId());
        en.setVisibleByDefault(false);
        en.setPersistent(false);
        en.setInvulnerable(true);
        en.setSilent(true);
        en.setGravity(false);
        if (en instanceof LivingEntity le) {
            le.setAI(false);
            le.setCollidable(false);
            le.setRemoveWhenFarAway(false);
        }
        if (en instanceof AbstractArrow arrow) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }
    }

    private void applyGear(Entity e, State s) {
        gear.put(s.id(), s);
        if (!(e instanceof LivingEntity le)) {
            return;
        }
        EntityEquipment eq = le.getEquipment();
        if (eq == null) {
            return;
        }
        ItemStack helmet = item(s.helmet());
        if (s.kind() == Model.PLAYER && helmet.getType() == Material.AIR) {
            helmet = head(s.id(), s.name()); // without a helmet the armor stand wears the player's head (skin)
        }
        eq.setHelmet(helmet);
        eq.setChestplate(item(s.chest()));
        eq.setLeggings(item(s.legs()));
        eq.setBoots(item(s.boots()));
        eq.setItemInMainHand(item(s.hand()));
    }

    private static ItemStack item(String material) {
        Material m = Material.matchMaterial(material);
        return new ItemStack(m == null ? Material.AIR : m);
    }

    private ItemStack head(UUID id, String name) {
        return heads.computeIfAbsent(id, k -> {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            String texture = replay.skins().getOrDefault(id, "");
            if (!texture.isEmpty()) {
                PlayerProfile profile = Bukkit.createProfile(id, name);
                profile.setProperty(new ProfileProperty("textures", texture));
                meta.setPlayerProfile(profile);
            } else {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            }
            head.setItemMeta(meta);
            return head;
        }).clone();
    }

    private Component label(State s, boolean dead) {
        NamedTextColor color = dead ? NamedTextColor.GRAY
                : (s.id().equals(replay.victim()) ? NamedTextColor.RED : NamedTextColor.WHITE);
        return Component.text((dead ? "✝ " : "") + s.name(), color);
    }

    // ---------------------------------------------------------------- UI

    private void sendTimeline(int last) {
        double now = cursor * replay.interval() / 20.0;
        double total = last * replay.interval() / 20.0;
        int bars = 20;
        int filled = last == 0 ? bars : (int) Math.round(bars * cursor / last);
        String text = String.format(Locale.ROOT, "%s %.1fs / %.1fs  %s%s  x%.2f  camera: %s",
                paused ? "⏸" : "▶", now, total,
                "▮".repeat(filled), "▯".repeat(bars - filled), speed, cameraName);
        viewer.sendActionBar(Component.text(text, NamedTextColor.YELLOW));
    }

    private void sendControls() {
        Component c = Component.text("Replay #" + replay.id() + " – " + replay.victimName() + "  ", NamedTextColor.GOLD)
                .append(btn("⏪ 2s", "/replay seek -2", "Back 2 seconds"))
                .append(btn("⏯", "/replay pause", "Pause / resume"))
                .append(btn("2s ⏩", "/replay seek 2", "Forward 2 seconds"))
                .append(btn("0.5x", "/replay speed 0.5", "Slow motion"))
                .append(btn("1x", "/replay speed 1", "Normal speed"))
                .append(btn("↺", "/replay restart", "Restart from the beginning"))
                .append(btn("victim", "/replay cam victim", "Victim's point of view"))
                .append(btn("killer", "/replay cam killer", "Attacker's point of view"))
                .append(btn("free", "/replay cam free", "Free camera"))
                .append(btn("⏹ stop", "/replay stop", "End the replay"));
        viewer.sendMessage(c);
    }

    private static Component btn(String label, String command, String hover) {
        return Component.text("[" + label + "]", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text(hover)))
                .clickEvent(ClickEvent.runCommand(command))
                .append(Component.space());
    }

    // ---------------------------------------------------------------- math

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static float lerpAngle(float a, float b, double t) {
        float d = (((b - a) % 360f) + 540f) % 360f - 180f;
        return (float) (a + d * t);
    }
}
