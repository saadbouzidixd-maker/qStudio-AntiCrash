package studio.q.anticrash.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of protection modules used by /qanticrash modules and status.
 * Registration happens at startup; entries are immutable afterwards.
 */
public final class ModuleRegistry {
    /** Runtime view of a module for diagnostics. */
    public record Entry(String name, boolean enabled, String description, String layer) {
    }

    private final Map<String, Entry> modules = new ConcurrentHashMap<>();

    public void register(String name, boolean enabled, String description, String layer) {
        modules.put(name.toLowerCase(Locale.ROOT),
                new Entry(name, enabled, description == null ? "" : description, layer == null ? "bukkit" : layer));
    }

    public void setEnabled(String name, boolean enabled) {
        Entry old = modules.get(name.toLowerCase(Locale.ROOT));
        if (old != null) {
            modules.put(name.toLowerCase(Locale.ROOT), new Entry(old.name(), enabled, old.description(), old.layer()));
        }
    }

    public boolean isEnabled(String name) {
        Entry e = modules.get(name.toLowerCase(Locale.ROOT));
        return e != null && e.enabled();
    }

    public List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(modules.values());
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    public int count() {
        return modules.size();
    }

    public long enabledCount() {
        return modules.values().stream().filter(Entry::enabled).count();
    }
}
