package cz.deathreplay;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Model {
    public static final byte PLAYER = 0;
    public static final byte MOB = 1;
    public static final byte ARROW = 2;

    public static final int SNEAK = 1;
    public static final int SPRINT = 2;
    public static final int SWIM = 4;
    public static final int GLIDE = 8;
    public static final int SWING = 16;
    public static final int HURT = 32;
    public static final int DEAD = 64;

    private Model() {
    }

    public record Replay(int id, long time, String world, UUID victim, String victimName,
                         UUID killer, String killerName, String cause, int interval,
                         List<Frame> frames, Map<UUID, String> skins) implements Serializable {
        public double seconds() {
            return (double) (Math.max(0, frames.size() - 1) * interval) / 20.0;
        }
    }

    public record Frame(List<State> states) implements Serializable {
    }

    /**
     * hand/helmet/chest/legs/boots contain either a plain material name (old replays)
     * or "B64:" + base64 of a serialized ItemStack (exact item with enchants, trims, dye...).
     * health / maxHealth / armor are 0 in replays saved before this field existed.
     */
    public record State(UUID id, byte kind, String type, String name,
                        double x, double y, double z, float yaw, float pitch, int flags,
                        String hand, String helmet, String chest, String legs, String boots,
                        float health, float maxHealth, float armor) implements Serializable {
        public boolean has(int flag) {
            return (flags & flag) != 0;
        }

        public boolean sameGear(State o) {
            return hand.equals(o.hand) && helmet.equals(o.helmet) && chest.equals(o.chest)
                    && legs.equals(o.legs) && boots.equals(o.boots);
        }
    }
}
