package com.banktemplates;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved bank layout, organised per native bank tab.
 * <p>
 * Each {@link TabLayout} holds a flat, row-major grid for one tab ({@link #MAIN_TAB} = the main
 * "all items" view, 1-9 = the numbered tabs). Each slot is one of:
 * <ul>
 *     <li>a real item id (&gt; 0) - drawn real if owned, faded placeholder if not,</li>
 *     <li>{@link #EMPTY} (-1) - an empty slot,</li>
 *     <li>{@link #FILLER} - a reserved 🚫 slot.</li>
 * </ul>
 * {@link #getColumns()} is the grid width the template was designed for (default 8), so it renders
 * consistently regardless of the player's bank window width.
 */
public class BankTemplate
{
	public static final int EMPTY = -1;
	// Must remain 20594: this value is serialized into template JSON. ItemID.BANK_FILLER == 20594 exactly.
	public static final int FILLER = net.runelite.api.gameval.ItemID.BANK_FILLER;
	/** The main "all items" bank view. */
	public static final int MAIN_TAB = 0;

	private String name;
	private String description;
	private int columns;
	private List<TabLayout> tabs;

	// Legacy single-grid format (older saves / shared codes). Migrated into a MAIN_TAB layout on access.
	private List<Integer> layout;

	private transient boolean preset;

	// Community repository linkage (persisted): the repo id this template corresponds to, and whether
	// the local user owns it (uploaded it) and may therefore update/delete it on the repo.
	private Long repoId;
	private boolean owned;
	// Whether this template was shared anonymously - remembered so re-sharing (Update) keeps the choice.
	private boolean sharedAnonymously;

	public BankTemplate()
	{
	}

	public Long getRepoId()
	{
		return repoId;
	}

	public void setRepoId(Long repoId)
	{
		this.repoId = repoId;
	}

	public boolean isOwned()
	{
		return owned;
	}

	public void setOwned(boolean owned)
	{
		this.owned = owned;
	}

	public boolean isSharedAnonymously()
	{
		return sharedAnonymously;
	}

	public void setSharedAnonymously(boolean sharedAnonymously)
	{
		this.sharedAnonymously = sharedAnonymously;
	}

	public String getName()
	{
		return name;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public String getDescription()
	{
		return description;
	}

	public void setDescription(String description)
	{
		this.description = description;
	}

	public boolean isPreset()
	{
		return preset;
	}

	void setPreset(boolean preset)
	{
		this.preset = preset;
	}

	public int getColumns()
	{
		return columns > 0 ? columns : BankLayoutRenderer.ITEMS_PER_ROW;
	}

	public void setColumns(int columns)
	{
		this.columns = columns;
	}

	// Several accessors below are synchronized on this template instance. The layout editor mutates the
	// per-tab lists (from the Swing thread and the client thread) while the renderer reads them on the
	// client thread, so editing and reading take the same lock to stay consistent.
	public synchronized List<TabLayout> getTabs()
	{
		normalize();
		return tabs;
	}

	// Lazily migrate the legacy single-grid field into a MAIN_TAB layout.
	private void normalize()
	{
		if (tabs == null)
		{
			tabs = new ArrayList<>();
		}
		if (tabs.isEmpty() && layout != null && !layout.isEmpty())
		{
			tabs.add(new TabLayout(MAIN_TAB, new ArrayList<>(layout)));
			layout = null;
		}
	}

	public synchronized void putTab(int tab, List<Integer> tabLayout)
	{
		normalize();
		tabs.removeIf(t -> t.getTab() == tab);
		tabs.add(new TabLayout(tab, tabLayout));
	}

	/**
	 * The live, mutable slot list for a tab, creating an empty one if the template doesn't define it
	 * yet. Used by the layout editor to add/move/remove slots in place.
	 */
	public synchronized List<Integer> editableTab(int tab)
	{
		normalize();
		for (TabLayout t : tabs)
		{
			if (t.getTab() == tab)
			{
				return t.getLayout();
			}
		}
		final TabLayout created = new TabLayout(tab, new ArrayList<>());
		tabs.add(created);
		return created.getLayout();
	}

	/** A safe, independent copy of a tab's slots for display (e.g. the editor grid). */
	public synchronized List<Integer> copyTab(int tab)
	{
		return new ArrayList<>(editableTab(tab));
	}

	/** Drops a tab whose layout has become empty so it doesn't linger as a blank tab. */
	public synchronized void pruneTab(int tab)
	{
		normalize();
		tabs.removeIf(t -> t.getTab() == tab && t.getLayout().isEmpty());
	}

	/** Tabs this template defines, ascending, with the main view (0) sorting first for the editor. */
	public synchronized List<Integer> definedTabs()
	{
		normalize();
		final List<Integer> result = new ArrayList<>();
		for (TabLayout t : tabs)
		{
			result.add(t.getTab());
		}
		result.sort((a, b) -> editorOrder(a) - editorOrder(b));
		return result;
	}

	private static int editorOrder(int tab)
	{
		// Main/untabbed (0) sorts first in the editor (it's the "all items" view); numbered tabs follow.
		return tab == MAIN_TAB ? -1 : tab;
	}

	/** A deep, independent copy - used to snapshot a template before editing so edits can be reverted. */
	public synchronized BankTemplate copy()
	{
		normalize();
		final BankTemplate c = new BankTemplate();
		c.name = name;
		c.description = description;
		c.columns = columns;
		c.repoId = repoId;
		c.owned = owned;
		c.sharedAnonymously = sharedAnonymously;
		c.preset = preset;
		c.tabs = new ArrayList<>();
		for (TabLayout t : tabs)
		{
			c.tabs.add(new TabLayout(t.getTab(), new ArrayList<>(t.getLayout())));
		}
		return c;
	}

	/** Replaces this template's tab layouts with those of {@code other} (used when reverting edits). */
	public synchronized void restoreTabsFrom(BankTemplate other)
	{
		normalize();
		other.normalize();
		tabs.clear();
		for (TabLayout t : other.tabs)
		{
			tabs.add(new TabLayout(t.getTab(), new ArrayList<>(t.getLayout())));
		}
		columns = other.columns;
	}

	/** Layout for a native tab as a primitive array, or {@code null} if this template doesn't define it. */
	public synchronized int[] tabLayout(int tab)
	{
		normalize();
		for (TabLayout t : tabs)
		{
			if (t.getTab() == tab)
			{
				return toArray(t.getLayout());
			}
		}
		return null;
	}

	/** Total real items across all tabs (excludes empty and filler slots). */
	public synchronized int itemCount()
	{
		normalize();
		int n = 0;
		for (TabLayout t : tabs)
		{
			for (Integer v : t.getLayout())
			{
				if (v != null && v > 0 && v != FILLER)
				{
					n++;
				}
			}
		}
		return n;
	}

	public synchronized int tabCount()
	{
		normalize();
		return tabs.size();
	}

	static int[] toArray(List<Integer> list)
	{
		if (list == null)
		{
			return new int[0];
		}
		final int[] a = new int[list.size()];
		for (int i = 0; i < a.length; i++)
		{
			final Integer v = list.get(i);
			a[i] = v == null ? EMPTY : v;
		}
		return a;
	}
}
