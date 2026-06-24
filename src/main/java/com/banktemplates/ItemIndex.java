package com.banktemplates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * A cached, searchable index of every in-game item by name - including <b>untradeable</b> items, which
 * {@link ItemManager#search} (backed by Grand Exchange price data) leaves out. That GE-only search is why
 * the Add-item picker couldn't find things like Barrows gloves, Arclight or Emberlight.
 * <p>
 * Built once, lazily, on the client thread in chunks (item compositions must be read there, and a full
 * pass is too much for a single tick), so it never blocks the client. Searches run on the EDT against the
 * cached snapshot. Noted items, placeholders and blank/"null" names are filtered out.
 */
@Singleton
class ItemIndex
{
	private static final int CHUNK = 2000;
	// Prefer shorter names so the plain item ("Arclight") ranks above its variants ("Arclight (or)").
	private static final Comparator<Entry> BY_NAME =
		Comparator.comparingInt((Entry e) -> e.lower.length()).thenComparing(e -> e.lower);

	static final class Entry
	{
		private final int id;
		private final String name;
		private final String lower;

		Entry(int id, String name)
		{
			this.id = id;
			this.name = name;
			this.lower = name.toLowerCase(Locale.ROOT);
		}

		int getId()
		{
			return id;
		}

		String getName()
		{
			return name;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;

	// Built once on the client thread; read (volatile) from the EDT. null until ready.
	private volatile Entry[] index;
	private boolean building;                                  // guarded by 'this'
	private final List<Runnable> pending = new ArrayList<>();  // guarded by 'this'

	@Inject
	ItemIndex(Client client, ClientThread clientThread, ItemManager itemManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
	}

	boolean isReady()
	{
		return index != null;
	}

	/** Ensures the index is built; runs {@code onReady} on the EDT once it is (immediately if already built). */
	void ensureBuilt(Runnable onReady)
	{
		if (index != null)
		{
			if (onReady != null)
			{
				SwingUtilities.invokeLater(onReady);
			}
			return;
		}
		boolean start = false;
		synchronized (this)
		{
			if (onReady != null)
			{
				pending.add(onReady);
			}
			if (!building)
			{
				building = true;
				start = true;
			}
		}
		if (start)
		{
			buildChunk(0, new ArrayList<>());
		}
	}

	// Indexes [start, start+CHUNK) on the client thread, then schedules the next chunk on a later tick so a
	// full ~30k-item pass never stalls a single frame.
	private void buildChunk(int start, List<Entry> acc)
	{
		clientThread.invokeLater(() ->
		{
			final int n = client.getItemCount();
			final int end = Math.min(start + CHUNK, n);
			for (int id = start; id < end; id++)
			{
				final ItemComposition c;
				try
				{
					c = itemManager.getItemComposition(id);
				}
				catch (RuntimeException ex)
				{
					continue;
				}
				if (c == null || c.getNote() != -1 || c.getPlaceholderTemplateId() != -1)
				{
					continue;
				}
				final String name = c.getName();
				if (name == null)
				{
					continue;
				}
				final String trimmed = name.trim();
				if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed))
				{
					continue;
				}
				acc.add(new Entry(id, trimmed));
			}
			if (end < n)
			{
				buildChunk(end, acc);
				return;
			}
			index = acc.toArray(new Entry[0]);
			final List<Runnable> fire;
			synchronized (this)
			{
				building = false;
				fire = new ArrayList<>(pending);
				pending.clear();
			}
			for (Runnable r : fire)
			{
				SwingUtilities.invokeLater(r);
			}
		});
	}

	/** Name search: prefix matches first, then substring matches, capped at {@code max}. Empty until built. */
	List<Entry> search(String query, int max)
	{
		final Entry[] idx = index;
		if (idx == null || query == null)
		{
			return Collections.emptyList();
		}
		final String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<Entry> prefix = new ArrayList<>();
		final List<Entry> contains = new ArrayList<>();
		for (Entry e : idx)
		{
			final int p = e.lower.indexOf(q);
			if (p == 0)
			{
				prefix.add(e);
			}
			else if (p > 0)
			{
				contains.add(e);
			}
		}
		prefix.sort(BY_NAME);
		contains.sort(BY_NAME);
		final List<Entry> out = new ArrayList<>();
		for (Entry e : prefix)
		{
			if (out.size() >= max)
			{
				break;
			}
			out.add(e);
		}
		for (Entry e : contains)
		{
			if (out.size() >= max)
			{
				break;
			}
			out.add(e);
		}
		return out;
	}

	AsyncBufferedImage getImage(int id)
	{
		return itemManager.getImage(id);
	}
}
