package com.banktemplates;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

/**
 * Owns the set of templates (bundled presets + user-saved templates) and the currently active one.
 * Presets ship as a single JSON resource; user templates live as individual JSON files under
 * {@code .runelite/bank-templates/}. The active selection is persisted via {@link ConfigManager}.
 */
@Slf4j
@Singleton
public class TemplateManager
{
	private static final String PRESETS_RESOURCE = "/com/banktemplates/presets/presets.json";
	private static final Path STORAGE_DIR = RuneLite.RUNELITE_DIR.toPath().resolve("bank-templates");
	private static final Pattern UNSAFE_FILENAME = Pattern.compile("[^a-zA-Z0-9-_]");
	private static final Type TEMPLATE_LIST_TYPE = new TypeToken<List<BankTemplate>>()
	{
	}.getType();

	private final Gson gson;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;

	private final List<BankTemplate> presets = new ArrayList<>();
	// Keyed by template name; preserves insertion order for stable panel display.
	private final Map<String, BankTemplate> userTemplates = new LinkedHashMap<>();

	private BankTemplate active;

	@Inject
	TemplateManager(Gson gson, ConfigManager configManager, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.configManager = configManager;
		this.executor = executor;
	}

	void load()
	{
		loadPresets();
		loadUserTemplates();
		resolveActiveFromConfig();
	}

