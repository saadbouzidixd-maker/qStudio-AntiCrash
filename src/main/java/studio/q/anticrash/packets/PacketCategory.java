package studio.q.anticrash.packets;

import java.util.Locale;

/**
 * Client-bound packet categories used for rate limiting. Categories map to
 * concrete packet types inside the PacketEvents/ProtocolLib adapters. Names
 * are stable identifiers used in config.yml (packets.limits).
 */
public enum PacketCategory {
    MOVEMENT(250, 100),
    INTERACTION(100, 30),
    FLYING(250, 100),
    CHAT_COMMAND(20, 5),
    CHAT_MESSAGE(10, 5),
    TAB_COMPLETE(20, 5),
    PLUGIN_MESSAGE(20, 10),
    WINDOW_CLICK(60, 20),
    ENTITY_ACTION(40, 20),
    HELD_ITEM(20, 10),
    OTHER(120, 60);

    private final int defaultPerSecond;
    private final int defaultBurst;

    PacketCategory(int defaultPerSecond, int defaultBurst) {
        this.defaultPerSecond = defaultPerSecond;
        this.defaultBurst = defaultBurst;
    }

    public int defaultPerSecond() {
        return defaultPerSecond;
    }

    public int defaultBurst() {
        return defaultBurst;
    }

    public static PacketCategory parse(String name) {
        if (name == null) {
            return OTHER;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return OTHER;
        }
    }
}
