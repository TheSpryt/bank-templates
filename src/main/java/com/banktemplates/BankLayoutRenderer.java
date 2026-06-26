package com.banktemplates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.Text;

/**
 * Renders the active {@link BankTemplate} over the bank by repositioning the bank item-container's
 * child widgets - entirely client-side, like RuneLite's built-in Bank Tag Layouts. The real bank is
 * never touched.
 * <p>
 * It applies the layout for whichever native tab is currently shown ({@link VarbitID#BANK_CURRENTTAB}),
 * positions items on a grid of the template's designed column count, and either appends or hides bank
 * items not covered by the template.
 */
@Slf4j
@Singleton
public class BankLayoutRenderer
{
	// Default bank item grid geometry, matching the values the bank interface itself uses.
	static final int ITEMS_PER_ROW = 8;
	private static final int ITEM_WIDTH = 36;
	private static final int ITEM_HEIGHT = 32;
	private static final int ITEM_X_PADDING = 12;
	private static final int ITEM_Y_PADDING = 4;
	private static final int ITEM_START_X = 51;
	// Extra vertical space inserted at each all-items tab boundary, so a divider line sits in the gap
	// instead of overlapping the rows above/below it (mirrors how the real bank spaces its dividers).
	private static final int DIVIDER_GAP = 10;

	private final Client client;
	private final ItemManager itemManager;
	private final TemplateManager templateManager;
	private final BankTemplatesConfig config;
	private final LayoutEditor layoutEditor;

	// True while drawing for the layout editor: items render as faded placeholders even if unowned, and
	// non-template bank items are not appended, so the design (and the "+" slot) stays clean.
	private boolean editing;
	// True while rendering an editable (non-preset) template, so item slots get a "Release" menu entry.
	private boolean liveEditable;
	// Row indices where a tab-divider line should be drawn (all-items view only).
	private int[] mainDividerRows = new int[0];
	// Virtual item widgets we create to render template slots beyond the real bank's capacity, so a
	// template larger than your bank still shows in full. Reused across rebuilds; reset if the bank's
	// item container changes (e.g. the bank is reopened).
	private final java.util.List<Widget> virtualSlots = new java.util.ArrayList<>();
	private Widget virtualContainer;
	// Cached filtered (tag tab / search) state from the last bank build, for the reorg overlay to read
	// (it can't detect it from the title itself, since it overwrites the title with step instructions).
	private volatile boolean bankFiltered;
	// The widget placed at each rendered slot, so the editor overlay can locate slots by position even
	// though a widget's container index no longer equals its rendered slot (we move items by identity).
	private volatile Widget[] slotWidgets = new Widget[0];
	// Number of slots the current tab's template defines (so Release is only added to those, not to
	// appended bank items).
	private int templateLen;

