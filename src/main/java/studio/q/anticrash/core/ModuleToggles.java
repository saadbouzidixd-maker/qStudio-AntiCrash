package studio.q.anticrash.core;

import studio.q.anticrash.config.ConfigManager;

/**
 * Cached module enable-flags read from config.yml. Hot listeners read plain
 * volatile booleans instead of touching the YAML tree per event.
 */
public final class ModuleToggles {
    public volatile boolean packets;
    public volatile boolean movement;
    public volatile boolean items;
    public volatile boolean books;
    public volatile boolean signs;
    public volatile boolean chat;
    public volatile boolean chunks;
    public volatile boolean entities;
    public volatile boolean redstone;
    public volatile boolean memory;
    public volatile boolean connections;
    public volatile boolean physics;
    public volatile boolean hopperFlow;

    public void reload(ConfigManager config) {
        packets = config.getBool("packets.enabled", true);
        movement = config.getBool("movement.enabled", true);
        items = config.getBool("items.enabled", true);
        books = config.getBool("books.enabled", true);
        signs = config.getBool("signs.enabled", true);
        chat = config.getBool("chat.enabled", true);
        chunks = config.getBool("chunks.enabled", true);
        entities = config.getBool("entities.enabled", true);
        redstone = config.getBool("redstone.enabled", true);
        memory = config.getBool("memory.enabled", true);
        connections = config.getBool("connections.enabled", true);
        physics = config.getBool("redstone.block-physics.enabled", true);
        hopperFlow = config.getBool("redstone.hoppers.enabled", true);
    }
}
