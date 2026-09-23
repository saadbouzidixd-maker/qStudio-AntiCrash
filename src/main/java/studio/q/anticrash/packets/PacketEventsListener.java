package studio.q.anticrash.packets;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;

import org.bukkit.entity.Player;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.MainThread;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.movement.MovementChecks;
import studio.q.anticrash.movement.MovementTracker;

import java.util.Locale;
import java.util.UUID;

/**
 * PacketEvents-backed packet listener. Every incoming play/config/login
 * packet passes here on the netty thread BEFORE the server processes it.
 *
 * Design:
 * - One listener instance for the whole plugin. No per-packet allocation
 *   except wrappers we explicitly create for payload inspection.
 * - Rate limiting uses the shared RateLimiter (token bucket, lock-free).
 * - Cancellation: PacketEvents allows setCancelled(true) on receive events,
 *   which drops the packet before server processing (verified against 2.13.0).
 */
public final class PacketEventsListener extends PacketListenerAbstract {
    /** Bridge to configured text/payload limits. Implemented by the plugin. */
    public interface TextLimits {
        int maxCommandLength();

        int maxChatLength();

        int maxTabLength();

        int maxPayloadBytes();

        boolean isChannelDenied(String channel);
    }

    private final ProtectionEngine engine;
    private final PacketGuard guard;
    private final MovementTracker movement;
    private final MainThread mainThread;
    private final TextLimits limits;

    public PacketEventsListener(ProtectionEngine engine, PacketGuard guard,
                                MovementTracker movement, MainThread mainThread, TextLimits limits) {
        super(PacketListenerPriority.LOW);
        this.engine = engine;
        this.guard = guard;
        this.movement = movement;
        this.mainThread = mainThread;
        this.limits = limits;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == null) {
            return;
        }
        guard.recordPacket(type.getName());
        if (!guard.enabled()) {
            return;
        }
        var user = event.getUser();
        if (user == null) {
            return;
        }
        UUID pid = user.getUUID();
        String name = user.getName() == null ? "unknown" : user.getName();
        long now = System.currentTimeMillis();

        // 1) Per-category rate limiting. Every packet counts once.
        PacketCategory category = categorize(type);
        PacketGuard.Verdict verdict = guard.checkRate(pid, name, category, now);
        if (verdict.blocked()) {
            event.setCancelled(true);
            if (verdict.report()) {
                Detection d = Detection.builder(pid, name, "PacketRate", "packet-flood")
                        .detail("Rate limit exceeded for category " + category.name().toLowerCase(Locale.ROOT))
                        .actual(verdict.actual())
                        .limit(verdict.limit())
                        .severity(Severity.HIGH)
                        .build();
                engine.handle(d);
            }
            return;
        }

