package com.banktemplates;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Holds the state and operations for editing a {@link BankTemplate}'s layout - both from the side-panel
 * grid editor and live over the bank. You can build or rearrange a layout <b>without owning the items</b>:
 * added items render as faded placeholders, and dragging/inserting/removing slots just rewrites the
 * template's per-tab lists.
 * <p>
 * Edits are written through to disk on each change and a snapshot is kept so {@link #revert()} can undo
 * the whole session. Only user templates are editable (presets are read-only). Each mutation locks on
 * the template instance so the client-thread renderer never reads a half-applied change.
 */
@Slf4j
@Singleton
public class LayoutEditor
{
	// OSRS allows nine numbered bank tabs (plus the main/all-items view).
	static final int MAX_TABS = 9;

	private final TemplateManager templateManager;
	private final ItemManager itemManager;

	// volatile: read from the client thread (renderer/overlay) and the Swing thread (panel/editor).
	private volatile boolean editing;
	private volatile BankTemplate target;
	// A deep copy taken when editing starts, so the whole session can be reverted.
	private volatile BankTemplate snapshot;

	// Notified after any change (and on start/stop) so the panel editor and the bank view refresh.
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	@Inject
	LayoutEditor(TemplateManager templateManager, ItemManager itemManager)
	{
		this.templateManager = templateManager;
		this.itemManager = itemManager;
	}

	void addListener(Runnable r)
	{
		if (r != null)
		{
			listeners.add(r);
		}
	}

	void removeListener(Runnable r)
	{
		listeners.remove(r);
	}

	boolean isEditing()
	{
		return editing;
	}

	BankTemplate getTarget()
	{
		return target;
	}

	/**
	 * The template that bank edits write to: the explicit edit session's target if one is open, otherwise
	 * the active (applied) template. This is what makes editing "always on" while a template is applied -
	 * dragging/releasing in the bank adjusts whatever template you're currently looking at.
	 */
	BankTemplate liveTemplate()
	{
		final BankTemplate t = target;
		if (editing && t != null)
		{
			return t;
		}
		final BankTemplate active = templateManager.getActive();
		return active != null && !active.isPreset() ? active : null;
	}

	/**
	 * True when the live (editable) template is the one currently applied/rendered, so in-bank editing is
	 * valid. Editing a template from the side panel does NOT activate it, so its window edits don't render
	 * over the bank - in-bank editing stays off until that template is the active one.
	 */
	boolean liveOverBank()
	{
		final BankTemplate t = liveTemplate();
		final BankTemplate a = templateManager.getActive();
		return t != null && a != null && t.getName().equals(a.getName());
	}

	/** True when the bank should show {@code template} in editable form. */
	boolean isEditing(BankTemplate template)
	{
		final BankTemplate t = target;
		return editing && t != null && template != null && t.getName().equals(template.getName());
	}

	/**
	 * Begins editing a user template in the side-panel window. Does NOT activate it - the bank keeps showing
	 * whatever template is currently applied, so you can edit a template without changing your bank view.
	 * Snapshots it for {@link #revert()}. Presets and nulls are rejected.
	 */
	boolean start(BankTemplate template)
	{
		if (template == null || template.isPreset())
		{
			return false;
		}
		this.target = template;
		this.snapshot = template.copy();
		this.editing = true;
		notifyChanged();
		return true;
	}

	/** Ends the editing session, tidying trailing gaps left while arranging. */
	void finish()
	{
		final BankTemplate t = target;
		if (t != null)
		{
			synchronized (t)
			{
				for (int tab : t.definedTabs())
				{
					trimTrailing(t.editableTab(tab));
					t.pruneTab(tab);
				}
			}
			templateManager.saveUserTemplate(t);
		}
		editing = false;
		target = null;
		snapshot = null;
		notifyChanged();
	}

	/** Ends the session and DISCARDS changes, restoring the template to how it was when editing began
	 *  (used when the editor window is closed without applying). */
	void discard()
	{
		final BankTemplate t = target;
		final BankTemplate snap = snapshot;
		if (t != null && snap != null)
		{
			t.restoreTabsFrom(snap);
			templateManager.saveUserTemplate(t);
		}
		editing = false;
		target = null;
		snapshot = null;
		notifyChanged();
	}

	/** Restores the template to how it was when editing started, then keeps editing. */
	void revert()
	{
		final BankTemplate t = target;
		final BankTemplate snap = snapshot;
		if (t != null && snap != null)
		{
			t.restoreTabsFrom(snap);
			templateManager.saveUserTemplate(t);
			notifyChanged();
		}
	}

	// ---- Layout mutations (all no-ops unless this template is being edited) ------------------

	/** Appends an item (or a filler) to a tab. Real items are deduped silently; fillers can repeat. */
	void addItem(int tab, int itemId)
	{
		if (itemId == BankTemplate.FILLER || (itemId > 0 && !contains(itemId)))
		{
			edit(tab, slots -> slots.add(itemId));
		}
	}

	/**
	 * Appends a real item to a tab, or - if it's already in the layout - reports where via {@code onDup}
	 * (with a user-facing message) and adds nothing. Fillers are always added. Must run on the client
	 * thread (it reads the item name). {@code onDup} is invoked on the client thread.
	 */
	void addItemOrReport(int tab, int itemId, Consumer<String> onDup)
	{
		if (itemId == BankTemplate.FILLER)
		{
			addItem(tab, itemId);
			return;
		}
		if (itemId <= 0)
		{
			return;
		}
		final BankTemplate t = liveTemplate();
		if (t == null)
		{
			return;
		}
		final int[] loc = locate(t, itemId);
		if (loc != null)
		{
			if (onDup != null)
			{
				onDup.accept(itemName(itemId) + " is already in " + describe(t, loc[0], loc[1]) + ".");
			}
			return;
		}
		edit(tab, slots -> slots.add(itemId));
	}

	/**
	 * Replaces the slot at {@code pos} in {@code tab} with a real item, or - if that item is already
	 * elsewhere in the layout - reports where via {@code onDup} (with a user-facing message) and changes
	 * nothing. Fillers replace unconditionally. Must run on the client thread (it reads the item name).
	 * {@code onDup} is invoked on the client thread.
	 */
	void replaceItemOrReport(int tab, int pos, int itemId, Consumer<String> onDup)
	{
		if (pos < 0)
		{
			return;
		}
		if (itemId == BankTemplate.FILLER)
		{
			setSlot(tab, pos, itemId);
			return;
		}
		if (itemId <= 0)
		{
			return;
		}
		final BankTemplate t = liveTemplate();
		if (t == null)
		{
			return;
		}
		final int[] loc = locate(t, itemId);
		// Picking the item that's already in this same slot is a no-op, not a duplicate.
		if (loc != null && !(loc[0] == tab && loc[1] == pos))
		{
			if (onDup != null)
			{
				onDup.accept(itemName(itemId) + " is already in " + describe(t, loc[0], loc[1]) + ".");
			}
			return;
		}
		setSlot(tab, pos, itemId);
	}

	// The {tab, index} of itemId in the template, or null if absent.
	private int[] locate(BankTemplate t, int itemId)
	{
		synchronized (t)
		{
			for (TabLayout tl : t.getTabs())
			{
				final List<Integer> layout = tl.getLayout();
				for (int i = 0; i < layout.size(); i++)
				{
					final Integer v = layout.get(i);
					if (v != null && v == itemId)
					{
						return new int[]{tl.getTab(), i};
					}
				}
			}
		}
		return null;
	}

	private String describe(BankTemplate t, int tab, int index)
	{
		final int cols = t.getColumns();
		final String tabName = tab == BankTemplate.MAIN_TAB ? "the Main tab" : "Tab " + tab;
		return tabName + ", row " + (index / cols + 1) + ", slot " + (index % cols + 1);
	}

	private String itemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (RuntimeException e)
		{
			return "That item";
		}
	}

	/** True if the current edit session has changes that differ from the pre-edit snapshot. */
	boolean hasUnsavedChanges()
	{
		final BankTemplate t = target;
		final BankTemplate snap = snapshot;
		if (!editing || t == null || snap == null)
		{
			return false;
		}
		synchronized (t)
		{
			final List<Integer> tabs = t.definedTabs();
			if (!tabs.equals(snap.definedTabs()))
			{
				return true;
			}
			for (int tab : tabs)
			{
				if (!java.util.Arrays.equals(t.tabLayout(tab), snap.tabLayout(tab)))
				{
					return true;
				}
			}
			return false;
		}
	}

	/** True if {@code itemId} already appears anywhere in the template being edited. */
	boolean contains(int itemId)
	{
		final BankTemplate t = liveTemplate();
		if (t == null)
		{
			return false;
		}
		synchronized (t)
		{
			for (TabLayout tl : t.getTabs())
			{
				for (Integer v : tl.getLayout())
				{
					if (v != null && v == itemId)
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Sets a slot to an item id, {@link BankTemplate#FILLER} or {@link BankTemplate#EMPTY}. */
	void setSlot(int tab, int pos, int value)
	{
		if (pos >= 0)
		{
			edit(tab, slots ->
			{
				while (slots.size() <= pos)
				{
					slots.add(BankTemplate.EMPTY);
				}
				slots.set(pos, value);
			});
		}
	}

	/** Removes a slot, shifting everything after it up. */
	void removeSlot(int tab, int pos)
	{
		edit(tab, slots ->
		{
			if (pos >= 0 && pos < slots.size())
			{
				slots.remove(pos);
			}
		});
	}

	/** Inserts an empty slot at {@code pos}, shifting everything from there down. */
	void insertEmpty(int tab, int pos)
	{
		edit(tab, slots -> slots.add(Math.max(0, Math.min(pos, slots.size())), BankTemplate.EMPTY));
	}

	/**
	 * Moves the slot at {@code from} to {@code to}, shifting the slots in between (drag-to-insert). A
	 * {@code to} at or past the end appends.
	 */
	void moveSlot(int tab, int from, int to)
	{
		if (from == to)
		{
			return;
		}
		edit(tab, slots ->
		{
			if (from >= 0 && from < slots.size())
			{
				final Integer value = slots.remove(from);
				slots.add(Math.max(0, Math.min(to, slots.size())), value);
			}
		});
	}

	/** Moves the slot at {@code fromPos} in {@code fromTab} to the end of {@code toTab} (drag onto a tab). */
	void moveToTab(int fromTab, int fromPos, int toTab)
	{
		final BankTemplate t = liveTemplate();
		if (t == null || fromTab == toTab)
		{
			return;
		}
		synchronized (t)
		{
			final List<Integer> from = t.editableTab(fromTab);
			if (fromPos < 0 || fromPos >= from.size())
			{
				return;
			}
			final Integer value = from.remove(fromPos);
			if (value != null && value != BankTemplate.EMPTY)
			{
				t.editableTab(toTab).add(value);
			}
		}
		templateManager.saveUserTemplate(t);
		notifyChanged();
	}

	/**
	 * Moves the slot at {@code fromPos} in {@code fromTab} into a brand-new tab (the next free numbered
	 * tab), mirroring the game's "drag to the + tab" behaviour. Respects the 9-tab maximum; returns false
	 * (and does nothing) if all nine tabs are already used.
	 */
	boolean moveToNewTab(int fromTab, int fromPos)
	{
		final BankTemplate t = liveTemplate();
		if (t == null)
		{
			return false;
		}
		int next;
		synchronized (t)
		{
			int max = 0;
			for (int tab : t.definedTabs())
			{
				if (tab != BankTemplate.MAIN_TAB)
				{
					max = Math.max(max, tab);
				}
			}
			next = max + 1;
		}
		if (next > MAX_TABS)
		{
			return false;
		}
		moveToTab(fromTab, fromPos, next);
		return true;
	}

	/** Swaps the contents of two slots (drag-to-swap). Pads with empties if needed. */
	void swapSlots(int tab, int a, int b)
	{
		if (a == b || a < 0 || b < 0)
		{
			return;
		}
		edit(tab, slots ->
		{
			while (slots.size() <= Math.max(a, b))
			{
				slots.add(BankTemplate.EMPTY);
			}
			final Integer tmp = slots.get(a);
			slots.set(a, slots.get(b));
			slots.set(b, tmp);
		});
	}

	/** Adds a full empty row (one tab's worth of columns) so there's room to drop items into. */
	void addRow(int tab)
	{
		final BankTemplate t = target;
		final int columns = t != null ? t.getColumns() : BankLayoutRenderer.ITEMS_PER_ROW;
		edit(tab, slots ->
		{
			for (int i = 0; i < columns; i++)
			{
				slots.add(BankTemplate.EMPTY);
			}
		});
	}

	/** Empties a tab. */
	void clearTab(int tab)
	{
		edit(tab, List::clear);
	}

	// Applies a mutation to a tab's slot list under the template lock, then saves and refreshes. A
	// no-op if editing has already stopped (so a late drag/drop can't touch a finished session).
	private void edit(int tab, java.util.function.Consumer<List<Integer>> op)
	{
		final BankTemplate t = liveTemplate();
		if (t == null)
		{
			return;
		}
		synchronized (t)
		{
			op.accept(t.editableTab(tab));
		}
		templateManager.saveUserTemplate(t);
		notifyChanged();
	}

	// Drop trailing empty slots from a tab so a saved layout doesn't carry dead space at the end.
	private static void trimTrailing(List<Integer> slots)
	{
		for (int i = slots.size() - 1; i >= 0; i--)
		{
			final Integer v = slots.get(i);
			if (v != null && v != BankTemplate.EMPTY)
			{
				break;
			}
			slots.remove(i);
		}
	}

	private void notifyChanged()
	{
		for (Runnable r : listeners)
		{
			try
			{
				r.run();
			}
			catch (RuntimeException e)
			{
				log.warn("Layout editor listener failed", e);
			}
		}
	}
}
