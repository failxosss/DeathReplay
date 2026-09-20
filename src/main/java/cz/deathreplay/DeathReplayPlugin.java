package cz.deathreplay;

import cz.deathreplay.Model.Replay;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class DeathReplayPlugin extends JavaPlugin implements Listener {
    /** The only permission of the plugin. Granted to operators by default. */
    public static final String PERM = "deathreplay.use";

    private ReplayStore store;
    private final Map<UUID, ReplaySession> sessions = new HashMap<>();
    /** UUIDs of the fake entities created by playback, so the recorder never records them. */
    private final Set<UUID> fake = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        store = new ReplayStore(this);
        store.load();

        Recorder recorder = new Recorder(this, store);
        getServer().getPluginManager().registerEvents(recorder, this);
        getServer().getPluginManager().registerEvents(this, this);
        recorder.start();

        ReplayCommand cmd = new ReplayCommand(this, store);
        PluginCommand pc = Objects.requireNonNull(getCommand("replay"), "command 'replay' missing from plugin.yml");
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        for (ReplaySession s : new ArrayList<>(sessions.values())) {
            s.stop();
        }
        if (store != null) {
            store.close();
        }
    }

    public ReplaySession session(Player p) {
        return sessions.get(p.getUniqueId());
    }

    public void start(Player p, Replay r) {
        stopSession(p);
        ReplaySession s = new ReplaySession(this, p, r);
        sessions.put(p.getUniqueId(), s);
        try {
            s.start();
        } catch (RuntimeException ex) {
            s.stop();
            throw ex;
        }
    }

    public void stopSession(Player p) {
        ReplaySession s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.stop();
        }
    }

    void released(UUID viewer, ReplaySession s) {
        sessions.remove(viewer, s);
    }

    public boolean isFake(Entity e) {
        return fake.contains(e.getUniqueId());
    }

    void markFake(UUID id) {
        fake.add(id);
    }

    void unmarkFake(UUID id) {
        fake.remove(id);
    }

    /** If an admin leaves in the middle of a replay, their original game mode and location are restored. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stopSession(e.getPlayer());
    }
}
