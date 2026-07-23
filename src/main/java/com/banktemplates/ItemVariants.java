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
 * The rest of the supplement covers the same shape of gap: forestry and lumberjack, spirit angler and
 * angler, forestry basket and log basket, radiant oathplate, sanguine torva, twisted ancestral.
 *
 * Only add a family here after confirming RuneLite really does leave it ungrouped, and that the pieces
 * are genuinely interchangeable for the purpose of "does this slot hold the right thing" - a recolour
 * or an upgrade of the same item, not a different item that merely shares a name. Most sets need no
 * entry: crystal armour, the crystal crown, bow of faerdhinen and blade of saeldor already carry all
 * eight elven clan recolours in RuneLite's own groups, as do slayer helmets, black masks and graceful.
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

		// Forestry outfit (anima-infused bark recolour) -> lumberjack. Same woodcutting bonus,
		// and RuneLite groups neither side, so the two sets never matched each other.
		EXTRA.put(28173, 10941); // Forestry hat   -> Lumberjack hat
		EXTRA.put(28169, 10939); // Forestry top   -> Lumberjack top
		EXTRA.put(28171, 10940); // Forestry legs  -> Lumberjack legs
		EXTRA.put(28175, 10933); // Forestry boots -> Lumberjack boots

		// Spirit angler -> angler. Keyed on 25592, the base of RuneLite's own "spirit angler headband"
		// group, so its second member (31252) collapses with it rather than being left behind.
		EXTRA.put(25592, 13258); // Spirit angler headband -> Angler hat
		EXTRA.put(25594, 13259); // Spirit angler top      -> Angler top
		EXTRA.put(25596, 13260); // Spirit angler waders   -> Angler waders
		EXTRA.put(25598, 13261); // Spirit angler boots    -> Angler boots

		// Forestry basket -> log basket, the upgrade of the same storage item. Both sides are grouped,
		// but as two separate groups, so this links group base to group base and the open forms
		// (28145 and 28142) follow their own groups.
		EXTRA.put(28143, 28140); // Forestry basket -> Log basket

		// Radiant oathplate -> oathplate.
		EXTRA.put(30777, 30750); // Radiant oathplate helm  -> Oathplate helm
		EXTRA.put(30779, 30753); // Radiant oathplate chest -> Oathplate chest
		EXTRA.put(30781, 30756); // Radiant oathplate legs  -> Oathplate legs

		// Sanguine torva -> torva. The targets are the ordinary pieces; RuneLite maps those onto the
		// damaged ids (26376/26378/26380), which is the group identity the rest of the plugin sees.
		EXTRA.put(28254, 26382); // Sanguine torva full helm -> Torva full helm
		EXTRA.put(28256, 26384); // Sanguine torva platebody -> Torva platebody
		EXTRA.put(28258, 26386); // Sanguine torva platelegs -> Torva platelegs

		// Corrupted voidwaker -> voidwaker, the other completed form. RuneLite groups the ordinary and
		// deadman voidwakers together and leaves the corrupted one out. The blade, hilt and gem stay
		// separate: they are the pieces you bank before assembling one, not another finished weapon.
		EXTRA.put(28531, 27690); // Corrupted voidwaker -> Voidwaker

		// Twisted ancestral -> ancestral. The colour kit (24670) is deliberately absent: it is a
		// separate item a player can bank on its own, not a robe.
		EXTRA.put(24664, 21018); // Twisted ancestral hat         -> Ancestral hat
		EXTRA.put(24666, 21021); // Twisted ancestral robe top    -> Ancestral robe top
		EXTRA.put(24668, 21024); // Twisted ancestral robe bottom -> Ancestral robe bottom
	}

	/**
	 * The base id for {@code itemId}, collapsing recolours and other variants onto one identity.
	 * Falls back to RuneLite's mapping, which returns the id itself when it knows no group.
	 *
	 * The supplement is consulted both before and after that mapping. Some variant sets are groups in
	 * their own right rather than loose ids - spirit angler headband holds 25592 and 31252, forestry
	 * basket holds the closed and open forms - so a supplement keyed on the group's base only reaches
	 * the other members once RuneLite has collapsed them onto it first.
	 */
	static int base(int itemId)
	{
		final int canonical = ItemVariationMapping.map(itemId);
		Integer extra = EXTRA.get(itemId);
		if (extra == null)
		{
			extra = EXTRA.get(canonical);
		}
		return extra != null ? ItemVariationMapping.map(extra) : canonical;
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
				// Pull in the rest of the variant's own group, if it has one, so linking to its base
				// brings its siblings along: the open forestry basket, the second angler headband.
				out.addAll(ItemVariationMapping.getVariations(e.getKey()));
			}
		}
		return out;
	}
}
