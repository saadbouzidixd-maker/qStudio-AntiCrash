package studio.q.anticrash.protection;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.ratelimit.RateLimiter;

import java.util.Locale;
import java.util.UUID;

/**
 * Chat / command / inventory-click protection at the Bukkit layer.
 * The packet layer normally caps these earlier; this listener covers servers
 * without packet layers and re-checks what plugins themselves inject.
 */
public final class ChatCommandListener implements Listener {
    private static final int KEY_CHAT = 1;
    private static final int KEY_COMMAND = 2;
    private static final int KEY_CLICK = 3;

    private final ProtectionEngine engine;
    private final ModuleToggles toggles;
    private final RateLimiter chatLimiter;
    private final RateLimiter commandLimiter;
    private final RateLimiter clickLimiter;
    private volatile int maxChat = 256;
    private volatile int maxCommand = 256;
    private volatile int clicksPerSecond = 80;

    public ChatCommandListener(ProtectionEngine engine, ModuleToggles toggles) {
        this.engine = engine;
        this.toggles = toggles;
        this.chatLimiter = new RateLimiter(10, 15, 60_000);
        this.commandLimiter = new RateLimiter(20, 30, 60_000);
        this.clickLimiter = new RateLimiter(80, 120, 60_000);
    }

    public void configure(int maxChat, int maxCommand, int chatPerSecond, int commandPerSecond,
                          int clicksPerSecond) {
        this.maxChat = maxChat;
        this.maxCommand = maxCommand;
        this.chatLimiter.configure(chatPerSecond, chatPerSecond + 5, 60_000);
        this.commandLimiter.configure(commandPerSecond, commandPerSecond + 10, 60_000);
        this.clickLimiter.configure(clicksPerSecond, clicksPerSecond * 2, 60_000);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!toggles.chat) {
            return;
        }
        UUID pid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        String message = event.getMessage();
        if (message.length() > maxChat) {
            event.setCancelled(true);
            detect(pid, event.getPlayer().getName(), "chat-oversize",
                    "Chat message length exceeds limit",
                    "length=" + message.length(), "max-chat-length=" + maxChat, Severity.MEDIUM);
            return;
        }
        if (!chatLimiter.tryConsume(RateLimiter.key(pid.getMostSignificantBits(),
                (int) pid.getLeastSignificantBits(), KEY_CHAT), now)) {
            event.setCancelled(true);
            detect(pid, event.getPlayer().getName(), "chat-flood",
                    "Chat rate exceeded",
                    "rate exceeded", chatLimiterSummary(), Severity.MEDIUM);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!toggles.chat) {
            return;
        }
        UUID pid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        String message = event.getMessage();
        if (message.length() > maxCommand) {
            event.setCancelled(true);
            detect(pid, event.getPlayer().getName(), "command-oversize",
                    "Command length exceeds limit",
                    "length=" + message.length(), "max-command-length=" + maxCommand, Severity.MEDIUM);
            return;
        }
        if (!commandLimiter.tryConsume(RateLimiter.key(pid.getMostSignificantBits(),
                (int) pid.getLeastSignificantBits(), KEY_COMMAND), now)) {
            event.setCancelled(true);
            detect(pid, event.getPlayer().getName(), "command-flood",
                    "Command rate exceeded",
                    "rate exceeded", commandLimiterSummary(), Severity.MEDIUM);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!toggles.items) {
            return;
        }
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        UUID pid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (!clickLimiter.tryConsume(RateLimiter.key(pid.getMostSignificantBits(),
                (int) pid.getLeastSignificantBits(), KEY_CLICK), now)) {
            event.setCancelled(true);
            detect(pid, player.getName(), "click-flood",
                    "Inventory click rate exceeded",
                    "rate exceeded", clicksPerSecond + "/s", Severity.MEDIUM);
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current != null && current.getAmount() > current.getMaxStackSize()) {
            event.setCancelled(true);
            detect(pid, player.getName(), "item-amount", "Item stack amount exceeds max stack size",
                    "amount=" + current.getAmount(), "max=" + current.getMaxStackSize(), Severity.HIGH);
        }
    }

    private String chatLimiterSummary() {
        return (int) chatLimiter.perSecond() + "/s burst " + chatLimiter.burst();
    }

    private String commandLimiterSummary() {
        return (int) commandLimiter.perSecond() + "/s burst " + commandLimiter.burst();
    }

    private void detect(UUID pid, String name, String rule, String detail,
                        String actual, String limit, Severity severity) {
        Detection d = Detection.builder(pid, name, "ChatGuard", rule)
                .detail(detail)
                .actual(actual)
                .limit(limit)
                .severity(severity)
                .build();
        engine.handle(d);
    }
}
