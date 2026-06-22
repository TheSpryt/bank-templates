package com.banktemplates;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Helper for reorganising your <b>real</b> bank to match the active template. Two complementary views,
 * chosen from the side panel:
 * <ul>
 *     <li><b>Labels</b> - every out-of-place item is tagged with where it belongs: first the tab it
 *     should move to, then (once in the right tab) its row-col, then nothing once it's correct.</li>
 *     <li><b>Step-by-step</b> - one move at a time: drag the highlighted item to the highlighted tab
 *     (tab phase), then swap/insert it into the highlighted slot (position phase).</li>
 * </ul>
 * It never moves anything - you do every drag. Only item order is matched (the real bank packs items
 * with no gaps, so template empty/filler columns aren't reproduced). Steps aside for Bank Tags.
 */
public class ReorgHelperOverlay extends Overlay
{
	private static final Color SOURCE_COLOR = new Color(255, 165, 0);   // orange: item to move
	private static final Color DONE_COLOR = new Color(110, 200, 110);
	private static final Color TEXT_BG = new Color(0, 0, 0, 180);
	private static final Font LABEL_FONT = new Font("Arial", Font.BOLD, 12);

	private static final int[] TAB_COUNT_VARBITS = {
		VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4,
		VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8,
		VarbitID.BANK_TAB_9
	};

	private final Client client;
	private final BankTemplatesConfig config;
	private final TemplateManager templateManager;
	private final ItemManager itemManager;

	// The chosen swap/insert mode is fixed per tab so the user only toggles once. Cached against the
	// tab + template it was decided for.
	private int planTab = -1;
	private String planTemplate = "";
	private boolean planSwap;

	@Inject
	ReorgHelperOverlay(Client client, BankTemplatesConfig config, TemplateManager templateManager, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.templateManager = templateManager;
		this.itemManager = itemManager;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showReorgHelper())
		{
			return null;
		}

		final BankTemplate template = templateManager.getActive();
		if (template == null || BankLayoutRenderer.isBankFiltered(client))
		{
			return null;
		}

		final Widget itemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (itemContainer == null || itemContainer.isHidden())
		{
			return null;
		}

		// Current tab's items in slot order, with widgets for bounds.
		final List<Widget> widgets = new ArrayList<>();
		final List<Integer> current = new ArrayList<>();
		final Widget[] children = itemContainer.getChildren();
		if (children != null)
		{
			for (Widget c : children)
			{
				if (c != null && !c.isSelfHidden() && c.getItemId() > 0 && c.getItemId() != ItemID.BLANKOBJECT)
				{
					widgets.add(c);
					current.add(functionalId(c.getItemId()));
				}
			}
		}
		if (current.isEmpty())
		{
			return null;
		}

		final int currentTab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);

		// Where each item belongs: target tab + rank within that tab (among items you actually own).
		final Set<Integer> owned = ownedItems();
		final Map<Integer, Integer> targetTab = new HashMap<>();
		final Map<Integer, Integer> targetRank = new HashMap<>();
		for (TabLayout tl : template.getTabs())
		{
			int rank = 0;
			final Set<Integer> seen = new HashSet<>();
			for (Integer v : tl.getLayout())
			{
				if (v == null || v <= 0 || v == BankTemplate.FILLER)
				{
					continue;
				}
				final int canon = functionalId(v);
				if (!owned.contains(canon) || !seen.add(canon))
				{
					continue;
				}
				if (!targetTab.containsKey(canon))
				{
					targetTab.put(canon, tl.getTab());
					targetRank.put(canon, rank);
				}
				rank++;
			}
		}

		// Which native tab each visible item is currently in.
		final int[] itemTab = currentItemTabs(current.size(), currentTab);

		final BankTemplatesConfig.ReorgDisplay mode = config.reorgDisplay();
		final boolean labels = mode == BankTemplatesConfig.ReorgDisplay.LABELS || mode == BankTemplatesConfig.ReorgDisplay.BOTH;
		final boolean steps = mode == BankTemplatesConfig.ReorgDisplay.STEP_BY_STEP || mode == BankTemplatesConfig.ReorgDisplay.BOTH;

		final Shape oldClip = graphics.getClip();

		if (labels)
		{
			graphics.setClip(itemContainer.getBounds());
			drawLabels(graphics, widgets, current, itemTab, targetTab, targetRank, template.getColumns());
			graphics.setClip(oldClip);
		}

		if (steps)
		{
			drawSteps(graphics, itemContainer, template, currentTab, widgets, current, itemTab, targetTab);
		}

		return null;
	}

	// Draw a destination tag on each out-of-place item: tab number if in the wrong tab, else row-col.
	private void drawLabels(Graphics2D g, List<Widget> widgets, List<Integer> current, int[] itemTab,
		Map<Integer, Integer> targetTab, Map<Integer, Integer> targetRank, int columns)
	{
		// Current rank of each item within the tab it's presently in (for the "already placed?" check).
		final Map<Integer, Integer> cursor = new HashMap<>();
		for (int k = 0; k < current.size(); k++)
		{
			final Integer tt = targetTab.get(current.get(k));
			if (tt == null || itemTab[k] != tt)
			{
				continue;
			}
			final int rank = cursor.getOrDefault(tt, 0);
			final Rectangle b = widgets.get(k).getBounds();
			if (b.width > 0 && targetRank.get(current.get(k)) != rank)
			{
				final int idx = targetRank.get(current.get(k));
				drawTag(g, b, (idx / columns + 1) + "-" + (idx % columns + 1), config.reorgHighlightColor());
			}
			cursor.put(tt, rank + 1);
		}
		// Wrong-tab items get the target tab number.
		for (int k = 0; k < current.size(); k++)
		{
			final Integer tt = targetTab.get(current.get(k));
			if (tt == null || itemTab[k] == tt)
			{
				continue;
			}
			final Rectangle b = widgets.get(k).getBounds();
			if (b.width > 0)
			{
				drawTag(g, b, tt == BankTemplate.MAIN_TAB ? "∞" : String.valueOf(tt), config.reorgHighlightColor());
			}
		}
	}

	private void drawSteps(Graphics2D g, Widget itemContainer, BankTemplate template, int currentTab,
		List<Widget> widgets, List<Integer> current, int[] itemTab, Map<Integer, Integer> targetTab)
	{
		// Tab phase: the first item sitting in the wrong tab. Highlight it and its destination tab button.
		for (int k = 0; k < current.size(); k++)
		{
			final Integer tt = targetTab.get(current.get(k));
			if (tt == null || itemTab[k] == tt)
			{
				continue;
			}
			final Widget from = widgets.get(k);
			outline(g, from.getBounds(), SOURCE_COLOR);
			final Map<Integer, Widget> buttons = tabButtons();
			final Widget tabBtn = buttons.get(tt);
			final String tabName = tt == BankTemplate.MAIN_TAB ? "the main tab" : "tab " + tt;
			if (tabBtn != null)
			{
				pulseRect(g, tabBtn.getBounds(), config.reorgHighlightColor());
			}
			final String name = client.getItemDefinition(from.getItemId()).getName();
			drawText(g, itemContainer, "Drag " + name + " to " + tabName, Color.WHITE, null, false);
			return;
		}

		// Position phase: order this view's items. (Existing swap/insert guide.)
		drawPositionStep(g, itemContainer, template, currentTab, widgets, current);
	}

	private void drawPositionStep(Graphics2D graphics, Widget itemContainer, BankTemplate template, int currentTab,
		List<Widget> widgets, List<Integer> current)
	{
		final int[] desired = currentTab == BankTemplate.MAIN_TAB ? template.fullLayout() : template.tabLayout(currentTab);
		if (desired == null || desired.length == 0)
		{
			return;
		}

		// Desired order = template items you have in this view (canonical, template order), then leftovers.
		final Set<Integer> have = new HashSet<>(current);
		final Set<Integer> added = new HashSet<>();
		final List<Integer> target = new ArrayList<>();
		for (int id : desired)
		{
			if (id <= 0 || id == BankTemplate.FILLER)
			{
				continue;
			}
			final int canon = functionalId(id);
			if (have.contains(canon) && added.add(canon))
			{
				target.add(canon);
			}
		}
		for (int canon : current)
		{
			if (added.add(canon))
			{
				target.add(canon);
			}
		}

		int i = 0;
		while (i < current.size() && i < target.size() && current.get(i).equals(target.get(i)))
		{
			i++;
		}

		final Shape oldClip = graphics.getClip();
		graphics.setClip(itemContainer.getBounds());

		if (i >= target.size() || i >= current.size())
		{
			drawText(graphics, itemContainer, "Bank matches the template", DONE_COLOR, null, false);
			graphics.setClip(oldClip);
			planTab = -1;
			return;
		}

		final int wantCanon = target.get(i);
		int j = i;
		while (j < current.size() && !current.get(j).equals(wantCanon))
		{
			j++;
		}
		if (j >= current.size())
		{
			graphics.setClip(oldClip);
			return;
		}

		// Pick ONE mode for the whole tab so the user only toggles swap/insert once: whichever sorts this
		// tab in fewer drags. The same drag (move the wanted item into slot i) works in either mode.
		if (planTab != currentTab || !template.getName().equals(planTemplate))
		{
			final int[] perm = permutation(current, target);
			planSwap = (perm.length - cycleCount(perm)) <= simulateInsertCost(perm);
			planTab = currentTab;
			planTemplate = template.getName();
		}

		final boolean swap = planSwap;
		final int neededMode = swap ? 0 : 1;          // BANK_INSERTMODE: 0 = swap, 1 = insert
		final boolean modeOk = client.getVarbitValue(VarbitID.BANK_INSERTMODE) == neededMode;

		final Widget from = widgets.get(j);
		final Widget to = widgets.get(i);
		final Color highlight = config.reorgHighlightColor();

		drawGhost(graphics, from.getItemId(), to.getBounds());
		outline(graphics, to.getBounds(), highlight);
		outline(graphics, from.getBounds(), SOURCE_COLOR);
		drawArrow(graphics, from.getBounds(), to.getBounds(), highlight);

		graphics.setClip(oldClip);

		final String name = client.getItemDefinition(from.getItemId()).getName();
		drawText(graphics, itemContainer,
			(swap ? "Swap " : "Insert ") + name + " into the highlighted slot",
			Color.WHITE,
			modeOk ? null : "Switch the bank to " + (swap ? "Swap" : "Insert") + " mode (highlighted)",
			!modeOk);

		if (!modeOk)
		{
			highlightToggle(graphics);
		}
	}

	/**
	 * A stable id for "this item, ignoring functionally-identical variants". Canonicalises placeholders
	 * to the real item, then maps the result onto its variation base ({@link ItemVariationMapping}) so
	 * item kits and alternate versions (charged/uncharged, degraded, recoloured, …) collapse to one id.
	 * This matches how the virtual renderer pairs template items to bank items, so the reorganise helper
	 * no longer flags a slot as "wrong" when you hold a functionally-identical version of the item.
	 */
	private int functionalId(int id)
	{
		return ItemVariationMapping.map(itemManager.canonicalize(id));
	}

	// Canonical ids of everything in the bank (what the player owns).
	private Set<Integer> ownedItems()
	{
		final Set<Integer> owned = new HashSet<>();
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			for (Item it : bank.getItems())
			{
				if (it.getId() > 0)
				{
					owned.add(functionalId(it.getId()));
				}
			}
		}
		return owned;
	}

	// The native tab each visible item currently sits in: the viewed tab, or - in the all-items view -
	// derived from the per-tab counts (items run tab 1..9 then the main view).
	private int[] currentItemTabs(int n, int currentTab)
	{
		final int[] tabs = new int[n];
		if (currentTab != BankTemplate.MAIN_TAB)
		{
			Arrays.fill(tabs, currentTab);
			return tabs;
		}
		int idx = 0;
		for (int t = 1; t <= 9; t++)
		{
			final int count = client.getVarbitValue(TAB_COUNT_VARBITS[t - 1]);
			for (int k = 0; k < count && idx < n; k++, idx++)
			{
				tabs[idx] = t;
			}
		}
		while (idx < n)
		{
			tabs[idx++] = BankTemplate.MAIN_TAB;
		}
		return tabs;
	}

	// The native bank tab buttons keyed by tab number (1..N). Each tab button is an item-bearing child
	// of Bankmain.TABS; they appear in tab order.
	private Map<Integer, Widget> tabButtons()
	{
		final Map<Integer, Widget> map = new HashMap<>();
		final Widget tabs = client.getWidget(InterfaceID.Bankmain.TABS);
		if (tabs == null)
		{
			return map;
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
				if (c == null)
				{
					continue;
				}
				// The all-items (main) tab is the infinity button, identified by its sprite.
				if (c.getSpriteId() == SpriteID.BanktabIcons.ALL_ITEMS)
				{
					map.put(BankTemplate.MAIN_TAB, c);
				}
				else if (c.getItemId() > 0 && tab <= 9)
				{
					map.put(tab++, c);
				}
			}
		}
		return map;
	}

	private static int[] permutation(List<Integer> current, List<Integer> target)
	{
		final Map<Integer, Integer> idx = new HashMap<>();
		for (int k = 0; k < target.size(); k++)
		{
			idx.put(target.get(k), k);
		}
		final int[] perm = new int[current.size()];
		for (int k = 0; k < current.size(); k++)
		{
			final Integer t = idx.get(current.get(k));
			perm[k] = t == null ? k : t;
		}
		return perm;
	}

	// Minimum swaps to sort a permutation = n - (number of cycles).
	private static int cycleCount(int[] perm)
	{
		final boolean[] seen = new boolean[perm.length];
		int cycles = 0;
		for (int s = 0; s < perm.length; s++)
		{
			if (seen[s])
			{
				continue;
			}
			cycles++;
			for (int x = s; !seen[x]; x = perm[x])
			{
				seen[x] = true;
			}
		}
		return cycles;
	}

	// Moves the insert guide makes (greedy: bring each slot's correct item to it).
	private static int simulateInsertCost(int[] perm)
	{
		final List<Integer> a = new ArrayList<>();
		for (int v : perm)
		{
			a.add(v);
		}
		int moves = 0;
		for (int i = 0; i < a.size(); i++)
		{
			if (a.get(i) == i)
			{
				continue;
			}
			int j = i + 1;
			while (j < a.size() && a.get(j) != i)
			{
				j++;
			}
			if (j >= a.size())
			{
				break;
			}
			a.add(i, a.remove(j));
			moves++;
		}
		return moves;
	}

	private void drawTag(Graphics2D g, Rectangle b, String text, Color color)
	{
		g.setFont(LABEL_FONT);
		final FontMetrics fm = g.getFontMetrics();
		final int w = fm.stringWidth(text) + 4;
		final int h = fm.getHeight() - 2;
		g.setColor(new Color(0, 0, 0, 200));
		g.fillRect(b.x + 1, b.y + 1, w, h);
		g.setColor(color);
		g.drawString(text, b.x + 3, b.y + 1 + fm.getAscent() - 1);
	}

	// Pulse the bank's Swap/Insert toggle button.
	private void highlightToggle(Graphics2D g)
	{
		final Widget toggle = client.getWidget(InterfaceID.Bankmain.SWAP_INSERT);
		if (toggle != null && !toggle.isHidden())
		{
			pulseRect(g, toggle.getBounds(), SOURCE_COLOR);
		}
	}

	private void pulseRect(Graphics2D g, Rectangle b, Color c)
	{
		if (b.width <= 0 || b.height <= 0)
		{
			return;
		}
		final double pulse = 0.45 + 0.35 * Math.sin(System.currentTimeMillis() / 200.0);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (pulse * 120)));
		g.fillRect(b.x, b.y, b.width, b.height);
		g.setColor(c);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2f));
		g.drawRect(b.x - 1, b.y - 1, b.width + 2, b.height + 2);
		g.setStroke(old);
	}

	private void outline(Graphics2D g, Rectangle b, Color c)
	{
		if (b.width <= 0 || b.height <= 0)
		{
			return;
		}
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 70));
		g.fillRect(b.x, b.y, b.width, b.height);
		g.setColor(c);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2f));
		g.drawRect(b.x, b.y, b.width, b.height);
		g.setStroke(old);
	}

	private void drawGhost(Graphics2D g, int itemId, Rectangle b)
	{
		final BufferedImage img = itemManager.getImage(itemId);
		if (img == null)
		{
			return;
		}
		final Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
		g.drawImage(img, b.x + (b.width - img.getWidth()) / 2, b.y + (b.height - img.getHeight()) / 2, null);
		g.setComposite(old);
	}

	private void drawArrow(Graphics2D g, Rectangle from, Rectangle to, Color c)
	{
		final int x1 = from.x + from.width / 2;
		final int y1 = from.y + from.height / 2;
		final int x2 = to.x + to.width / 2;
		final int y2 = to.y + to.height / 2;

		g.setColor(c);
		final Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2f));
		g.drawLine(x1, y1, x2, y2);

		final double angle = Math.atan2(y2 - y1, x2 - x1);
		final int len = 8;
		final int ax = (int) (x2 - len * Math.cos(angle - Math.PI / 6));
		final int ay = (int) (y2 - len * Math.sin(angle - Math.PI / 6));
		final int bx = (int) (x2 - len * Math.cos(angle + Math.PI / 6));
		final int by = (int) (y2 - len * Math.sin(angle + Math.PI / 6));
		g.fillPolygon(new int[]{x2, ax, bx}, new int[]{y2, ay, by}, 3);
		g.setStroke(old);
	}

	private void drawText(Graphics2D g, Widget container, String line, Color color, String warn, boolean warnActive)
	{
		// Anchor to the bank title bar (above the items) so the instruction doesn't cover the item grid.
		final Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		final Rectangle anchor = title != null && !title.isHidden() && title.getBounds().width > 0
			? title.getBounds() : container.getBounds();

		final int pad = 5;
		g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
		final int w1 = g.getFontMetrics().stringWidth(line);
		final int lineH = g.getFontMetrics().getHeight();
		final int boxW = Math.max(w1, warn != null ? g.getFontMetrics().stringWidth(warn) : 0) + pad * 2;
		final int boxH = (warn != null ? lineH * 2 : lineH) + pad * 2;

		// Centre the box over the title bar (which sits above the tabs and items, so nothing important
		// is covered).
		final int x = anchor.x + Math.max(2, (anchor.width - boxW) / 2);
		final int boxY = anchor.y;

		g.setColor(TEXT_BG);
		g.fillRect(x, boxY, boxW, boxH);

		int textY = boxY + pad + g.getFontMetrics().getAscent();
		g.setColor(color);
		g.drawString(line, x + pad, textY);
		if (warn != null)
		{
			textY += lineH;
			g.setColor(warnActive ? SOURCE_COLOR : Color.LIGHT_GRAY);
			g.drawString(warn, x + pad, textY);
		}
	}
}