	private void loadPresets()
	{
		presets.clear();
		try (InputStream in = TemplateManager.class.getResourceAsStream(PRESETS_RESOURCE))
		{
			if (in == null)
			{
				log.debug("No bundled presets resource found at {}", PRESETS_RESOURCE);
				return;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				final List<BankTemplate> loaded = gson.fromJson(reader, TEMPLATE_LIST_TYPE);
				if (loaded != null)
				{
					for (BankTemplate t : loaded)
					{
						if (t != null && t.getName() != null)
						{
							t.setPreset(true);
							presets.add(t);
						}
					}
				}
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.warn("Failed to load bundled bank template presets", e);
		}
	}

	private void loadUserTemplates()
	{
		userTemplates.clear();
		if (!Files.isDirectory(STORAGE_DIR))
		{
			return;
		}

		try (Stream<Path> files = Files.list(STORAGE_DIR))
		{
			files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json"))
				.sorted()
				.forEach(this::loadUserTemplateFile);
		}
		catch (IOException e)
		{
			log.warn("Failed to list bank template directory {}", STORAGE_DIR, e);
		}
	}

	private void loadUserTemplateFile(Path file)
	{
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			final BankTemplate t = gson.fromJson(reader, BankTemplate.class);
			if (t != null && t.getName() != null && !t.getTabs().isEmpty())
			{
				t.setPreset(false);
				t.pruneTrailingEmptyTabs();
				userTemplates.put(t.getName(), t);
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.warn("Skipping unreadable bank template file {}", file, e);
		}
	}

	private void resolveActiveFromConfig()
	{
		final String name = configManager.getConfiguration(BankTemplatesConfig.GROUP, BankTemplatesConfig.ACTIVE_TEMPLATE_KEY);
		active = name == null || name.isEmpty() ? null : findByName(name);
	}

	/** All templates the user can choose from: presets first, then user templates. */
	public List<BankTemplate> getTemplates()
	{
		final List<BankTemplate> all = new ArrayList<>(presets);
		all.addAll(userTemplates.values());
		return all;
	}

	public List<BankTemplate> getPresets()
	{
		return Collections.unmodifiableList(presets);
	}

	public List<BankTemplate> getUserTemplates()
	{
		final List<BankTemplate> l = new ArrayList<>(userTemplates.values());
		l.sort(Comparator.comparing(BankTemplate::getName, String.CASE_INSENSITIVE_ORDER));
		return l;
	}

	public BankTemplate getActive()
	{
		return active;
	}

	public BankTemplate findByName(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (BankTemplate t : getTemplates())
		{
			if (name.equals(t.getName()))
			{
				return t;
			}
		}
		return null;
	}

	/** Sets the active template (null clears it) and persists the choice. */
	public void setActive(BankTemplate template)
	{
		this.active = template;
		if (template == null)
		{
			configManager.unsetConfiguration(BankTemplatesConfig.GROUP, BankTemplatesConfig.ACTIVE_TEMPLATE_KEY);
		}
		else
		{
			configManager.setConfiguration(BankTemplatesConfig.GROUP, BankTemplatesConfig.ACTIVE_TEMPLATE_KEY, template.getName());
		}
	}

	public boolean isActive(BankTemplate template)
	{
		return active != null && template != null && active.getName().equals(template.getName());
	}

	/**
	 * Saves a user template (creating or overwriting by name) and writes it to disk asynchronously.
	 * Returns false if the name collides with a preset.
	 */
	public boolean saveUserTemplate(BankTemplate template)
	{
		if (template == null || template.getName() == null || template.getName().trim().isEmpty())
		{
			return false;
		}

		final String name = template.getName().trim();
		template.setName(name);
		template.setPreset(false);

		// Don't let a user template shadow a preset of the same name.
		for (BankTemplate p : presets)
		{
			if (p.getName().equalsIgnoreCase(name))
			{
				return false;
			}
		}

		userTemplates.put(name, template);
		writeAsync(template);
		return true;
	}

	/**
	 * Renames a user template, moving its on-disk file and keeping the active-template config in sync.
	 * The template's name is its storage key (map key + file name), so this re-keys it rather than just
	 * setting the field. Returns false if the new name is blank or collides with a preset or another
	 * user template (case-insensitive); a no-op rename to the same name returns true.
	 */
	public boolean renameTemplate(BankTemplate template, String newName)
	{
		if (template == null || template.isPreset() || newName == null)
		{
			return false;
		}

		final String trimmed = newName.trim();
		if (trimmed.isEmpty())
		{
			return false;
		}

		final String oldName = template.getName();
		if (trimmed.equals(oldName))
		{
			return true;
		}

		// Don't shadow a preset, or clobber a different user template that already uses the name.
		for (BankTemplate p : presets)
		{
			if (p.getName().equalsIgnoreCase(trimmed))
			{
				return false;
			}
		}
		for (BankTemplate t : userTemplates.values())
		{
			if (t != template && t.getName().equalsIgnoreCase(trimmed))
			{
				return false;
			}
		}

		userTemplates.remove(oldName);
		template.setName(trimmed);
		userTemplates.put(trimmed, template);

		// Write the new file first, then drop the old one - but only if it's a different file (a rename that
		// only changes case or punctuation can map to the same safe file name, which we must not delete).
		final Path oldFile = fileFor(oldName);
		writeAsync(template);
		if (!fileFor(trimmed).equals(oldFile))
		{
			deleteAsync(oldName);
		}

		// The active template is tracked by name in config; if this is it, repoint the stored name.
		if (active == template)
		{
			configManager.setConfiguration(BankTemplatesConfig.GROUP, BankTemplatesConfig.ACTIVE_TEMPLATE_KEY, trimmed);
		}
		return true;
	}

	public void deleteUserTemplate(BankTemplate template)
	{
		if (template == null || template.isPreset())
		{
			return;
		}

		userTemplates.remove(template.getName());
		if (isActive(template))
		{
			setActive(null);
		}
		deleteAsync(template.getName());
	}

	private void writeAsync(BankTemplate template)
	{
		final String json = gson.toJson(template);
		final Path file = fileFor(template.getName());
		executor.execute(() ->
		{
			try
			{
				Files.createDirectories(STORAGE_DIR);
				Files.write(file, json.getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				log.warn("Failed to save bank template {}", file, e);
			}
		});
	}

	private void deleteAsync(String name)
	{
		final Path file = fileFor(name);
		executor.execute(() ->
		{
			try
			{
				Files.deleteIfExists(file);
			}
			catch (IOException e)
			{
				log.warn("Failed to delete bank template {}", file, e);
			}
		});
	}

	private static Path fileFor(String name)
	{
		final String safe = UNSAFE_FILENAME.matcher(name.trim().replace(' ', '_')).replaceAll("_");
		return STORAGE_DIR.resolve(safe + ".json");
	}
}