        // 2) Category-specific deep checks.
        switch (category) {
            case MOVEMENT, FLYING -> checkMovement(event, pid, name, type, now);
            case CHAT_COMMAND -> checkChatCommand(event, pid, name);
            case CHAT_MESSAGE -> checkChatMessage(event, pid, name);
            case TAB_COMPLETE -> checkTabComplete(event, pid, name);
            case PLUGIN_MESSAGE -> checkPluginMessage(event, pid, name);
            case INTERACTION -> checkInteraction(event, pid, name);
            default -> {
            }
        }
    }

    private PacketCategory categorize(PacketTypeCommon type) {
        if (PacketType.Play.Client.PLAYER_POSITION.equals(type)
                || PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION.equals(type)
                || PacketType.Play.Client.PLAYER_ROTATION.equals(type)
                || PacketType.Play.Client.PLAYER_FLYING.equals(type)) {
            return PacketCategory.MOVEMENT;
        }
        if (PacketType.Play.Client.ANIMATION.equals(type)
                || PacketType.Play.Client.PLAYER_DIGGING.equals(type)
                || PacketType.Play.Client.USE_ITEM.equals(type)
                || PacketType.Play.Client.INTERACT_ENTITY.equals(type)) {
            return PacketCategory.INTERACTION;
        }
        if (PacketType.Play.Client.CHAT_COMMAND.equals(type)
                || PacketType.Play.Client.CHAT_COMMAND_UNSIGNED.equals(type)) {
            return PacketCategory.CHAT_COMMAND;
        }
        if (PacketType.Play.Client.CHAT_MESSAGE.equals(type)) {
            return PacketCategory.CHAT_MESSAGE;
        }
        if (PacketType.Play.Client.TAB_COMPLETE.equals(type)) {
            return PacketCategory.TAB_COMPLETE;
        }
        if (PacketType.Play.Client.PLUGIN_MESSAGE.equals(type)) {
            return PacketCategory.PLUGIN_MESSAGE;
        }
        if (PacketType.Play.Client.CLICK_WINDOW.equals(type)
                || PacketType.Play.Client.CLICK_WINDOW_BUTTON.equals(type)) {
            return PacketCategory.WINDOW_CLICK;
        }
        if (PacketType.Play.Client.ENTITY_ACTION.equals(type)) {
            return PacketCategory.ENTITY_ACTION;
        }
        if (PacketType.Play.Client.HELD_ITEM_CHANGE.equals(type)) {
            return PacketCategory.HELD_ITEM;
        }
        return PacketCategory.OTHER;
    }

    private void checkMovement(PacketReceiveEvent event, UUID pid, String name,
                               PacketTypeCommon type, long now) {
        if (type == PacketType.Play.Client.PLAYER_FLYING) {
            // Ground-only packet: no coordinates to validate.
            return;
        }
        WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
        if (!wrapper.hasPositionChanged() && !wrapper.hasRotationChanged()) {
            return;
        }
        var loc = wrapper.getLocation();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        float yaw = loc.getYaw();
        float pitch = loc.getPitch();

        // NaN/Infinity are unambiguous client faults: drop immediately.
        if (!MovementChecks.isFinite(x, y, z)) {
            event.setCancelled(true);
            Detection d = Detection.builder(pid, name, "Movement", "movement-invalid")
                    .detail("Non-finite coordinates in position packet")
                    .actual(MovementChecks.describeInvalid(x, y, z))
                    .limit("finite coordinates")
                    .severity(Severity.CRITICAL)
                    .build();
            engine.handle(d);
            return;
        }
        if (!MovementChecks.isFiniteRotation(yaw, pitch)) {
            event.setCancelled(true);
            Detection d = Detection.builder(pid, name, "Movement", "movement-invalid")
                    .detail("Non-finite rotation values")
                    .actual("yaw=" + yaw + " pitch=" + pitch)
                    .limit("finite rotation")
                    .severity(Severity.HIGH)
                    .build();
            engine.handle(d);
            return;
        }
        MovementTracker.Sample sample = new MovementTracker.Sample(x, y, z, yaw, pitch, now);
        MovementTracker.Analysis a = movement.observe(pid, sample);
        if (a.impossibleDelta()) {
            event.setCancelled(true);
            Detection d = Detection.builder(pid, name, "Movement", "movement-extreme-delta")
                    .detail("Movement delta exceeds physical bounds")
                    .actual(a.describe())
                    .limit(a.limit())
                    .severity(Severity.HIGH)
                    .build();
            engine.handle(d);
        } else if (a.rotationSpam()) {
            event.setCancelled(true);
            Detection d = Detection.builder(pid, name, "Movement", "movement-rotation-spam")
                    .detail("Extreme rotation rate")
                    .actual(a.describe())
                    .limit(a.limit())
                    .severity(Severity.MEDIUM)
                    .build();
            engine.handle(d);
        }
    }

    private void checkChatCommand(PacketReceiveEvent event, UUID pid, String name) {
        String command;
        try {
            WrapperPlayClientChatCommand wrapper = new WrapperPlayClientChatCommand(event);
            command = wrapper.getCommand();
        } catch (Exception ex) {
            // Malformed wrapper: let the server's own decoder handle/report it.
            return;
        }
        if (command == null) {
            return;
        }
        int max = limits.maxCommandLength();
        PacketGuard.Verdict v = guard.checkText(pid, name, "command", command, max);
        if (v.blocked()) {
            event.setCancelled(true);
            if (v.report()) {
                Detection d = Detection.builder(pid, name, "CommandGuard", "command-oversize")
                        .detail("Command text exceeded configured limits")
                        .actual(v.actual())
                        .limit(v.limit())
                        .severity(Severity.HIGH)
                        .build();
                engine.handle(d);
            }
        }
    }

    private void checkChatMessage(PacketReceiveEvent event, UUID pid, String name) {
        String message;
        try {
            WrapperPlayClientChatMessage wrapper = new WrapperPlayClientChatMessage(event);
            message = wrapper.getMessage();
        } catch (Exception ex) {
            return;
        }
        if (message == null) {
            return;
        }
        int max = limits.maxChatLength();
        PacketGuard.Verdict v = guard.checkText(pid, name, "chat", message, max);
        if (v.blocked()) {
            event.setCancelled(true);
            if (v.report()) {
                Detection d = Detection.builder(pid, name, "ChatGuard", "chat-oversize")
                        .detail("Chat message exceeded configured limits")
                        .actual(v.actual())
                        .limit(v.limit())
                        .severity(Severity.HIGH)
                        .build();
                engine.handle(d);
            }
        }
    }

    private void checkTabComplete(PacketReceiveEvent event, UUID pid, String name) {
        String text;
        try {
            WrapperPlayClientTabComplete wrapper = new WrapperPlayClientTabComplete(event);
            text = wrapper.getText();
        } catch (Exception ex) {
            return;
        }
        if (text == null) {
            return;
        }
        int max = limits.maxTabLength();
        PacketGuard.Verdict v = guard.checkText(pid, name, "tab", text, max);
        if (v.blocked()) {
            event.setCancelled(true);
            if (v.report()) {
                Detection d = Detection.builder(pid, name, "TabGuard", "tab-abuse")
                        .detail("Tab-complete text exceeded configured limits")
                        .actual(v.actual())
                        .limit(v.limit())
                        .severity(Severity.HIGH)
                        .build();
                engine.handle(d);
            }
        }
    }

    private void checkPluginMessage(PacketReceiveEvent event, UUID pid, String name) {
        String channel;
        int len;
        try {
            WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
            channel = wrapper.getChannelName();
            byte[] data = wrapper.getData();
            len = data == null ? 0 : data.length;
        } catch (Exception ex) {
            return;
        }
        int max = limits.maxPayloadBytes();
        boolean denied = limits.isChannelDenied(channel);
        PacketGuard.Verdict v = guard.checkPayload(pid, name, channel, len, max, denied);
        if (v.blocked()) {
            event.setCancelled(true);
            if (v.report()) {
                Detection d = Detection.builder(pid, name, "PayloadGuard", "payload-oversize")
                        .detail("Plugin message payload exceeded configured limits")
                        .actual(v.actual())
                        .limit(v.limit())
                        .severity(Severity.HIGH)
                        .build();
                engine.handle(d);
            }
        }
    }

    private void checkInteraction(PacketReceiveEvent event, UUID pid, String name) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            try {
                WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
                wrapper.getAction();
            } catch (Exception ex) {
                // Malformed interaction data: cancel before server decode.
                event.setCancelled(true);
                Detection d = Detection.builder(pid, name, "PacketGuard", "packet-malformed")
                        .detail("Malformed INTERACT_ENTITY packet")
                        .actual("exception during wrapper parse")
                        .limit("parseable packet")
                        .severity(Severity.HIGH)
                        .build();
                engine.handle(d);
            }
        }
    }

    /** Called by the plugin when a player quits: drop their tracking state. */
    public void handleQuit(UUID playerId) {
        movement.remove(playerId);
    }
}
