package com.banktemplates;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

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
	private final TemplateManager templateManager;

	// volatile: read from the client thread (renderer/overlay) and the Swing thread (panel/editor).
	private volatile boolean editing;
	private volatile BankTemplate target;
	// A deep copy taken when editing starts, so the whole session can be reverted.
	private volatile BankTemplate snapshot;

	// Notified after any change (and on start/stop) so the panel editor and the bank view refresh.
	private final List<Runnable> listeners = new ArrayList<>();

	@Inject
	LayoutEditor(TemplateManager templateManager)
	{
		this.templateManager = templateManager;
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

	/** True when the bank should show {@code template} in editable form. */
	boolean isEditing(BankTemplate template)
	{
		final BankTemplate t = target;
		return editing && t != null && template != null && t.getName().equals(template.getName());
	}

	/**
	 * Begins editing a user template. Makes it the active template so it renders over the bank, and
	 * snapshots it for {@link #revert()}. Presets and nulls are rejected.
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
		templateManager.setActive(template);
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

	/** Appends an item to a tab (used by the "+" / search). */
	void addItem(int tab, int itemId)
	{
		if (itemId > 0)
		{
			edit(tab, slots -> slots.add(itemId));
		}
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
		final BankTemplate t = target;
		if (!editing || t == null)
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
