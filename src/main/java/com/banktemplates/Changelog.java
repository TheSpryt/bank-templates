package com.banktemplates;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * The bundled patch notes. The newest entry's {@link Entry#version} is treated as this build's version;
 * the side panel shows the newest entry's notes as an "Updates" tab until the user dismisses it.
 * <p>
 * This is purely local (no network) - when the Plugin Hub auto-updates the plugin, the new build ships
 * with a new newest entry, which is how "a new version went live" is detected.
 */
final class Changelog
{
	static final class Entry
	{
		String version;
		List<String> notes;
		List<String> knownIssues;
	}

	private Changelog()
	{
	}

	/** The newest changelog entry, or {@code null} if none could be loaded. */
	static Entry latest(Gson gson)
	{
		final List<Entry> all = load(gson);
		return all.isEmpty() ? null : all.get(0);
	}

	private static List<Entry> load(Gson gson)
	{
		try (InputStream in = Changelog.class.getResourceAsStream("/com/banktemplates/changelog.json"))
		{
			if (in == null)
			{
				return Collections.emptyList();
			}
			final Entry[] entries = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Entry[].class);
			return entries == null ? Collections.emptyList() : java.util.Arrays.asList(entries);
		}
		catch (Exception e)
		{
			return Collections.emptyList();
		}
	}
}
