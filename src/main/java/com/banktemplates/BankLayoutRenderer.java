package com.banktemplates;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
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

	private final Client client;
	private final ItemManager itemManager;
	private final TemplateManager templateManager;
	private final BankTemplatesConfig config;
	private final LayoutEditor layoutEditor;

	// True while drawing for the layout editor: items render as faded placeholders even if unowned, and
	// non-template bank items are not appended, so the design (and the "+" slot) stays clean.
	private boolean editing;

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
	static boolean isBankFiltered(Client client)
	{
		final Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (title == null || title.getText() == null)
		{
			return false;
		}
		final String t = Text.removeTags(title.getText()).toLowerCase();
		return t.contains("tag tab") || t.startsWith("showing");
	}

	void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING)
		{
			return;
		}

		// A Bank Tags "tag tab" (or a search) is showing a filtered view - don't touch the widgets at
		// all (not even resetWidgets), or we'd clobber Bank Tags' own layout until it redraws.
		if (isBankFiltered(client))
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

		int[] layout = template.tabLayout(client.getVarbitValue(VarbitID.BANK_CURRENTTAB));
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
		try
		{
			layout(layout, template.getColumns());
		}
		finally
		{
			this.editing = false;
		}
	}

	// Tab icons are set during the bank build, so override them AFTER it runs (post-fire), or they'd be
	// clobbered.
	void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING || isBankFiltered(client))
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
	}

	// Override the native tab-button icons to the template's first item per tab. The tab icons are the
	// item-bearing children of Bankmain.TABS, in tab order (1..N). Only existing real tabs are changed;
	// when the template is switched off, the game's own rebuild restores the real icons.
	private void applyTabIcons(BankTemplate template)
	{
		final Widget tabs = client.getWidget(InterfaceID.Bankmain.TABS);
		if (tabs == null)
		{
			return;
		}
		int tab = 1;
		for (Widget[] group : new Widget[][]{tabs.getDynamicChildren(), tabs.getStaticChildren(), tabs.getNestedChildren()})
		{
			if (group == null)
			{
				continue;
			}
			for (Widget c : group)
			{
				if (c == null || c.getItemId() <= 0 || tab > 9)
				{
					continue;
				}
				final int icon = firstItem(template.tabLayout(tab));
				if (icon > 0)
				{
					c.setItemId(icon);
					c.setItemQuantity(0);
					c.revalidate();
				}
				tab++;
			}
		}
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
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		final Widget itemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bank == null || itemContainer == null)
		{
			return;
		}

		// Collect the items currently shown and hide every item widget; we redraw them in order below.
		final Set<Integer> bankItems = new LinkedHashSet<>();
		for (int i = 0; i < bank.size(); ++i)
		{
			final Widget c = itemContainer.getChild(i);
			if (c == null)
			{
				continue;
			}
			if (!c.isSelfHidden() && c.getItemId() > -1 && c.getItemId() != ItemID.BLANKOBJECT)
			{
				bankItems.add(c.getItemId());
				c.setHidden(true);
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

		// Draw the template slots.
		int lastContentSlot = -1;
		for (int pos = 0; pos < layout.length; ++pos)
		{
			final Widget c = itemContainer.getChild(pos);
			if (c == null || c.getOriginalHeight() != ITEM_HEIGHT)
			{
				break;
			}

			final int slot = layout[pos];
			if (slot == BankTemplate.EMPTY)
			{
				drawEmpty(c, pos, columns);
			}
			else if (slot == BankTemplate.FILLER)
			{
				drawFiller(c, pos, columns);
				lastContentSlot = pos;
			}
			else
			{
				final Integer bankItemId = templateToBank.get(slot);
				drawItem(c, bank, bankItemId != null ? bankItemId : slot, pos, columns);
				lastContentSlot = pos;
			}
		}

		// Items in this tab not covered by the template: append below, or hide if configured.
		final int appendStart = ((layout.length + columns - 1) / columns) * columns;

		for (int pos = layout.length; pos < appendStart; ++pos)
		{
			final Widget c = itemContainer.getChild(pos);
			if (c == null || c.getOriginalHeight() != ITEM_HEIGHT)
			{
				break;
			}
			drawEmpty(c, pos, columns);
		}

		int slotIdx = appendStart;
		// While editing we don't append the player's other bank items - the grid is the design surface,
		// and the slot right after the layout is the editor's "+" (add item) target.
		if (!editing && !config.hideNonTemplateItems())
		{
			for (int itemId : bankItems)
			{
				final Widget c = itemContainer.getChild(slotIdx);
				if (c == null || c.getOriginalHeight() != ITEM_HEIGHT)
				{
					break;
				}
				drawItem(c, bank, itemId, slotIdx, columns);
				lastContentSlot = slotIdx;
				++slotIdx;
			}
		}

		// Blank the remaining slots. (When hiding non-template items, leftover widgets stay hidden.)
		while (true)
		{
			final Widget c = itemContainer.getChild(slotIdx);
			if (c == null || c.getOriginalHeight() != ITEM_HEIGHT)
			{
				break;
			}
			drawEmpty(c, slotIdx, columns);
			++slotIdx;
		}

		// Grow the bank's scroll region so a template taller than the default view can be scrolled.
		// bankmain_finishbuilding (about to run) reads the scroll height as int arg 9 from the end of
		// the stack; growing it makes the script size the scrollbar to fit our layout.
		if (lastContentSlot >= 0)
		{
			final int rows = (lastContentSlot / columns) + 1;
			final int height = rows * (ITEM_HEIGHT + ITEM_Y_PADDING);
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

		c.setItemId(item);
		c.setName("<col=ff9040>" + def.getName() + "</col>");
		c.clearActions();
		c.setOnDragListener((Object[]) null);

		if (qty <= 0)
		{
			// In the editor, always show a faded placeholder - otherwise items you add but don't own
			// would be invisible and impossible to arrange.
			if (!config.showPlaceholders() && !editing)
			{
				drawEmpty(c, idx, columns);
				return;
			}
			c.setItemQuantity(0);
			c.setItemQuantityMode(ItemQuantityMode.NEVER);
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

		// Block dragging while a template is applied; manual reorganising uses the reorg helper view.
		c.setOnDragCompleteListener((JavaScriptCallback) ev -> client.setDraggedOnWidget(null));

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
		c.setOnDragListener((Object[]) null);
		c.setOnDragCompleteListener((JavaScriptCallback) ev -> client.setDraggedOnWidget(null));
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

		final int posX = (idx % columns) * (ITEM_WIDTH + ITEM_X_PADDING) + ITEM_START_X;
		final int posY = (idx / columns) * (ITEM_HEIGHT + ITEM_Y_PADDING);
		c.setHidden(false);
		c.setOriginalX(posX);
		c.setOriginalY(posY);
		c.revalidate();
	}

	private void position(Widget c, int idx, int columns)
	{
		final int posX = (idx % columns) * (ITEM_WIDTH + ITEM_X_PADDING) + ITEM_START_X;
		final int posY = (idx / columns) * (ITEM_HEIGHT + ITEM_Y_PADDING);
		c.setOriginalX(posX);
		c.setOriginalY(posY);
		c.revalidate();
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
