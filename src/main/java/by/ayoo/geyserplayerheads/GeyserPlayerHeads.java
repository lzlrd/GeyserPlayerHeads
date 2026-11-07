package by.ayoo.geyserplayerheads;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomSkullsEvent.SkullTextureType;
import org.geysermc.geyser.api.extension.Extension;

/**
 * Registers players' skins as Geyser custom skulls, so Bedrock players see player heads with the right face in
 * inventories, in hand and when worn. Geyser only accepts custom skulls while it starts, so each start registers the
 * current skin of every player in the server's user cache (Java players through Mojang, Bedrock players through the
 * GeyserMC skin API) plus every skin registered at an earlier start, which keeps older heads working after a player
 * changes skin.
 */
public final class GeyserPlayerHeads implements Extension {
	private static final Pattern CACHED_UUID = Pattern.compile("\"uuid\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
	private static final Pattern TEXTURES = Pattern.compile("\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");
	private static final Pattern SKIN_URL = Pattern.compile("\"SKIN\"\\s*:\\s*\\{.*?\"url\"\\s*:\\s*\"https?://textures\\.minecraft\\.net/texture/([0-9a-fA-F]+)\"", Pattern.DOTALL);
	private static final Pattern TEXTURE_ID = Pattern.compile("\"texture_id\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	// Lookups per start, newest players first, so a busy user cache can't hold up Geyser's start for long.
	private static final int MAX_LOOKUPS = 200;
	private static final String DEFAULT_CONFIG = """
			# Player list to look up, relative to the server folder.
			user-cache=usercache.json
			# Where skins are looked up. Only change these for testing.
			java-profile-url=https://sessionserver.mojang.com/session/minecraft/profile/
			bedrock-skin-url=https://api.geysermc.org/v2/skin/
			""";

	@Subscribe
	public void onDefineCustomSkulls(GeyserDefineCustomSkullsEvent event) {
		Properties config = config();
		Path historyFile = dataFolder().resolve("skins.txt");
		Map<UUID, Set<String>> history = readHistory(historyFile);
		List<UUID> players = readUserCache(Path.of(config.getProperty("user-cache")));
		Map<UUID, String> current = lookUp(players, config);
		current.forEach((uuid, hash) -> history.computeIfAbsent(uuid, key -> new LinkedHashSet<>()).add(hash));
		writeHistory(historyFile, history);
		Set<String> hashes = new LinkedHashSet<>();
		history.values().forEach(hashes::addAll);
		for (String hash : hashes)
			event.register(hash, SkullTextureType.SKIN_HASH);
		logger().info("Registered " + hashes.size() + " player skins as custom skulls (looked up " + current.size() + " of " + players.size() + " players)");
	}

	private Properties config() {
		Path file = dataFolder().resolve("config.properties");
		Properties config = new Properties();
		try {
			if (Files.notExists(file)) {
				Files.createDirectories(dataFolder());
				Files.writeString(file, DEFAULT_CONFIG);
			}
			try (Reader reader = Files.newBufferedReader(file)) {
				config.load(reader);
			}
		} catch (IOException e) {
			logger().warning("Could not read " + file + ", using defaults: " + e.getMessage());
		}
		Properties defaults = new Properties();
		try {
			defaults.load(new StringReader(DEFAULT_CONFIG));
		} catch (IOException ignored) {
			// A string reader doesn't throw.
		}
		defaults.forEach(config::putIfAbsent);
		return config;
	}

	private List<UUID> readUserCache(Path file) {
		List<UUID> players = new ArrayList<>();
		try {
			Matcher m = CACHED_UUID.matcher(Files.readString(file));
			Set<UUID> seen = new LinkedHashSet<>();
			while (m.find() && seen.size() < MAX_LOOKUPS)
				seen.add(UUID.fromString(m.group(1)));
			players.addAll(seen);
		} catch (IOException | IllegalArgumentException e) {
			logger().warning("Could not read the player list " + file.toAbsolutePath() + ": " + e.getMessage());
		}
		return players;
	}

	private Map<UUID, String> lookUp(List<UUID> players, Properties config) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
		Map<UUID, CompletableFuture<String>> requests = new LinkedHashMap<>();
		for (UUID uuid : players) {
			if (uuid.getMostSignificantBits() == 0) {
				// Floodgate gives Bedrock players a UUID made from their XUID.
				String xuid = Long.toUnsignedString(uuid.getLeastSignificantBits());
				requests.put(uuid, get(http, config.getProperty("bedrock-skin-url") + xuid).thenApply(GeyserPlayerHeads::bedrockSkin));
			} else if (uuid.version() == 4) {
				requests.put(uuid, get(http, config.getProperty("java-profile-url") + uuid.toString().replace("-", "")).thenApply(GeyserPlayerHeads::javaSkin));
			}
			// Other UUIDs come from offline-mode logins and have no skin to find.
		}
		Map<UUID, String> skins = new LinkedHashMap<>();
		long deadline = System.nanoTime() + TIMEOUT.multipliedBy(2).toNanos();
		requests.forEach((uuid, request) -> {
			try {
				String hash = request.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				if (hash != null)
					skins.put(uuid, hash.toLowerCase(Locale.ROOT));
			} catch (Exception e) {
				logger().warning("Could not look up the skin of " + uuid + ": " + e);
			}
		});
		return skins;
	}

	private static CompletableFuture<String> get(HttpClient http, String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", "GeyserPlayerHeads").GET().build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> response.statusCode() == 200 ? response.body() : null);
	}

	static String javaSkin(String profile) {
		if (profile == null)
			return null;
		Matcher value = TEXTURES.matcher(profile);
		if (!value.find())
			return null;
		Matcher url = SKIN_URL.matcher(new String(Base64.getDecoder().decode(value.group(1)), StandardCharsets.UTF_8));
		return url.find() ? url.group(1) : null;
	}

	// "is_steve" in this response is the arm model (classic rather than slim), not the default skin, so it is ignored.
	static String bedrockSkin(String skin) {
		if (skin == null)
			return null;
		Matcher id = TEXTURE_ID.matcher(skin);
		return id.find() ? id.group(1) : null;
	}

	private Map<UUID, Set<String>> readHistory(Path file) {
		Map<UUID, Set<String>> history = new TreeMap<>();
		if (Files.notExists(file))
			return history;
		try {
			for (String line : Files.readAllLines(file)) {
				String[] parts = line.trim().split("\\s+");
				if (parts.length == 2 && parts[1].matches("[0-9a-f]+"))
					history.computeIfAbsent(UUID.fromString(parts[0]), key -> new LinkedHashSet<>()).add(parts[1]);
			}
		} catch (IOException | IllegalArgumentException e) {
			logger().warning("Could not read " + file + ": " + e.getMessage());
		}
		return history;
	}

	private void writeHistory(Path file, Map<UUID, Set<String>> history) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				writer.write("# Every skin registered so far, one \"uuid skin-hash\" per line. Delete a line to drop that skin.\n");
				for (Map.Entry<UUID, Set<String>> entry : history.entrySet())
					for (String hash : entry.getValue())
						writer.write(entry.getKey() + " " + hash + "\n");
			}
		} catch (IOException e) {
			logger().warning("Could not save " + file + ": " + e.getMessage());
		}
	}
}
