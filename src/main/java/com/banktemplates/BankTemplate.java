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
	public static final int FILLER = 20594;
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

	public List<TabLayout> getTabs()
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

	public void putTab(int tab, List<Integer> tabLayout)
	{
		normalize();
		tabs.removeIf(t -> t.getTab() == tab);
		tabs.add(new TabLayout(tab, tabLayout));
	}

	/** Layout for a native tab as a primitive array, or {@code null} if this template doesn't define it. */
	public int[] tabLayout(int tab)
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

	/**
	 * Every tab concatenated in bank order (numbered tabs 1-9 ascending, then the main/untabbed area
	 * last) - i.e. the order the "view all items" tab shows them in. Used when that view is active.
	 */
	public int[] fullLayout()
	{
		normalize();
		final List<TabLayout> sorted = new ArrayList<>(tabs);
		sorted.sort((a, b) -> tabOrder(a.getTab()) - tabOrder(b.getTab()));
		final List<Integer> all = new ArrayList<>();
		for (TabLayout t : sorted)
		{
			all.addAll(t.getLayout());
		}
		return toArray(all);
	}

	private static int tabOrder(int tab)
	{
		// Main/untabbed (0) sorts last, after the numbered tabs.
		return tab == MAIN_TAB ? 100 : tab;
	}

	/** Total real items across all tabs (excludes empty and filler slots). */
	public int itemCount()
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

	public int tabCount()
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
