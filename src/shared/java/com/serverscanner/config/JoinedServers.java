package com.serverscanner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.serverscanner.Log;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every server you have joined through the finder, so the list can mark them and you can look them
 * up again later.
 *
 * <p>Random results are unrepeatable by nature — once a server scrolls away you will probably never
 * be shown it again — so anything you actually joined is worth keeping.
 */
public final class JoinedServers {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("randomserverfinder-joined.json");

	/** Oldest entries are dropped past this, so the file cannot grow without bound. */
	private static final int MAX_ENTRIES = 500;

	private static JoinedServers instance;

	/** Keyed by address so lookups during rendering are O(1). */
	private final Map<String, Entry> byAddress = new LinkedHashMap<>();

	public static class Entry {
		public String address;
		public String name;
		public String version;
		public long firstJoined;
		public long lastJoined;
		public int joinCount;

		Entry(String address, String name, String version, long now) {
			this.address = address;
			this.name = name;
			this.version = version;
			this.firstJoined = now;
			this.lastJoined = now;
			this.joinCount = 1;
		}
	}

	public static JoinedServers get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean hasJoined(String address) {
		return byAddress.containsKey(address);
	}

	public Entry find(String address) {
		return byAddress.get(address);
	}

	/** Most recently joined first. */
	public List<Entry> recent() {
		List<Entry> out = new ArrayList<>(byAddress.values());
		out.sort((a, b) -> Long.compare(b.lastJoined, a.lastJoined));
		return out;
	}

	public int size() {
		return byAddress.size();
	}

	/** Records a join, or bumps the counters if this server is already known. */
	public void record(String address, String name, String version) {
		long now = System.currentTimeMillis() / 1000L;
		Entry existing = byAddress.get(address);
		if (existing != null) {
			existing.lastJoined = now;
			existing.joinCount++;
			if (name != null && !name.isBlank()) existing.name = name;
			if (version != null && !version.isBlank()) existing.version = version;
		} else {
			byAddress.put(address, new Entry(address, name, version, now));
			trim();
		}
		save();
	}

	public void forget(String address) {
		if (byAddress.remove(address) != null) {
			save();
		}
	}

	public void clear() {
		byAddress.clear();
		save();
	}

	private void trim() {
		if (byAddress.size() <= MAX_ENTRIES) return;
		List<Entry> ordered = recent();
		byAddress.clear();
		for (int i = 0; i < Math.min(MAX_ENTRIES, ordered.size()); i++) {
			byAddress.put(ordered.get(i).address, ordered.get(i));
		}
	}

	// --- Persistence ---------------------------------------------------------------------------

	private static JoinedServers load() {
		JoinedServers joined = new JoinedServers();
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				Map<String, Entry> stored = GSON.fromJson(reader,
						new TypeToken<HashMap<String, Entry>>() { }.getType());
				if (stored != null) {
					stored.forEach((address, entry) -> {
						if (entry != null && address != null) {
							entry.address = address;
							joined.byAddress.put(address, entry);
						}
					});
				}
			} catch (Exception e) {
				Log.LOGGER.warn("Could not read {}", PATH, e);
			}
		}
		return joined;
	}

	private void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(byAddress, writer);
			}
		} catch (Exception e) {
			Log.LOGGER.error("Could not save {}", PATH, e);
		}
	}
}
