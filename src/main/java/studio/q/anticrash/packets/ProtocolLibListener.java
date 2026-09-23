package studio.q.anticrash.packets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerOptions;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;

import org.bukkit.entity.Player;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.MainThread;
import studio.q.anticrash.core.ProtectionEngine;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * ProtocolLib fallback packet layer. ProtocolLib's PacketEvent does not expose
 * a per-packet byte length, so this layer enforces per-category and global
 * rate limits only; size-based checks remain PacketEvents-specific and are
 * documented in the README. If ProtocolLib is present but PacketEvents is
 * missing, this adapter provides the flood-protection baseline.
 */
public final class ProtocolLibListener {
    private static final List<PacketType> WATCHED = Arrays.asList(
            PacketType.Play.Client.POSITION,
            PacketType.Play.Client.POSITION_LOOK,
            PacketType.Play.Client.LOOK,
            PacketType.Play.Client.GROUND,
            PacketType.Play.Client.ARM_ANIMATION,
            PacketType.Play.Client.BLOCK_DIG,
            PacketType.Play.Client.USE_ITEM,
            PacketType.Play.Client.BLOCK_PLACE,
            PacketType.Play.Client.CHAT_COMMAND,
            PacketType.Play.Client.CHAT_COMMAND_SIGNED,
            PacketType.Play.Client.CHAT,
            PacketType.Play.Client.TAB_COMPLETE,
            PacketType.Play.Client.CUSTOM_PAYLOAD,
            PacketType.Play.Client.WINDOW_CLICK,
            PacketType.Play.Client.ENTITY_ACTION,
            PacketType.Play.Client.CLIENT_COMMAND,
            PacketType.Play.Client.SPECTATE,
            PacketType.Play.Client.HELD_ITEM_SLOT);

    private final ProtectionEngine engine;
    private final PacketGuard guard;
    private final MainThread mainThread;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private boolean registered;

    public ProtocolLibListener(org.bukkit.plugin.java.JavaPlugin plugin, ProtectionEngine engine,
                               PacketGuard guard, MainThread mainThread) {
        this.plugin = plugin;
        this.engine = engine;
        this.guard = guard;
        this.mainThread = mainThread;
    }

    public boolean register() {
        if (registered) {
            return true;
        }
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketAdapter adapter = new PacketAdapter(plugin, ListenerPriority.LOW,
                    WATCHED, ListenerOptions.ASYNC) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    handle(event);
                }
            };
            pm.addPacketListener(adapter);
            registered = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void handle(PacketEvent event) {
        if (!guard.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        UUID pid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        guard.recordPacket(event.getPacketType().name());

        PacketCategory category = categorize(event.getPacketType());
        PacketGuard.Verdict v = guard.checkRate(pid, name, category, now);
        if (v.blocked()) {
            event.setCancelled(true);
            if (v.report()) {
                Detection d = Detection.builder(pid, name, "PacketRate", "packet-flood")
                        .detail("Rate limit exceeded for category " + category.name().toLowerCase(Locale.ROOT))
                        .actual(v.actual())
                        .limit(v.limit())
                        .severity(Severity.HIGH)
                        .build();
                engine.handle(d);
            }
        }
    }

    private PacketCategory categorize(PacketType type) {
        if (type == PacketType.Play.Client.POSITION
                || type == PacketType.Play.Client.POSITION_LOOK
                || type == PacketType.Play.Client.LOOK
                || type == PacketType.Play.Client.GROUND) {
            return PacketCategory.MOVEMENT;
        }
        if (type == PacketType.Play.Client.ARM_ANIMATION
                || type == PacketType.Play.Client.BLOCK_DIG
                || type == PacketType.Play.Client.USE_ITEM
                || type == PacketType.Play.Client.BLOCK_PLACE) {
            return PacketCategory.INTERACTION;
        }
        if (type == PacketType.Play.Client.CHAT_COMMAND
                || type == PacketType.Play.Client.CHAT_COMMAND_SIGNED
                || type == PacketType.Play.Client.CHAT) {
            return PacketCategory.CHAT_COMMAND;
        }
        if (type == PacketType.Play.Client.TAB_COMPLETE) {
            return PacketCategory.TAB_COMPLETE;
        }
        if (type == PacketType.Play.Client.CUSTOM_PAYLOAD) {
            return PacketCategory.PLUGIN_MESSAGE;
        }
        if (type == PacketType.Play.Client.WINDOW_CLICK) {
            return PacketCategory.WINDOW_CLICK;
        }
        if (type == PacketType.Play.Client.ENTITY_ACTION) {
            return PacketCategory.ENTITY_ACTION;
        }
        if (type == PacketType.Play.Client.HELD_ITEM_SLOT) {
            return PacketCategory.HELD_ITEM;
        }
        return PacketCategory.OTHER;
    }

    public void unregister() {
        if (registered) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListeners(plugin);
                registered = false;
            } catch (Throwable ignored) {
            }
        }
    }
}
