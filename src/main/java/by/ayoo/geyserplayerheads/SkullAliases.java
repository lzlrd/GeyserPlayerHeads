package by.ayoo.geyserplayerheads;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Lets many head skins share one Bedrock custom skull for their item icon. Each custom skull costs Geyser roughly
 * 240 KB of heap (a custom block with 21 states for every Bedrock version it supports), which is too much for thousands
 * of variant heads, so a server can register one head per mob and alias the rest of that mob's heads to it.
 *
 * <p>Only item translation sees the aliases: icons in inventories, in hand, in item frames and on the ground show the
 * target's skull, and the item keeps its own name. Placed heads see only the skulls Geyser registered, so Geyser draws
 * an aliased head with its own skin once it is placed. Geyser has no API for this, so the aliases wrap its internal
 * custom skull registry after it has built the custom blocks, the items and the skull pack, and come out again before
 * a reload rebuilds them.
 */
final class SkullAliases {
	static final String FILE_NAME = "skull-aliases.txt";
	private static final Pattern HASH = Pattern.compile("[0-9a-f]+");
	private static final String HEADER = """
			# Skull aliases, one "skin-hash target-skin-hash" per line.
			# Bedrock players see the target's skull as the item icon of heads with the first skin; placed heads keep their own skin.
			# A line with a single skin hash registers that skin as its own skull.
			# GeyserPlayerHeads registers every target as a custom skull, so leave targets out of Geyser's custom_mappings.
			""";

	private final Map<String, String> aliases;
	private final Set<String> standalone;
	private ItemSkulls applied;

	private SkullAliases(Map<String, String> aliases, Set<String> standalone) {
		this.aliases = aliases;
		this.standalone = standalone;
	}

	/**
	 * Reads the alias file, writing an empty one with instructions if there is none.
	 */
	static SkullAliases read(Path folder, Consumer<String> warn) {
		Path file = folder.resolve(FILE_NAME);
		Map<String, String> aliases = new LinkedHashMap<>();
		Set<String> standalone = new LinkedHashSet<>();
		try {
			if (Files.notExists(file)) {
				Files.createDirectories(folder);
				Files.writeString(file, HEADER);
			}
			int lineNumber = 0;
			for (String line : Files.readAllLines(file)) {
				lineNumber++;
				String trimmed = line.strip();
				if (trimmed.isEmpty() || trimmed.startsWith("#"))
					continue;
				String[] parts = trimmed.toLowerCase(Locale.ROOT).split("\\s+");
				if (parts.length > 2 || !HASH.matcher(parts[0]).matches() || (parts.length == 2 && !HASH.matcher(parts[1]).matches())) {
					warn.accept("Ignoring line " + lineNumber + " of " + FILE_NAME + ": expected \"skin-hash target-skin-hash\" or one skin hash");
					continue;
				}
				if (parts.length == 1 || parts[0].equals(parts[1]))
					standalone.add(parts[0]);
				else
					aliases.put(parts[0], parts[1]);
			}
		} catch (IOException e) {
			warn.accept("Could not read " + file + ": " + e.getMessage());
		}
		return new SkullAliases(aliases, standalone);
	}

	/**
	 * The skins Geyser has to register as custom skulls: every alias target and every single-hash line.
	 */
	Collection<String> targets() {
		Set<String> targets = new LinkedHashSet<>(this.standalone);
		targets.addAll(this.aliases.values());
		return targets;
	}

	int size() {
		return this.aliases.size();
	}

