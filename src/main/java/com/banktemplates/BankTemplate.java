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
	// True once this template is backed by a row in the linked Exchange Insights account's website "My
	// Templates" (see BankTemplatesPanel#syncWebTemplates). Distinguishes duplex-synced templates from
	// browse-imported community templates, which share the same owned=false + repoId shape.
	private boolean webSynced;
	// The website row id this template corresponds to in the user's own "My Templates" (duplex sync). Kept
	// separate from repoId, which is the COMMUNITY source id (the template you imported from, or your public
	// share) - a template can be both an import (repoId) and your own private web copy (webId) at once.
	private Long webId;
	// A stable, client-generated id for a template that originated in-game (an import or an in-plugin
	// creation). Sent up on sync so the server can match this local copy to the web row it creates for it,
	// on this and every later sync, without ever duplicating it.
	private String clientKey;
	// Wall-clock ms of the last local (in-game) edit, for last-write-wins duplex reconciliation. Only bumped
	// by genuine user edits (import, create, layout edit, rename) - never when sync writes the server's copy
	// back down, which would otherwise ping-pong the template between the two sides.
	private long updatedAt;

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

	public boolean isWebSynced()
	{
		return webSynced;
	}

	public void setWebSynced(boolean webSynced)
	{
		this.webSynced = webSynced;
	}

	public Long getWebId()
	{
		return webId;
	}

	public void setWebId(Long webId)
	{
		this.webId = webId;
	}

	public String getClientKey()
	{
		return clientKey;
	}

	public void setClientKey(String clientKey)
	{
		this.clientKey = clientKey;
	}

	public long getUpdatedAt()
	{
		return updatedAt;
	}

	public void setUpdatedAt(long updatedAt)
	{
		this.updatedAt = updatedAt;
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
		// Keep any custom icon the tab already had - replacing its layout (e.g. moving items in) shouldn't
		// silently reset the icon the user chose for it.
		int icon = 0;
		for (TabLayout t : tabs)
		{
			if (t.getTab() == tab)
			{
				icon = t.getCustomIconId();
				break;
			}
		}
		tabs.removeIf(t -> t.getTab() == tab);
		final TabLayout added = new TabLayout(tab, tabLayout);
		added.setCustomIconId(icon);
		tabs.add(added);
	}

	/** The custom icon item id chosen for a tab's button, or 0 to use the tab's first item (the default). */
	public synchronized int getTabIcon(int tab)
	{
		normalize();
		for (TabLayout t : tabs)
		{
			if (t.getTab() == tab)
			{
				return t.getCustomIconId();
			}
		}
		return 0;
	}

	/** Sets (or clears, with 0) a tab's custom icon, creating the tab if it isn't defined yet. */
	public synchronized void setTabIcon(int tab, int iconId)
	{
		normalize();
		for (TabLayout t : tabs)
		{
			if (t.getTab() == tab)
			{
				t.setCustomIconId(iconId);
				return;
			}
		}
		final TabLayout created = new TabLayout(tab, new ArrayList<>());
		created.setCustomIconId(iconId);
		tabs.add(created);
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

	/**
	 * Removes trailing empty numbered tabs - the highest-numbered tabs holding no real item (e.g. a tab left
	 * behind after all its items were moved away). Stops at the first non-empty tab from the top, so it never
	 * leaves a gap in the numbering. Pointless empty tabs otherwise count toward the 9-tab cap (hiding the +)
	 * and render as a blank tab button.
	 */
	public synchronized void pruneTrailingEmptyTabs()
	{
		normalize();
		for (int n = 9; n >= 1; n--)
		{
			final int[] lay = tabLayout(n);
			if (lay == null)
			{
				continue;
			}
			boolean hasItem = false;
			for (int v : lay)
			{
				if (v > 0 && v != FILLER)
				{
					hasItem = true;
					break;
				}
			}
			if (hasItem)
			{
				break;
			}
			final int num = n;
			tabs.removeIf(t -> t.getTab() == num);
		}
	}

	/**
	 * Removes a numbered tab and renumbers the higher tabs down to close the gap (like the real bank collapsing
	 * a tab). The main view (0) is never removed. No-op if the tab isn't defined.
	 */
	public synchronized void removeTab(int tab)
	{
		normalize();
		if (tab == MAIN_TAB)
		{
			return;
		}
		// Snapshot the surviving numbered tabs (their backing lists), renumbering anything above the removed
		// tab down by one.
		final List<TabLayout> survivors = new ArrayList<>();
		for (TabLayout t : tabs)
		{
			if (t.getTab() == MAIN_TAB || t.getTab() == tab)
			{
				continue;
			}
			final TabLayout moved = new TabLayout(t.getTab() > tab ? t.getTab() - 1 : t.getTab(), t.getLayout());
			moved.setCustomIconId(t.getCustomIconId());
			survivors.add(moved);
		}
		tabs.removeIf(t -> t.getTab() != MAIN_TAB);
		tabs.addAll(survivors);
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

	/**
	 * Swaps two numbered tabs, like dragging one bank tab onto another: {@code fromTab} and {@code toTab}
	 * exchange contents (their tab numbers stay put, so the rest of the tabs are untouched). The main view
	 * (0) is never involved. Returns the tab number now holding what was dragged ({@code toTab}), or -1 on a
	 * no-op (either isn't a numbered tab this template defines, or they're the same).
	 */
	public synchronized int moveTab(int fromTab, int toTab)
	{
		normalize();
		if (fromTab == MAIN_TAB || toTab == MAIN_TAB || fromTab == toTab)
		{
			return -1;
		}
		List<Integer> fromLayout = null;
		List<Integer> toLayout = null;
		int fromIcon = 0;
		int toIcon = 0;
		for (TabLayout t : tabs)
		{
			if (t.getTab() == fromTab)
			{
				fromLayout = t.getLayout();
				fromIcon = t.getCustomIconId();
			}
			else if (t.getTab() == toTab)
			{
				toLayout = t.getLayout();
				toIcon = t.getCustomIconId();
			}
		}
		if (fromLayout == null || toLayout == null)
		{
			return -1;
		}
		// Exchange the two tabs' contents (and their icons - the icon belongs to the items), keeping their
		// numbers (drag tab 3 onto tab 1 -> tab 1 shows tab 3's items and icon, and vice versa).
		tabs.removeIf(t -> t.getTab() == fromTab || t.getTab() == toTab);
		final TabLayout movedTo = new TabLayout(toTab, fromLayout);
		movedTo.setCustomIconId(fromIcon);
		final TabLayout movedFrom = new TabLayout(fromTab, toLayout);
		movedFrom.setCustomIconId(toIcon);
		tabs.add(movedTo);
		tabs.add(movedFrom);
		return toTab;
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
		c.webSynced = webSynced;
		c.webId = webId;
		c.clientKey = clientKey;
		c.updatedAt = updatedAt;
		c.preset = preset;
		c.tabs = new ArrayList<>();
		for (TabLayout t : tabs)
		{
			final TabLayout copy = new TabLayout(t.getTab(), new ArrayList<>(t.getLayout()));
			copy.setCustomIconId(t.getCustomIconId());
			c.tabs.add(copy);
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
			final TabLayout copy = new TabLayout(t.getTab(), new ArrayList<>(t.getLayout()));
			copy.setCustomIconId(t.getCustomIconId());
			tabs.add(copy);
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
