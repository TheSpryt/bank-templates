package com.banktemplates;

import net.runelite.client.config.ConfigManager;

/**
 * The one place an Exchange Insights account token lives on a client, shared by every plugin
 * in the family.
 *
 * <p>The obvious way to share a token is for each plugin to read its siblings' config keys
 * directly, which is what Exchange Insights and Bank Templates currently do to each other.
 * That works for two, but it is O(n^2): a fourth plugin means editing the other three so they
 * know about it, and every plugin carries a list of its peers.
 *
 * <p>So instead there is a single agreed location - {@link #GROUP}/{@link #KEY} - that all of
 * them read and write. A new plugin joins by using these same two constants and nothing else
 * changes. Nobody needs to know who else exists.
 *
 * <p>The group is deliberately NOT any plugin's {@code @ConfigGroup}. It is a credential store
 * rather than a setting, so it should not appear in anyone's settings panel, and it must not
 * disappear when the plugin that happened to create it is uninstalled.
 *
 * <p>Legacy keys are still read as a fallback, so a client that linked before this existed
 * keeps working; the first read promotes that token into the shared slot, after which the
 * legacy path is never needed again.
 */
final class SharedAccountToken
{
	/** Shared credential store. Not owned by any plugin's config group, by design. */
	static final String GROUP = "eiaccount";
	static final String KEY = "token";

	/** Where tokens lived before the shared slot existed. Read-only fallback, for migration. */
	private static final String[][] LEGACY = {
		{"exchangeinsights", "token"},
		{"banktemplates", "eiAccountToken"},
	};

	private SharedAccountToken()
	{
	}

	/** True if this group/key is one a token can arrive through, so callers know to re-check. */
	static boolean isTokenKey(String group, String key)
	{
		if (GROUP.equals(group) && KEY.equals(key))
		{
			return true;
		}
		for (String[] pair : LEGACY)
		{
			if (pair[0].equals(group) && pair[1].equals(key))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The account token for this client, or null when nothing is linked.
	 *
	 * <p>Reads the shared slot first. If it is empty but a legacy key holds one, that token is
	 * promoted into the shared slot so later reads - including from plugins that have never
	 * heard of the legacy layout - find it in the agreed place.
	 */
	static String get(ConfigManager configManager)
	{
		final String shared = trimToNull(configManager.getConfiguration(GROUP, KEY));
		if (shared != null)
		{
			return shared;
		}

		for (String[] pair : LEGACY)
		{
			final String legacy = trimToNull(configManager.getConfiguration(pair[0], pair[1]));
			if (legacy != null)
			{
				configManager.setConfiguration(GROUP, KEY, legacy);
				return legacy;
			}
		}
		return null;
	}

	/** Store a newly issued token where every plugin in the family will find it. */
	static void set(ConfigManager configManager, String token)
	{
		final String clean = trimToNull(token);
		if (clean == null)
		{
			configManager.unsetConfiguration(GROUP, KEY);
			return;
		}
		configManager.setConfiguration(GROUP, KEY, clean);
	}

	/**
	 * Clear the shared token. Also clears the legacy copies, because leaving one behind would
	 * let the next {@link #get} promote it straight back and silently undo the unlink.
	 */
	static void clear(ConfigManager configManager)
	{
		configManager.unsetConfiguration(GROUP, KEY);
		for (String[] pair : LEGACY)
		{
			if (trimToNull(configManager.getConfiguration(pair[0], pair[1])) != null)
			{
				configManager.unsetConfiguration(pair[0], pair[1]);
			}
		}
	}

	private static String trimToNull(String value)
	{
		if (value == null)
		{
			return null;
		}
		final String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
