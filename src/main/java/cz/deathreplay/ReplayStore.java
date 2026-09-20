package cz.deathreplay;

import cz.deathreplay.Model.Replay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Keeps replays in memory and persists them (gzip) to plugins/DeathReplay/replays/. */
public final class ReplayStore {
    /** When loading, only our own classes and basic java.util / java.lang types are allowed. */
    private static final ObjectInputFilter FILTER =
            ObjectInputFilter.Config.createFilter("cz.deathreplay.*;java.util.*;java.lang.*;!*");
    private static final String EXT = ".replay";

    private final DeathReplayPlugin plugin;
    private final File dir;
    private final TreeMap<Integer, Replay> replays = new TreeMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DeathReplay-IO");
        t.setDaemon(true);
        return t;
    });
    private int nextId = 1;

    public ReplayStore(DeathReplayPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "replays");
    }

    public void load() {
        dir.mkdirs();
        long keepMs = plugin.getConfig().getLong("storage.keep-days", 14) * 86_400_000L;
        long cutoff = System.currentTimeMillis() - keepMs;
        File[] files = dir.listFiles((d, n) -> n.endsWith(EXT));
        if (files == null) {
            return;
        }
        for (File f : files) {
            nextId = Math.max(nextId, parseId(f) + 1);
            try (ObjectInputStream in = new ObjectInputStream(
                    new GZIPInputStream(new BufferedInputStream(new FileInputStream(f))))) {
                in.setObjectInputFilter(FILTER);
                Replay r = (Replay) in.readObject();
                if (r.time() < cutoff) {
                    f.delete();
                    continue;
                }
                replays.put(r.id(), r);
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not load replay " + f.getName() + ": " + ex);
            }
        }
        trim();
    }

    public int newId() {
        return nextId++;
    }

    public void add(Replay r) {
        replays.put(r.id(), r);
        trim();
        io.execute(() -> write(r));
    }

    public Replay get(int id) {
        return replays.get(id);
    }

    public boolean delete(int id) {
        if (replays.remove(id) == null) {
            return false;
        }
        io.execute(() -> file(id).delete());
        return true;
    }

    /** Newest replays first, optionally only those of one victim (by name). */
    public List<Replay> recent(int limit, String victimName) {
        List<Replay> out = new ArrayList<>();
        for (Map.Entry<Integer, Replay> e : replays.descendingMap().entrySet()) {
            Replay r = e.getValue();
            if (victimName != null && !r.victimName().equalsIgnoreCase(victimName)) {
                continue;
            }
            out.add(r);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void trim() {
        int max = plugin.getConfig().getInt("storage.max-replays", 50);
        while (replays.size() > max) {
            int oldest = replays.firstKey();
            replays.remove(oldest);
            io.execute(() -> file(oldest).delete());
        }
    }

    private File file(int id) {
        return new File(dir, id + EXT);
    }

    private static int parseId(File f) {
        String n = f.getName();
        try {
            return Integer.parseInt(n.substring(0, n.length() - EXT.length()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void write(Replay r) {
        try (ObjectOutputStream out = new ObjectOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file(r.id())))))) {
            out.writeObject(r);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save replay #" + r.id() + ": " + ex);
        }
    }
}