	/**
	 * Wraps Geyser's custom skull registry so item translation sees the aliases. Skins Geyser registered in their own
	 * right keep their own skull. Returns a line for the log, or null when there is nothing to report.
	 */
	String apply() throws ReflectiveOperationException {
		if (this.aliases.isEmpty())
			return null;
		Object registry = registry();
		Map<String, Object> registered = skulls(registry);
		if (registered instanceof ItemSkulls wrapped)
			registered = wrapped.registered;
		Map<String, Object> targets = new HashMap<>();
		Map<Object, Boolean> usedTargets = new IdentityHashMap<>();
		int ownSkull = 0;
		int missingTarget = 0;
		for (Map.Entry<String, String> alias : this.aliases.entrySet()) {
			Object target = registered.get(alias.getValue());
			if (target == null) {
				missingTarget++;
			} else if (registered.containsKey(alias.getKey())) {
				ownSkull++;
			} else {
				targets.put(alias.getKey(), target);
				usedTargets.put(target, true);
			}
		}
		this.applied = new ItemSkulls(registered, targets);
		set(registry, this.applied);
		StringBuilder line = new StringBuilder("Showing " + targets.size() + " head skins with " + plural(usedTargets.size(), "custom skull") + " in item icons");
		if (ownSkull > 0)
			line.append(", left ").append(ownSkull).append(" that have their own skull");
		if (missingTarget > 0)
			line.append(", skipped ").append(missingTarget).append(" whose target Geyser did not register");
		return line.toString();
	}

	/**
	 * Takes the wrapper out again, so a Geyser reload rebuilds from the registered skulls only.
	 */
	void remove() throws ReflectiveOperationException {
		if (this.applied == null)
			return;
		Object registry = registry();
		if (skulls(registry) == this.applied)
			set(registry, this.applied.registered);
		this.applied = null;
	}

	private static String plural(int count, String noun) {
		return count + " " + noun + (count == 1 ? "" : "s");
	}

	private static Object registry() throws ReflectiveOperationException {
		Class<?> blockRegistries = Class.forName("org.geysermc.geyser.registry.BlockRegistries", true, SkullAliases.class.getClassLoader());
		return blockRegistries.getField("CUSTOM_SKULLS").get(null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> skulls(Object registry) throws ReflectiveOperationException {
		Method get = registry.getClass().getMethod("get");
		return (Map<String, Object>) get.invoke(registry);
	}

	private static void set(Object registry, Map<String, Object> skulls) throws ReflectiveOperationException {
		Method set = registry.getClass().getMethod("set", Object.class);
		set.invoke(registry, skulls);
	}

	/**
	 * Geyser's custom skull table with the aliases visible to its item translator only. Everything else, placed heads
	 * included, sees the registered skulls alone, and changes go through to Geyser's own table.
	 */
	static final class ItemSkulls extends AbstractMap<String, Object> {
		private static final String ITEM_TRANSLATOR = "org.geysermc.geyser.translator.item.ItemTranslator";
		private static final StackWalker STACK = StackWalker.getInstance();
		final Map<String, Object> registered;
		private final Map<String, Object> aliases;

		ItemSkulls(Map<String, Object> registered, Map<String, Object> aliases) {
			this.registered = registered;
			this.aliases = aliases;
		}

		@Override
		public Object get(Object key) {
			Object skull = this.registered.get(key);
			// Only aliased skins pay for the stack walk, and only when Geyser looks them up.
			if (skull == null && this.aliases.containsKey(key) && fromItemTranslator())
				return this.aliases.get(key);
			return skull;
		}

		@Override
		public boolean containsKey(Object key) {
			return this.registered.containsKey(key);
		}

		@Override
		public int size() {
			return this.registered.size();
		}

		@Override
		public Set<Map.Entry<String, Object>> entrySet() {
			return this.registered.entrySet();
		}

		@Override
		public Set<String> keySet() {
			return this.registered.keySet();
		}

		@Override
		public Collection<Object> values() {
			return this.registered.values();
		}

		@Override
		public Object put(String key, Object value) {
			return this.registered.put(key, value);
		}

		@Override
		public Object remove(Object key) {
			return this.registered.remove(key);
		}

		@Override
		public void clear() {
			this.registered.clear();
		}

		private static boolean fromItemTranslator() {
			return STACK.walk(frames -> frames.limit(10).anyMatch(frame -> frame.getClassName().equals(ITEM_TRANSLATOR)));
		}
	}
}
