package com.banktemplates;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * Snapshots the player's current bank into a {@link BankTemplate}, one layout per native tab.
 * <p>
 * The bank container is ordered tab 1, tab 2, … tab 9, then the main ("all items") view. The number
 * of items in each numbered tab is held in {@code BANK_TAB_1..9}, so we slice the container by those
 * counts; whatever's left is the main view.
 */
final class BankCapture
{
	// Package-visible: the bank-snapshot sync slices the container by the same per-tab counts.
	static final int[] TAB_COUNT_VARBITS = {
		VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4,
		VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8,
		VarbitID.BANK_TAB_9
	};

	private BankCapture()
	{
	}

	/**
	 * Captures the open bank. Must run on the client thread. Returns {@code null} if the bank is
	 * closed or empty.
	 */
	static BankTemplate capture(Client client, ItemManager itemManager, String name)
	{
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return null;
		}

		final Item[] items = bank.getItems();
		if (items.length == 0)
		{
			return null;
		}

		final BankTemplate template = new BankTemplate();
		template.setName(name);
		template.setColumns(BankLayoutRenderer.ITEMS_PER_ROW);

		int idx = 0;
		for (int tab = 1; tab <= 9; tab++)
		{
			final int count = client.getVarbitValue(TAB_COUNT_VARBITS[tab - 1]);
			if (count <= 0)
			{
				continue;
			}
			final List<Integer> layout = new ArrayList<>();
			for (int k = 0; k < count && idx < items.length; k++, idx++)
			{
				layout.add(slot(items[idx], itemManager));
			}
			if (!layout.isEmpty())
			{
				template.putTab(tab, layout);
			}
		}

		// Everything remaining is the main (tab 0) view.
		final List<Integer> main = new ArrayList<>();
		for (; idx < items.length; idx++)
		{
			main.add(slot(items[idx], itemManager));
		}
		if (!main.isEmpty())
		{
			template.putTab(BankTemplate.MAIN_TAB, main);
		}

		return template.tabCount() > 0 ? template : null;
	}

	private static int slot(Item item, ItemManager itemManager)
	{
		final int id = item.getId();
		if (id <= 0)
		{
			return BankTemplate.EMPTY;
		}
		// A bank filler (the 🚫 reserved-slot item) is captured as a FILLER slot, never a real item, so it's
		// never counted in the template's item totals or the "x / y items" owned count.
		if (id == BankTemplate.FILLER)
		{
			return BankTemplate.FILLER;
		}
		return itemManager.canonicalize(id);
	}
}
