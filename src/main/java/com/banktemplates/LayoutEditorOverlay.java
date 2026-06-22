package com.banktemplates;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The in-bank face of the layout editor. While a template is being edited it draws a green "+" button
 * after the last slot (search and add an item without owning it) and lets you drag slots around to
 * rearrange them - all client-side, the real bank is never touched. Slot rectangles are captured during
 * {@link #render} (client thread) and the mouse handlers hit-test those cached rectangles, so input
 * handling never reads widgets off-thread.
 */
public class LayoutEditorOverlay extends Overlay implements MouseListener
{
	private static final Color ADD_COLOR = new Color(110, 200, 110);
	private static final Color DRAG_COLOR = new Color(255, 165, 0);
	private static final Color BANNER_BG = new Color(0, 0, 0, 180);
	private static final Font PLUS_FONT = new Font("Arial", Font.BOLD, 22);
	private static final Font BANNER_FONT = new Font("Arial", Font.BOLD, 12);

	private final Client client;
	private final ItemManager itemManager;
	private final LayoutEditor layoutEditor;
	private final ClientThread clientThread;

	// Captured each frame on the client thread, read by the (AWT-thread) mouse handlers.
	private volatile Rectangle[] slotRects = new Rectangle[0];
	private volatile int[] slotItems = new int[0];
	private volatile Rectangle addRect;
	private volatile Rectangle containerRect;
	private volatile int currentTab;

	// Drag state, set by the mouse handlers, read while rendering the drag ghost.
	private volatile int dragFrom = -1;
	private volatile int dragItem;
	private volatile java.awt.Point dragPoint;

	@Inject
	LayoutEditorOverlay(Client client, ItemManager itemManager, LayoutEditor layoutEditor, ClientThread clientThread)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.layoutEditor = layoutEditor;
		this.clientThread = clientThread;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final BankTemplate template = layoutEditor.getTarget();
		if (!layoutEditor.isEditing() || template == null || BankLayoutRenderer.isBankFiltered(client))
		{
			clearCache();
			return null;
		}

		final Widget itemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (itemContainer == null || itemContainer.isHidden())
		{
			clearCache();
			return null;
		}

		final int tab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);
		final int[] layout = template.tabLayout(tab);
		final int len = layout == null ? 0 : layout.length;

		final Rectangle[] rects = new Rectangle[len];
		final int[] items = new int[len];
		for (int i = 0; i < len; i++)
		{
			final Widget c = itemContainer.getChild(i);
			rects[i] = c != null ? c.getBounds() : null;
			items[i] = layout[i];
		}

		// The "+" lives in the slot right after the layout.
		final Widget addChild = itemContainer.getChild(len);
		final Rectangle add = addChild != null ? addChild.getBounds() : null;

		this.slotRects = rects;
		this.slotItems = items;
		this.addRect = add;
		this.containerRect = itemContainer.getBounds();
		this.currentTab = tab;

		if (add != null && add.width > 0)
		{
			drawAddButton(graphics, add);
		}

		drawBanner(graphics, template, itemContainer);

		// Drag ghost.
		final int from = dragFrom;
		final java.awt.Point p = dragPoint;
		if (from >= 0 && from < rects.length && rects[from] != null)
		{
			outline(graphics, rects[from], DRAG_COLOR);
		}
		if (from >= 0 && p != null && dragItem > 0)
		{
			final BufferedImage img = itemManager.getImage(dragItem);
			if (img != null)
			{
				final Composite old = graphics.getComposite();
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
				graphics.drawImage(img, p.x - img.getWidth() / 2, p.y - img.getHeight() / 2, null);
				graphics.setComposite(old);
			}
		}

		return null;
	}

	private void drawAddButton(Graphics2D g, Rectangle b)
	{
		g.setColor(new Color(ADD_COLOR.getRed(), ADD_COLOR.getGreen(), ADD_COLOR.getBlue(), 60));
		g.fillRect(b.x, b.y, b.width, b.height);
		final Stroke old = g.getStroke();
		g.setColor(ADD_COLOR);
		g.setStroke(new BasicStroke(2f));
		g.drawRect(b.x, b.y, b.width, b.height);
		g.setStroke(old);

		g.setFont(PLUS_FONT);
		final int sw = g.getFontMetrics().stringWidth("+");
		final int ascent = g.getFontMetrics().getAscent();
		g.setColor(Color.WHITE);
		g.drawString("+", b.x + (b.width - sw) / 2, b.y + (b.height + ascent) / 2 - 2);
	}

	private void drawBanner(Graphics2D g, BankTemplate template, Widget container)
	{
		final Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		final Rectangle anchor = title != null && !title.isHidden() && title.getBounds().width > 0
			? title.getBounds() : container.getBounds();

		final String text = "Editing \"" + template.getName() + "\" - drag to arrange, click + to add";
		g.setFont(BANNER_FONT);
		final int pad = 5;
		final int w = g.getFontMetrics().stringWidth(text) + pad * 2;
		final int h = g.getFontMetrics().getHeight() + pad * 2;
		final int x = anchor.x + Math.max(2, (anchor.width - w) / 2);
		final int y = anchor.y;

		g.setColor(BANNER_BG);
		g.fillRect(x, y, w, h);
		g.setColor(ADD_COLOR);
		g.drawString(text, x + pad, y + pad + g.getFontMetrics().getAscent());
	}

	private void outline(Graphics2D g, Rectangle b, Color c)
	{
		if (b == null || b.width <= 0)
		{
			return;
		}
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 70));
		g.fillRect(b.x, b.y, b.width, b.height);
		final Stroke old = g.getStroke();
		g.setColor(c);
		g.setStroke(new BasicStroke(2f));
		g.drawRect(b.x, b.y, b.width, b.height);
		g.setStroke(old);
	}

	private void clearCache()
	{
		slotRects = new Rectangle[0];
		slotItems = new int[0];
		addRect = null;
		containerRect = null;
		dragFrom = -1;
		dragPoint = null;
	}

	// ---- Mouse handling (AWT thread; hit-tests the cached rectangles only) -------------------

	@Override
	public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
	{
		if (!layoutEditor.isEditing() || !javax.swing.SwingUtilities.isLeftMouseButton(e))
		{
			return e;
		}
		final java.awt.Point p = e.getPoint();

		final Rectangle add = addRect;
		if (add != null && add.contains(p))
		{
			openSearch();
			e.consume();
			return e;
		}

		final int slot = slotAt(p);
		if (slot >= 0)
		{
			dragFrom = slot;
			dragItem = slot < slotItems.length ? slotItems[slot] : -1;
			dragPoint = p;
			e.consume();
		}
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseDragged(java.awt.event.MouseEvent e)
	{
		if (dragFrom >= 0)
		{
			dragPoint = e.getPoint();
			e.consume();
		}
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseReleased(java.awt.event.MouseEvent e)
	{
		final int from = dragFrom;
		if (from < 0)
		{
			return e;
		}
		final java.awt.Point p = e.getPoint();
		dragFrom = -1;
		dragPoint = null;

		final int tab = currentTab;
		int target = slotAt(p);
		final Rectangle add = addRect;
		final Rectangle container = containerRect;
		if (target < 0)
		{
			if ((add != null && add.contains(p)) || (container != null && container.contains(p)))
			{
				target = slotRects.length; // dropped past the items -> move to the end
			}
		}

		if (target >= 0 && target != from)
		{
			final int dest = target;
			clientThread.invoke(() -> layoutEditor.moveSlot(tab, from, dest));
		}
		e.consume();
		return e;
	}

	private int slotAt(java.awt.Point p)
	{
		final Rectangle[] rects = slotRects;
		for (int i = 0; i < rects.length; i++)
		{
			if (rects[i] != null && rects[i].width > 0 && rects[i].contains(p))
			{
				return i;
			}
		}
		return -1;
	}

	private void openSearch()
	{
		final int tab = currentTab;
		javax.swing.SwingUtilities.invokeLater(() ->
			ItemSearch.open(client.getCanvas(), itemManager, id ->
				clientThread.invoke(() -> layoutEditor.addItem(tab, id))));
	}

	@Override
	public java.awt.event.MouseEvent mouseClicked(java.awt.event.MouseEvent e)
	{
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseEntered(java.awt.event.MouseEvent e)
	{
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseExited(java.awt.event.MouseEvent e)
	{
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseMoved(java.awt.event.MouseEvent e)
	{
		return e;
	}
}
