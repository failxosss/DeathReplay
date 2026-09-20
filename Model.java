package cz.deathreplay;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Replay data model. Everything is immutable and serializable (replays are stored gzipped on disk). */
public final class Model {
    private Model() {}

    public static final byte PLAYER = 0, MOB = 1, ARROW = 2;
    public static final int SNEAK = 1, SPRINT = 2, SWIM = 4, GLIDE = 8, SWING = 16, HURT = 32, DEAD = 64;

    /** State of a single entity in a single frame. */
    public record State(UUID id, byte kind, String type, String name,
                        double x, double y, double z, float yaw, float pitch, int flags,
                        String hand, String helmet, String chest, String legs, String boots)
            implements Serializable {

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }

        public boolean sameGear(State o) {
            return hand.equals(o.hand) && helmet.equals(o.helmet) && chest.equals(o.chest)
                    && legs.equals(o.legs) && boots.equals(o.boots);
        }
    }

    /** One snapshot: the first state is always the victim, followed by nearby entities. */
    public record Frame(List<State> states) implements Serializable {}

    public record Replay(int id, long time, String world,
                         UUID victim, String victimName,
                         UUID killer, String killerName, String cause,
                         int interval, List<Frame> frames, Map<UUID, String> skins)
            implements Serializable {

        public double seconds() {
            return Math.max(0, frames.size() - 1) * interval / 20.0;
        }
    }
}
