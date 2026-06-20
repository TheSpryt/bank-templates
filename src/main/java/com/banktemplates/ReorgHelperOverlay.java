package com.banktemplates;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Guided, one-step-at-a-time helper for reorganising your <b>real</b> bank to match the active
 * template. It highlights the next item that's out of order and where to drag it (arrow + ghost +
 * text), and tells you whether to use swap or insert mode. It never moves anything - you do the drag.
 * <p>
 * Only the item <i>order</i> can be matched: the real bank packs items with no gaps, so the template's
 * empty/filler columns aren't reproduced here. Works on whichever native tab you're viewing, and
 * steps aside for Bank Tags tag tabs/searches.
 */
public class ReorgHelperOverlay extends Overlay
{
	private static final Color SOURCE_COLOR = new Color(255, 165, 0);   // orange: item to move
	private static final Color DONE_COLOR = new Color(110, 200, 110);
	private static final Color TEXT_BG = new Color(0, 0, 0, 180);

	private final Client client;
	private final BankTemplatesConfig config;
	private final TemplateManager templateManager;
	private final ItemManager itemManager;

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

		final int currentTab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);
		// The "view all items" tab (0) shows the whole bank, so match against the whole template.
		final int[] desired = currentTab == BankTemplate.MAIN_TAB
			? template.fullLayout()
			: template.tabLayout(currentTab);
		if (desired == null || desired.length == 0)
		{
			return null;
		}

		// Current tab's items, in slot order, with their widgets (for bounds).
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
					current.add(itemManager.canonicalize(c.getItemId()));
				}
			}
		}
		if (current.isEmpty())
		{
			return null;
		}

		// Desired order = template items you actually have in this tab (canonical), in template order.
		final Set<Integer> have = new HashSet<>(current);
		final Set<Integer> added = new HashSet<>();
		final List<Integer> target = new ArrayList<>();
		for (int id : desired)
		{
			if (id <= 0 || id == BankTemplate.FILLER)
			{
				continue;
			}
			final int canon = itemManager.canonicalize(id);
			if (have.contains(canon) && added.add(canon))
			{
				target.add(canon);
			}
		}
		// Items present but not in the template trail along in their current order.
		for (int canon : current)
		{
			if (added.add(canon))
			{
				target.add(canon);
			}
		}

		// First slot that doesn't match the target order.
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
			return null;
		}

		// The item that belongs at i is currently at j (>= i).
		final int wantCanon = target.get(i);
		int j = i;
		while (j < current.size() && !current.get(j).equals(wantCanon))
		{
			j++;
		}
		if (j >= current.size())
		{
			graphics.setClip(oldClip);
			return null;
		}

		// A swap is better only when the two slots are each other's targets; otherwise insert.
		final boolean swap = i < target.size() && current.get(i).equals(target.get(j));
		final int neededMode = swap ? 0 : 1;          // BANK_INSERTMODE: 0 = swap, 1 = insert
		final int currentMode = client.getVarbitValue(VarbitID.BANK_INSERTMODE);
		final boolean modeOk = currentMode == neededMode;

		final Widget from = widgets.get(j);
		final Widget to = widgets.get(i);
		final Rectangle fromB = from.getBounds();
		final Rectangle toB = to.getBounds();
		final Color target_ = config.reorgHighlightColor();

		// Ghost of the item in its destination, target + source outlines, and an arrow between them.
		drawGhost(graphics, from.getItemId(), toB);
		outline(graphics, toB, target_);
		outline(graphics, fromB, SOURCE_COLOR);
		drawArrow(graphics, fromB, toB, target_);

		// The toggle button lives outside the item area, so draw the rest unclipped.
		graphics.setClip(oldClip);

		final String name = client.getItemDefinition(from.getItemId()).getName();
		final String action = swap ? "Swap" : "Insert";
		drawText(graphics, itemContainer,
			action + " " + name + " into the highlighted slot",
			Color.WHITE,
			modeOk ? null : "Switch the bank to " + (swap ? "Swap" : "Insert") + " mode (highlighted)",
			!modeOk);

		if (!modeOk)
		{
			highlightToggle(graphics);
		}
		return null;
	}

	// Pulse the bank's Swap/Insert toggle button so the user can see what to click.
	private void highlightToggle(Graphics2D g)
	{
		final Widget toggle = client.getWidget(InterfaceID.Bankmain.SWAP_INSERT);
		if (toggle == null || toggle.isHidden())
		{
			return;
		}
		final Rectangle b = toggle.getBounds();
		if (b.width <= 0 || b.height <= 0)
		{
			return;
		}
		final double pulse = 0.45 + 0.35 * Math.sin(System.currentTimeMillis() / 200.0);
		g.setColor(new Color(SOURCE_COLOR.getRed(), SOURCE_COLOR.getGreen(), SOURCE_COLOR.getBlue(), (int) (pulse * 120)));
		g.fillRect(b.x, b.y, b.width, b.height);
		g.setColor(SOURCE_COLOR);
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

		// Filled arrowhead at the target.
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
		final Rectangle cb = container.getBounds();
		final int pad = 4;
		final int x = cb.x + 6;
		int y = cb.y + 14;

		g.setFont(g.getFont().deriveFont(12f));
		final int w1 = g.getFontMetrics().stringWidth(line);
		final int boxW = Math.max(w1, warn != null ? g.getFontMetrics().stringWidth(warn) : 0) + pad * 2;
		final int boxH = (warn != null ? 30 : 16) + pad;

		g.setColor(TEXT_BG);
		g.fillRect(x - pad, y - 12, boxW, boxH);

		g.setColor(color);
		g.drawString(line, x, y);
		if (warn != null)
		{
			y += 14;
			g.setColor(warnActive ? SOURCE_COLOR : Color.LIGHT_GRAY);
			g.drawString(warn, x, y);
		}
	}
}
