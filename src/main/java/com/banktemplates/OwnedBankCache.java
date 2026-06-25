package com.banktemplates;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.RuneLite;

/**
 * Remembers, per account, the set of (variant-collapsed) item ids that account holds in its bank, so the side
 * panel can show "x / y items" counts before the live bank has loaded this session (and the "Items owned"
 * Browse sort works away from a bank). Stored locally under .runelite/bank-templates/ - it never leaves the
 * user's machine. Treated as a best-effort cache: read/write failures are ignored.
 */
class OwnedBankCache
{
	private static final File DIR = new File(RuneLite.RUNELITE_DIR, "bank-templates");
	private static final File FILE = new File(DIR, "owned-banks.json");
	private static final Type TYPE = new TypeToken<Map<String, List<Integer>>>()
	{
	}.getType();

	private final Gson gson;
	// accountHash (as string, since JSON keys are strings) -> the owned ids. Loaded lazily on first use.
	private Map<String, List<Integer>> data;

	OwnedBankCache(Gson gson)
	{
		this.gson = gson;
	}

	/** Last-known owned set for an account, or null if none is cached. Blocking file IO - call off-thread. */
	synchronized Set<Integer> get(long accountHash)
	{
		ensureLoaded();
		final List<Integer> ids = data.get(Long.toString(accountHash));
		return ids == null ? null : new HashSet<>(ids);
	}

	/** Stores an account's owned set and persists it. Blocking file IO - call off-thread. */
	synchronized void put(long accountHash, Set<Integer> ids)
	{
		ensureLoaded();
		data.put(Long.toString(accountHash), new ArrayList<>(ids));
		write();
	}

	private void ensureLoaded()
	{
		if (data != null)
		{
			return;
		}
		data = new HashMap<>();
		if (!FILE.exists())
		{
			return;
		}
		try (Reader r = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8))
		{
			final Map<String, List<Integer>> loaded = gson.fromJson(r, TYPE);
			if (loaded != null)
			{
				data = loaded;
			}
		}
		catch (Exception e)
		{
			// Corrupt or unreadable - start fresh.
		}
	}

	private void write()
	{
		try
		{
			DIR.mkdirs();
			try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8))
			{
				gson.toJson(data, w);
			}
		}
		catch (Exception e)
		{
			// Best-effort cache; ignore write failures.
		}
	}
}