	@Inject
	BankLayoutRenderer(Client client, ItemManager itemManager, TemplateManager templateManager,
		BankTemplatesConfig config, LayoutEditor layoutEditor)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.templateManager = templateManager;
		this.config = config;
		this.layoutEditor = layoutEditor;
	}

	/**
	 * True when the bank is showing a filtered view - a Bank Tags "tag tab" ("Tag tab …") or a search
	 * ("Showing …") - in which case we leave the layout alone. Read from the bank title since the
	 * Bank Tags service isn't injectable from an external plugin.
	 */
	// The filtered state detected at the last bank build (safe to read every frame, unlike the title which
	// the reorg overlay overwrites).
	boolean isBankFilteredNow()
	{
		return bankFiltered;
	}

	// The widget the last layout placed at rendered slot {@code pos} (or null). Used by the editor overlay
	// to find a slot's on-screen widget without assuming the widget's container index equals the slot.
	Widget slotWidgetAt(int pos)
	{
		final Widget[] w = slotWidgets;
		return w != null && pos >= 0 && pos < w.length ? w[pos] : null;
	}

	// The first rendered slot holding no item (an empty/gap slot we placed), or null if there's none.
	// Hosts the "+" add button so it can never land on - and block the withdrawal of - a real item.
	Widget firstEmptySlot()
	{
		final Widget[] w = slotWidgets;
		if (w == null)
		{
			return null;
		}
		for (final Widget s : w)
		{
			if (s != null && !s.isHidden() && s.getItemId() <= 0)
			{
				return s;
			}
		}
		return null;
	}

	// A free widget for a slot that isn't an owned item (empty, filler, or unowned placeholder): an empty
	// bank-slot widget if one's left, otherwise a created virtual widget.
	private Widget takeSpare(java.util.Deque<Widget> spares, Widget container, int[] virt)
	{
		final Widget s = spares.poll();
		return s != null ? s : obtainVirtual(container, virt[0]++);
	}

	static boolean isBankFiltered(Client client)
	{
		final Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (title == null || title.getText() == null)
		{
			return false;
		}
		final String t = Text.removeTags(title.getText()).toLowerCase();
		// "tag tab"/"showing" = a Bank Tags tag tab or a search. "inventory setup" = the Inventory Setups
		// bank view, which overrides the bank title to "Inventory Setup <name>". In all of these the bank is
		// showing a filtered/foreign layout, so we must not apply ours over the top of it.
		return t.contains("tag tab") || t.startsWith("showing") || t.startsWith("inventory setup");
	}

	void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING)
		{
			return;
		}

		// Hide any overflow (virtual) slots up front; layout() re-shows the ones it needs this build.
		hideVirtualSlots();

		// Detect the filtered (Bank Tags tag tab / search) state now, at build time, and cache it: the
		// reorg overlay can't read it from the title later because it overwrites the title with steps.
		bankFiltered = isBankFiltered(client);

		// A Bank Tags "tag tab" (or a search) is showing a filtered view - don't touch the widgets at
		// all (not even resetWidgets), or we'd clobber Bank Tags' own layout until it redraws.
		if (bankFiltered)
		{
			return;
		}

		resetWidgets();

		final BankTemplate template = templateManager.getActive();
		if (template == null)
		{
			return;
		}

		final boolean editingNow = layoutEditor.isEditing(template);

		// Reorganise-helper mode shows the real bank (with the guide overlay) rather than the virtual
		// layout, so don't apply the template while it's on - unless we're actively editing this
		// template, in which case the editable layout always renders.
		if (!editingNow && (config.showReorgHelper() || !config.applyLayout()))
		{
			return;
		}

		final int currentTab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);
		int[] layout;
		if (currentTab == BankTemplate.MAIN_TAB)
		{
			// The all-items view shows every defined tab's items, grouped (main first), with dividers.
			layout = buildMainView(template);
		}
		else
		{
			layout = template.tabLayout(currentTab);
			// A numbered tab gets a single divider where its template items end and your other items begin.
			final int cols = template.getColumns();
			final int len = layout == null ? 0 : layout.length;
			this.mainDividerRows = len > 0 ? new int[]{(len + cols - 1) / cols} : new int[0];
		}
		if (layout == null || layout.length == 0)
		{
			if (!editingNow)
			{
				// This template doesn't define the tab being viewed: leave the bank in its default state.
				return;
			}
			// Editing a tab the template doesn't cover yet: render an empty, editable grid.
			layout = new int[0];
		}

		this.editing = editingNow;
		this.liveEditable = !template.isPreset();
		try
		{
			layout(layout, template.getColumns());
		}
		finally
		{
			this.editing = false;
			this.liveEditable = false;
		}
	}

	// Tab icons are set during the bank build, so override them AFTER it runs (post-fire), or they'd be
	// clobbered.
	void onScriptPostFired(ScriptPostFired event)
	{
		// Apply tab icons even on a Bank Tags tag tab / search: the numbered tab buttons are the same
		// there, and otherwise the game resets them to the live bank's first items.
		if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING)
		{
			return;
		}
		final BankTemplate template = templateManager.getActive();
		if (template == null)
		{
			return;
		}
		if (!layoutEditor.isEditing(template) && (config.showReorgHelper() || !config.applyLayout()))
		{
			return;
		}
		applyTabIcons(template);

		// Overwrite the bank's slot counter to show the active template's footprint (items + placeholders +
		// fillers) instead of your live item count, in red when it won't fit your bank.
		final int used = templateSlotCount(template);
		final int capacity = parseCount(client.getWidget(InterfaceID.Bankmain.CAPACITY));
		final Widget occupied = client.getWidget(InterfaceID.Bankmain.OCCUPIEDSLOTS);
		if (occupied != null && used > 0)
		{
			occupied.setText(capacity > 0 && used > capacity ? "<col=ff3030>" + used + "</col>" : Integer.toString(used));
		}

		// While editing, show the "Editing ..." banner in the bank's real title bar (set after the bank's
		// own title so it isn't clobbered). Over-capacity is conveyed by the red slot counter above.
		if (layoutEditor.isEditing(template))
		{
			final Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
			if (title != null)
			{
				title.setText("Editing \"" + template.getName() + "\"");
			}
		}
	}

	// The active template's footprint: every defined slot that occupies a real bank slot (items you own,
	// placeholders for items you don't, and fillers) - i.e. everything except empty gaps.
	private static int templateSlotCount(BankTemplate template)
	{
		int n = 0;
		for (TabLayout tl : template.getTabs())
		{
			for (Integer v : tl.getLayout())
			{
				if (v != null && v > 0)
				{
					n++;
				}
			}
		}
		return n;
	}

	private static int parseCount(Widget w)
	{
		if (w == null || w.getText() == null)
		{
			return -1;
		}
		final String digits = w.getText().replaceAll("[^0-9]", "");
		if (digits.isEmpty())
		{
			return -1;
		}
		try
		{
			return Integer.parseInt(digits);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	// Tab-row geometry (from the native widgets): icons at x = 82 + 40*slot, backgrounds at 79 + 40*slot.
	private static final int TAB_ICON_X = 82;
	private static final int TAB_BG_X = 79;
	private static final int TAB_W = 40;

	// Override the native tab-button icons to the template's first item per tab (the item-bearing children
	// of Bankmain.TABS, in tab order 1..N). When "Hide items not in the template" is on, tabs the template
	// doesn't define are removed and the remaining tabs (and the + button) shift left to close the gap.
	// The game's own rebuild restores everything when the template/option is switched off.
	private void applyTabIcons(BankTemplate template)
	{
		final Widget tabs = client.getWidget(InterfaceID.Bankmain.TABS);
		if (tabs == null)
		{
			return;
		}

		final List<Widget> icons = new ArrayList<>();
		final List<Widget> backgrounds = new ArrayList<>();
		Widget addIcon = null;
		Widget addBg = null;
		for (Widget[] group : new Widget[][]{tabs.getDynamicChildren(), tabs.getStaticChildren(), tabs.getNestedChildren()})
		{
			if (group == null)
			{
				continue;
			}
			for (Widget c : group)
			{
				if (c == null)
				{
					continue;
				}
				if (c.getItemId() > 0)
				{
					icons.add(c);
				}
				else if (c.getSpriteId() == SpriteID.Banktabs.TAB)
				{
					backgrounds.add(c);
				}
				else if (c.getSpriteId() == SpriteID.BanktabIcons.ADD)
				{
					addIcon = c;
				}
				else if (c.getSpriteId() == SpriteID.Banktabs.EMPTY)
				{
					addBg = c;
				}
			}
		}

		final boolean hide = config.hideNonTemplateItems();
		final List<Integer> defined = template.definedTabs();

		int packed = 0;
		for (int i = 0; i < icons.size() && i < 9; i++)
		{
			final Widget icon = icons.get(i);
			final int tabNum = i + 1;
			if (hide && !defined.contains(tabNum))
			{
				icon.setHidden(true);
				continue;
			}
			final int iconItem = firstItem(template.tabLayout(tabNum));
			if (iconItem > 0)
			{
				icon.setItemId(iconItem);
				icon.setItemQuantity(0);
			}
			if (hide)
			{
				icon.setHidden(false);
				icon.setOriginalX(TAB_ICON_X + packed * TAB_W);
			}
			icon.revalidate();
			packed++;
		}

		if (hide)
		{
			// Keep one background per shown tab; hide the rest; shift the + button after them.
			for (int b = 0; b < backgrounds.size(); b++)
			{
				final Widget bg = backgrounds.get(b);
				bg.setHidden(b >= packed);
				if (b < packed)
				{
					bg.setOriginalX(TAB_BG_X + b * TAB_W);
				}
				bg.revalidate();
			}
			if (addIcon != null)
			{
				addIcon.setOriginalX(TAB_ICON_X + packed * TAB_W);
				addIcon.revalidate();
			}
			if (addBg != null)
			{
				addBg.setOriginalX(TAB_BG_X + packed * TAB_W);
				addBg.revalidate();
			}
		}
	}

	// Builds the all-items view: every defined tab's items (main first), each padded to a full row so the
	// next tab starts on a fresh line. Records the boundary row after each tab (for divider lines). When
	// editing, the main tab gets a guaranteed trailing empty slot to host the "+" add button.
	private int[] buildMainView(BankTemplate template)
	{
		final int columns = template.getColumns();
		// The editor "+" sits at child[mainTabLength]. Reserve a slot for it (for any editable template) so
		// it always lands in its own slot at the end of the main tab, never on the next tab's first item.
		final boolean reservePlus = !template.isPreset();
		final List<Integer> combined = new ArrayList<>();
		final List<Integer> rows = new ArrayList<>();

		// The main tab (0) always comes first - even if it has no items, so the "+" has a home there.
		final int[] mainItems = template.tabLayout(BankTemplate.MAIN_TAB);
		if (mainItems != null)
		{
			for (int v : mainItems)
			{
				combined.add(v);
			}
		}
		if (reservePlus)
		{
			combined.add(BankTemplate.EMPTY);
		}
		while (combined.size() % columns != 0)
		{
			combined.add(BankTemplate.EMPTY);
		}
		rows.add(combined.size() / columns);

		// Then each numbered tab, in order.
		for (int tab : template.definedTabs())
		{
			if (tab == BankTemplate.MAIN_TAB)
			{
				continue;
			}
			final int[] items = template.tabLayout(tab);
			if (items != null)
			{
				for (int v : items)
				{
					combined.add(v);
				}
			}
			while (combined.size() % columns != 0)
			{
				combined.add(BankTemplate.EMPTY);
			}
			rows.add(combined.size() / columns);
		}
		this.mainDividerRows = new int[rows.size()];
		for (int i = 0; i < rows.size(); i++)
		{
			this.mainDividerRows[i] = rows.get(i);
		}
		final int[] arr = new int[combined.size()];
		for (int i = 0; i < arr.length; i++)
		{
			arr[i] = combined.get(i);
		}
		return arr;
	}

	// A tab's icon is its first real item.
	private static int firstItem(int[] layout)
	{
		if (layout == null)
		{
			return 0;
		}
		for (int v : layout)
		{
			if (v > 0 && v != BankTemplate.FILLER)
			{
				return v;
			}
		}
		return 0;
	}

	private void layout(int[] layout, int columns)
	{
		this.templateLen = layout.length;
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		final Widget itemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bank == null || itemContainer == null)
		{
			return;
		}

		// Collect every bank item with the widget that natively shows it, plus the empty item-slot widgets
		// as reusable "spares", and hide them all. We then reposition each item's OWN widget to its template
		// slot (rather than reassigning item ids onto fixed-position widgets), so a widget's container index
		// keeps matching the item on it - which is what other plugins (e.g. Inventory Setups) rely on to
		// highlight and withdraw the correct items.
		final Set<Integer> bankItems = new LinkedHashSet<>();
		final Map<Integer, Widget> itemToWidget = new HashMap<>();
		final java.util.Deque<Widget> spares = new java.util.ArrayDeque<>();
		final Item[] bankSlots = bank.getItems();
		for (int i = 0; ; ++i)
		{
			final Widget c = itemContainer.getChild(i);
			if (c == null || c.getOriginalHeight() != ITEM_HEIGHT)
			{
				break;
			}
			c.setHidden(true);
			// Read the item from the bank CONTAINER (child index i == bank slot i), NOT the widget's own
			// item id - which can be stale from our previous render and would wrongly drop an owned item to
			// a placeholder on the next pass (the bug where dragging turned an item you own into a placeholder).
			final int id = i < bankSlots.length && bankSlots[i] != null ? bankSlots[i].getId() : -1;
			if (id > -1 && id != ItemID.BLANKOBJECT)
			{
				bankItems.add(id);
				itemToWidget.putIfAbsent(id, c);
			}
			else
			{
				spares.add(c);
			}
		}

		// Match each template item to a concrete bank item (exact, then placeholder, then variant).
		final Map<Integer, Integer> templateToBank = new HashMap<>();
		final ItemMatcher[] matchers = {this::matchExact, this::matchPlaceholder, this::matchVariant};
		for (ItemMatcher matcher : matchers)
		{
			for (int itemId : layout)
			{
				if (itemId <= 0 || itemId == BankTemplate.FILLER || templateToBank.containsKey(itemId))
				{
					continue;
				}
				final int matched = matcher.match(bankItems, itemId);
				if (matched != -1)
				{
					templateToBank.put(itemId, matched);
					bankItems.remove(matched);
					final ItemComposition def = client.getItemDefinition(matched);
					bankItems.remove(def.getPlaceholderId());
				}
			}
		}

		// Draw each template slot using the matched item's OWN widget (preserving its container index), or a
		// spare/virtual widget for fillers, empty gaps, and placeholders for items you don't own. `placed`
		// records the widget at each slot so the editor overlay can find slots without assuming index==slot.
		final boolean mainView = client.getVarbitValue(VarbitID.BANK_CURRENTTAB) == BankTemplate.MAIN_TAB;
		final int appendStart = ((layout.length + columns - 1) / columns) * columns;
		final Widget[] placed = new Widget[Math.max(appendStart, layout.length)];
		final int[] virt = {0};
		int lastContentSlot = -1;
		for (int pos = 0; pos < layout.length; ++pos)
		{
			final int slot = layout[pos];
			Widget c;
			if (slot == BankTemplate.EMPTY)
			{
				c = takeSpare(spares, itemContainer, virt);
				if (c != null)
				{
					drawEmpty(c, pos, columns);
				}
			}
			else if (slot == BankTemplate.FILLER)
			{
				c = takeSpare(spares, itemContainer, virt);
				if (c != null)
				{
					drawFiller(c, pos, columns);
					lastContentSlot = pos;
				}
			}
			else
			{
				final Integer matched = templateToBank.get(slot);
				final Widget own = matched != null ? itemToWidget.remove(matched) : null;
				if (own != null)
				{
					drawItem(own, bank, matched, pos, columns);
					c = own;
					lastContentSlot = pos;
				}
				else
				{
					// You don't own this item yet: faded placeholder on a spare widget.
					c = takeSpare(spares, itemContainer, virt);
					if (c != null)
					{
						drawItem(c, bank, slot, pos, columns);
						lastContentSlot = pos;
					}
				}
			}
			placed[pos] = c;
		}

		// Pad the last template row so the "+" / extras start on a fresh row.
		for (int pos = layout.length; pos < appendStart; ++pos)
		{
			final Widget c = takeSpare(spares, itemContainer, virt);
			if (c == null)
			{
				break;
			}
			drawEmpty(c, pos, columns);
			placed[pos] = c;
		}

		// Append the player's other (non-template) items below the layout, using each one's own widget. In
		// a numbered tab while editing we skip them (the grid is the design surface); in the all-items view
		// we still show them, separated by a divider, so you can see what's not yet in the template.
		int slotIdx = appendStart;
		if ((!editing || mainView) && !config.hideNonTemplateItems())
		{
			for (int itemId : bankItems)
			{
				final Widget own = itemToWidget.remove(itemId);
				final Widget c = own != null ? own : takeSpare(spares, itemContainer, virt);
				if (c == null)
				{
					break;
				}
				drawItem(c, bank, itemId, slotIdx, columns);
				lastContentSlot = slotIdx;
				++slotIdx;
			}
		}

		// Whether any of the player's non-template items were appended below the layout.
		final boolean appendedExtras = slotIdx > appendStart;

		// Everything we didn't reuse (unmatched items, unused spares, old virtual slots) stays hidden.
		for (int i = virt[0]; i < virtualSlots.size(); i++)
		{
			final Widget w = virtualSlots.get(i);
			if (w != null)
			{
				w.setHidden(true);
			}
		}
		this.slotWidgets = placed;

		// Reposition the bank's thin tab-divider lines: in the all-items view between each tab's block of
		// items, and in any tab before your other (non-template) items appended below; hide spare ones.
		{
			final Widget[] children = itemContainer.getChildren();
			if (children != null)
			{
				final List<Integer> boundaries = new ArrayList<>();
				for (int i = 0; i < mainDividerRows.length; i++)
				{
					// The last boundary is the template/extras divider - only show it if extras follow.
					if (i < mainDividerRows.length - 1 || appendedExtras)
					{
						boundaries.add(mainDividerRows[i]);
					}
				}
				int di = 0;
				for (Widget c : children)
				{
					if (c == null || c.getSpriteId() == -1 || c.getOriginalHeight() <= 0 || c.getOriginalHeight() > 4)
					{
						continue;
					}
					if (di < boundaries.size())
					{
						// Sit the line in the gap just above the boundary row (centred in the gap).
						final int row = boundaries.get(di);
						c.setHidden(false);
						c.setOriginalX(ITEM_START_X);
						c.setOriginalY(rowY(row) - (ITEM_Y_PADDING + DIVIDER_GAP) / 2);
						c.revalidate();
						di++;
					}
					else
					{
						c.setHidden(true);
					}
				}
			}
		}

		// While editing, the "+" add button lives in the slot after the layout - make sure the scroll
		// region reaches it so it's never stranded below the visible bank.
		if (editing)
		{
			lastContentSlot = Math.max(lastContentSlot, layout.length);
		}

		// Grow the bank's scroll region so a template taller than the default view can be scrolled.
		// bankmain_finishbuilding (about to run) reads the scroll height as int arg 9 from the end of
		// the stack; growing it makes the script size the scrollbar to fit our layout.
		if (lastContentSlot >= 0)
		{
			// Include the all-items divider gaps so the scroll region reaches the last row.
			final int height = rowY(lastContentSlot / columns) + ITEM_HEIGHT + ITEM_Y_PADDING;
			final int[] intStack = client.getIntStack();
			final int intStackSize = client.getIntStackSize();
			if (intStackSize >= 9 && height > intStack[intStackSize - 9])
			{
				intStack[intStackSize - 9] = height;
			}
		}
	}

	private void drawItem(Widget c, ItemContainer bank, int item, int idx, int columns)
	{
		final ItemComposition def = client.getItemDefinition(item);
		if (def == null)
		{
			// Unknown item id (e.g. a hand-edited template or an item this client version doesn't have).
			drawEmpty(c, idx, columns);
			return;
		}
		final int qty = bank.count(item);
		// A real bank placeholder (the game's own marker - left after withdrawing your last one, or present while
		// the item is equipped/in your inventory) must NEVER render as a withdrawable item, or the client lets
		// you "withdraw" a phantom copy of an item you don't actually have (issue #8). The bank stores a
		// placeholder under the placeholder item's id with quantity 1, so count() returns 1 and the old qty<=0
		// check let it through as a real item - force it down the faded branch instead.
		final boolean isPlaceholder = def.getPlaceholderTemplateId() != -1;

		c.setItemId(item);
		c.setName("<col=ff9040>" + def.getName() + "</col>");
		c.clearActions();
		c.setOnDragListener((Object[]) null);

		if (qty <= 0 || isPlaceholder)
		{
			// Our own placeholders for items not in the bank at all are gated by the showPlaceholders config; a
			// real game placeholder is an actual bank slot, so always show it (matching the real bank view).
			if (!isPlaceholder && !config.showPlaceholders() && !editing)
			{
				drawEmpty(c, idx, columns);
				return;
			}
			c.setItemQuantity(0);
			// Show a "0" stack count (top-left, like the real bank's leftover placeholders) so it's clear at a
			// glance that you own none of this item.
			c.setItemQuantityMode(ItemQuantityMode.ALWAYS);
			c.setOpacity(120);
			c.setAction(10 - 1, "Examine");
		}
		else
		{
			c.setItemQuantity(qty);
			c.setItemQuantityMode(ItemQuantityMode.STACKABLE);
			c.setOpacity(0);

			final int quantityType = client.getVarbitValue(VarbitID.BANK_QUANTITY_TYPE);
			final int requestQty = client.getVarbitValue(VarbitID.BANK_REQUESTEDQUANTITY);

			final String suffix;
			switch (quantityType)
			{
				case 1:
					suffix = "5";
					break;
				case 2:
					suffix = "10";
					break;
				case 3:
					suffix = Integer.toString(Math.max(1, requestQty));
					break;
				case 4:
					suffix = "All";
					break;
				default:
					suffix = "1";
					break;
			}

			c.setAction(0, "Withdraw-" + suffix);
			if (quantityType != 0)
			{
				c.setAction(1, "Withdraw-1");
			}
			c.setAction(2, "Withdraw-5");
			c.setAction(3, "Withdraw-10");
			if (requestQty > 0)
			{
				c.setAction(4, "Withdraw-" + requestQty);
			}
			c.setAction(5, "Withdraw-X");
			c.setAction(6, "Withdraw-All");
			c.setAction(7, "Withdraw-All-but-1");
			if (client.getVarbitValue(VarbitID.BANK_LEAVEPLACEHOLDERS) == 0)
			{
				c.setAction(9, "Placeholder");
			}
			c.setAction(10, "Examine");
		}

		// While editing, let the slot be cleared from the template (left-shifting the rest) via a
		// client-side "Release" entry - it never touches the real bank.
		if (liveEditable && idx < templateLen)
		{
			c.setAction(8, "Release");
		}

		// Block dragging while a template is applied; manual reorganising uses the reorg helper view.
		c.setOnDragCompleteListener((JavaScriptCallback) ev -> client.setDraggedOnWidget(null));
		// Let the native bank drag run normally (the item follows the cursor). We intercept the drop, not the
		// drag itself, so the client completes its own drag and clears its drag state - this is what stops a
		// post-drag click from withdrawing the item that was dragged. The drag-complete listener above blocks
		// the real-bank reorder; our overlay rewrites the template to match the drop instead.

		position(c, idx, columns);
		c.setHidden(false);
	}

	private void drawFiller(Widget c, int idx, int columns)
	{
		final ItemComposition def = client.getItemDefinition(ItemID.BANK_FILLER);
		c.setItemId(ItemID.BANK_FILLER);
		c.setItemQuantity(0);
		c.setItemQuantityMode(ItemQuantityMode.NEVER);
		c.setName("<col=ff9040>" + def.getName() + "</col>");
		c.clearActions();
		c.setOpacity(0);
		if (liveEditable && idx < templateLen)
		{
			c.setAction(8, "Release");
		}
		c.setOnDragListener((Object[]) null);
		c.setOnDragCompleteListener((JavaScriptCallback) ev -> client.setDraggedOnWidget(null));
		// Let the native bank drag run normally (the item follows the cursor). We intercept the drop, not the
		// drag itself, so the client completes its own drag and clears its drag state - this is what stops a
		// post-drag click from withdrawing the item that was dragged. The drag-complete listener above blocks
		// the real-bank reorder; our overlay rewrites the template to match the drop instead.
		position(c, idx, columns);
		c.setHidden(false);
	}

	private void drawEmpty(Widget c, int idx, int columns)
	{
		c.setOriginalWidth(ITEM_WIDTH + ITEM_X_PADDING);
		c.setOriginalHeight(ITEM_HEIGHT + ITEM_Y_PADDING);
		c.clearActions();
		c.setItemId(-1);
		c.setItemQuantity(0);
		c.setOnDragListener((Object[]) null);
		c.setOnDragCompleteListener((JavaScriptCallback) ev -> client.setDraggedOnWidget(null));
		// Let the native bank drag run normally (the item follows the cursor). We intercept the drop, not the
		// drag itself, so the client completes its own drag and clears its drag state - this is what stops a
		// post-drag click from withdrawing the item that was dragged. The drag-complete listener above blocks
		// the real-bank reorder; our overlay rewrites the template to match the drop instead.

		final int posX = (idx % columns) * (ITEM_WIDTH + ITEM_X_PADDING) + ITEM_START_X;
		c.setHidden(false);
		c.setOriginalX(posX);
		c.setOriginalY(rowY(idx / columns));
		c.revalidate();
	}

	private void position(Widget c, int idx, int columns)
	{
		final int posX = (idx % columns) * (ITEM_WIDTH + ITEM_X_PADDING) + ITEM_START_X;
		c.setOriginalX(posX);
		c.setOriginalY(rowY(idx / columns));
		c.revalidate();
	}

	// Y of a grid row, including the extra gap added at each all-items tab divider above it.
	private int rowY(int row)
	{
		int gaps = 0;
		for (int boundary : mainDividerRows)
		{
			if (boundary <= row)
			{
				gaps++;
			}
		}
		return row * (ITEM_HEIGHT + ITEM_Y_PADDING) + gaps * DIVIDER_GAP;
	}

	// Returns a virtual item widget for an overflow slot (one past the bank's real capacity), reusing a
	// tracked child or creating a fresh one. Created widgets persist in the container across rebuilds; if
	// the container instance changes (bank reopened) we drop the stale references and start over.
	private Widget obtainVirtual(Widget container, int i)
	{
		if (container != virtualContainer)
		{
			virtualSlots.clear();
			virtualContainer = container;
		}
		Widget w = i < virtualSlots.size() ? virtualSlots.get(i) : null;
		if (w == null)
		{
			w = container.createChild(-1, WidgetType.GRAPHIC);
			if (i < virtualSlots.size())
			{
				virtualSlots.set(i, w);
			}
			else
			{
				virtualSlots.add(w);
			}
		}
		// Normalise the size each time (drawEmpty pads it, and resetWidgets doesn't reach these children).
		w.setOriginalWidth(ITEM_WIDTH);
		w.setOriginalHeight(ITEM_HEIGHT);
		return w;
	}

	private void hideVirtualSlots()
	{
		for (Widget w : virtualSlots)
		{
			if (w != null)
			{
				w.setHidden(true);
			}
		}
	}

	private void resetWidgets()
	{
		final Widget w = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (w == null || w.getChildren() == null)
		{
			return;
		}
		for (Widget c : w.getChildren())
		{
			if (c.getOriginalHeight() < ITEM_HEIGHT)
			{
				break;
			}
			if (c.getOriginalWidth() != ITEM_WIDTH || c.getOriginalHeight() != ITEM_HEIGHT)
			{
				c.setOriginalWidth(ITEM_WIDTH);
				c.setOriginalHeight(ITEM_HEIGHT);
				c.revalidate();
			}
		}
	}

	void remapWithdraw(MenuOptionClicked event)
	{
		if (!config.applyLayout() || templateManager.getActive() == null)
		{
			return;
		}
		// In a filtered view (Bank Tags tag tab, search, or an Inventory Setups bank filter) we never apply
		// our layout, so the native widget->slot mapping is intact - don't remap or we'd fight those plugins.
		if (isBankFiltered(client))
		{
			return;
		}
		if (event.getParam1() != InterfaceID.Bankmain.ITEMS)
		{
			return;
		}

		final MenuEntry menu = event.getMenuEntry();
		final Widget w = menu.getWidget();
		if (w == null || w.getItemId() <= 0)
		{
			return;
		}

		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return;
		}

		final int idx = bank.find(w.getItemId());
		if (idx > -1 && menu.getParam0() != idx)
		{
			menu.setParam0(idx);
		}
	}

	// Handles the client-side "Release" entry: clears that template slot (LayoutEditor.removeSlot shifts
	// the rest up). Purely edits the local template - nothing is sent to the server.
	void handleRelease(MenuOptionClicked event)
	{
		if (!"Release".equals(event.getMenuOption()) || event.getParam1() != InterfaceID.Bankmain.ITEMS)
		{
			return;
		}
		final BankTemplate template = templateManager.getActive();
		if (template == null || template.isPreset())
		{
			return;
		}
		event.consume();
		final int tab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);
		layoutEditor.removeSlot(tab, event.getParam0());
	}

	@FunctionalInterface
	private interface ItemMatcher
	{
		int match(Set<Integer> bank, int itemId);
	}

	private int matchExact(Set<Integer> bank, int itemId)
	{
		return bank.contains(itemId) ? itemId : -1;
	}

	private int matchPlaceholder(Set<Integer> bank, int itemId)
	{
		final int placeholderId = itemManager.getItemComposition(itemId).getPlaceholderId();
		return placeholderId != -1 && bank.contains(placeholderId) ? placeholderId : -1;
	}

	private int matchVariant(Set<Integer> bank, int itemId)
	{
		final int baseId = ItemVariationMapping.map(itemId);
		final Collection<Integer> variations = ItemVariationMapping.getVariations(baseId);
		if (variations.size() > 1)
		{
			for (int variationId : variations)
			{
				if (bank.contains(variationId))
				{
					return variationId;
				}
				final int placeholderId = itemManager.getItemComposition(variationId).getPlaceholderId();
				if (placeholderId != -1 && bank.contains(placeholderId))
				{
					return placeholderId;
				}
			}
		}
		return -1;
	}
}
