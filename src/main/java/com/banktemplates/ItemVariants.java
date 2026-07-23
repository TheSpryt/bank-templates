package com.banktemplates;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.client.game.ItemVariationMapping;

/**
 * Variant collapsing, with a supplement for families RuneLite's own mapping misses.
 *
 * Everywhere this plugin compares "is the item in this slot the one the template wants", it collapses
 * variants first, so holding a different version of an item never counts as the wrong item. That is
 * normally {@link ItemVariationMapping}, but its data is incomplete: some recoloured sets are simply
 * absent from it, and an absent item maps to itself.
 *
 * The prospector set is the case that surfaced it (issue #37). Base prospector pieces are grouped -
 * 12013 and 29472 both map to "prospector helmet" - while the GOLDEN pieces sit in no group at all.
 * A template built with golden prospector therefore never matched a player's ordinary prospector, and
 * the reorganise helper told them to move a helmet that was already exactly where it belonged, down
 * to the bottom of the tab with the items the template does not want.
 *
 * Only add a family here after confirming RuneLite really does leave it ungrouped, and that the pieces
 * are genuinely interchangeable for the purpose of "does this slot hold the right thing" - a recolour
 * or a cosmetic kit, not a different item that merely shares a name.
 */
final class ItemVariants
{
	private ItemVariants()
	{
	}

	/** Extra variant -> base links, for sets RuneLite's item_variations.json does not group. */
	private static final Map<Integer, Integer> EXTRA = new HashMap<>();

	static
	{
		// Golden prospector (Motherlode Mine recolour) -> ordinary prospector. Verified against
		// RuneLite 1.12.30's item_variations.json: every base piece is grouped, every golden one is not.
		EXTRA.put(25549, 12013); // Golden prospector helmet -> Prospector helmet
		EXTRA.put(25551, 12014); // Golden prospector jacket -> Prospector jacket
		EXTRA.put(25553, 12015); // Golden prospector legs   -> Prospector legs
		EXTRA.put(25555, 12016); // Golden prospector boots  -> Prospector boots
	}

	/**
	 * The base id for {@code itemId}, collapsing recolours and other variants onto one identity.
	 * Falls back to RuneLite's mapping, which returns the id itself when it knows no group.
	 */
	static int base(int itemId)
	{
		final Integer extra = EXTRA.get(itemId);
		final int id = extra != null ? extra : itemId;
		return ItemVariationMapping.map(id);
	}

	/**
	 * Every id that counts as {@code baseId}, including the supplemented ones. Needed as well as
	 * {@link #base(int)} because matching runs in both directions: a template asking for the golden
	 * piece has to accept the ordinary one, and a template asking for the ordinary piece has to accept
	 * the golden one. RuneLite's list only knows the half it grouped.
	 */
	static Collection<Integer> variations(int baseId)
	{
		final Set<Integer> out = new LinkedHashSet<>(ItemVariationMapping.getVariations(baseId));
		out.add(baseId);
		for (Map.Entry<Integer, Integer> e : EXTRA.entrySet())
		{
			if (ItemVariationMapping.map(e.getValue()) == baseId)
			{
				out.add(e.getKey());
			}
		}
		return out;
	}
}
