package com.banktemplates;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
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
	private static final Font PLUS_FONT = new Font("Arial", Font.PLAIN, 22);

	private static final int DRAG_THRESHOLD = 5;

	private final Client client;
	private final ItemIndex itemIndex;
	private final LayoutEditor layoutEditor;
	private final ClientThread clientThread;
	private final BankTemplatesConfig config;
	private final BankLayoutRenderer renderer;

	// Captured each frame on the client thread, read by the (AWT-thread) mouse handlers.
	private volatile Rectangle[] slotRects = new Rectangle[0];
	private volatile int[] slotItems = new int[0];
	private volatile Rectangle addRect;
	private volatile Rectangle containerRect;
	private volatile int currentTab;
	// Native tab buttons (tab number -> bounds), so a slot can be dragged onto a tab to move it there.
	private volatile Map<Integer, Rectangle> tabRects = Collections.emptyMap();
	// The bank's "+" (add tab) button: dropping a slot here creates a new template tab.
	private volatile Rectangle newTabRect;

	// Drag state, set by the mouse handlers. The native bank drag draws the item on the cursor; we only
	// track this to know it's a drag (not a click) and for edge auto-scroll.
	private volatile int dragFrom = -1;
	private volatile java.awt.Point dragPoint;
	// A press is "armed" until it either moves past the threshold (-> drag/rearrange) or releases in
	// place (-> click passes through to the bank, e.g. withdraw).
	private volatile int pressSlot = -1;
	private volatile java.awt.Point pressPoint;

	@Inject
	LayoutEditorOverlay(Client client, ItemIndex itemIndex, LayoutEditor layoutEditor, ClientThread clientThread,
		BankTemplatesConfig config, BankLayoutRenderer renderer)
	{
		this.client = client;
		this.itemIndex = itemIndex;
		this.layoutEditor = layoutEditor;
		this.clientThread = clientThread;
		this.config = config;
		this.renderer = renderer;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Editable whenever a user template is applied (always-on), or during an explicit edit session.
		final BankTemplate template = layoutEditor.liveTemplate();
		final boolean active = template != null
			&& (layoutEditor.isEditing() || (config.applyLayout() && !config.showReorgHelper()))
			&& !BankLayoutRenderer.isBankFiltered(client);
		if (!active)
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

		// Slots are located via the renderer's position->widget map (a widget's container index no longer
		// equals its rendered slot, since items are placed by identity for Inventory Setups compatibility).
		final Rectangle[] rects = new Rectangle[len];
		final int[] items = new int[len];
		for (int i = 0; i < len; i++)
		{
			final Widget c = renderer.slotWidgetAt(i);
			rects[i] = c != null ? c.getBounds() : null;
			items[i] = layout[i];
		}

		// The "+" lives in the slot right after the layout. It can be scrolled out of view, where its
		// widget bounds sit over the bank's bottom buttons - only show/hit-test it when it's actually
		// inside the visible bank area.
		final Rectangle container = itemContainer.getBounds();
		Widget addChild = renderer.slotWidgetAt(len);
		// If another plugin (e.g. Inventory Setups) rearranged the bank so the slot after the layout now holds
		// a real item, move the "+" to the first genuinely-empty slot so it never covers - and blocks the
		// withdrawal of - an item.
		if (addChild == null || addChild.getItemId() > 0)
		{
			final Widget empty = renderer.firstEmptySlot();
			if (empty != null)
			{
				addChild = empty;
			}
		}
		Rectangle add = addChild != null ? addChild.getBounds() : null;
		if (add != null && (container == null || !container.contains(add)))
		{
			add = null;
		}

		this.slotRects = rects;
		this.slotItems = items;
		this.addRect = add;
		this.containerRect = container;
		this.currentTab = tab;
		this.tabRects = captureTabRects();

		if (add != null && add.width > 0)
		{
			drawAddButton(graphics, add);
		}

		// The "Editing ..." banner is shown in the bank's real title bar (set by BankLayoutRenderer).

		// The native bank drag draws the item on the cursor, so we don't render our own ghost. We only read
		// the drag point here to auto-scroll the bank when the cursor nears the top/bottom edge.
		final int from = dragFrom;
		final java.awt.Point p = dragPoint;

		// While dragging near the top/bottom edge, auto-scroll the bank (like the native bank drag) so an
		// item can be dragged across many rows without holding click and using the scroll wheel together.
		if (from >= 0 && p != null)
		{
			final Rectangle cb = itemContainer.getBounds();
			if (cb != null && cb.height > 0)
			{
				final int edge = 24;
				int delta = 0;
				if (p.y < cb.y + edge)
				{
					delta = -12;
				}
				else if (p.y > cb.y + cb.height - edge)
				{
					delta = 12;
				}
				if (delta != 0)
				{
					final int max = Math.max(0, itemContainer.getScrollHeight() - itemContainer.getHeight());
					final int newY = Math.max(0, Math.min(max, itemContainer.getScrollY() + delta));
					if (newY != itemContainer.getScrollY())
					{
						itemContainer.setScrollY(newY);
						itemContainer.revalidateScroll();
						client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS, newY);
					}
				}
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
		tabRects = Collections.emptyMap();
		newTabRect = null;
		dragFrom = -1;
		dragPoint = null;
		pressSlot = -1;
		pressPoint = null;
	}

	// ---- Mouse handling (AWT thread; hit-tests the cached rectangles only) -------------------

	@Override
	public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
	{
		if (layoutEditor.liveTemplate() == null || !javax.swing.SwingUtilities.isLeftMouseButton(e))
		{
			return e;
		}
		final java.awt.Point p = e.getPoint();

		// The "+" add button has no withdraw conflict, so act on press.
		final Rectangle add = addRect;
		if (add != null && add.contains(p))
		{
			openSearch();
			e.consume();
			return e;
		}

		// Arm a potential drag but DON'T consume yet, so a plain click still reaches the bank (withdraw).
		final int slot = slotAt(p);
		if (slot >= 0)
		{
			pressSlot = slot;
			pressPoint = p;
			dragFrom = -1;
		}
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseDragged(java.awt.event.MouseEvent e)
	{
		final java.awt.Point press = pressPoint;
		if (dragFrom < 0 && pressSlot >= 0 && press != null && press.distance(e.getPoint()) > DRAG_THRESHOLD)
		{
			// Movement past the threshold means a drag (rearrange the template), not a click (withdraw).
			dragFrom = pressSlot;
		}
		if (dragFrom >= 0)
		{
			// Track the cursor for edge auto-scroll, but DON'T consume: the native bank drag runs underneath
			// (item on the cursor) and must complete so the client clears its own drag state.
			dragPoint = e.getPoint();
		}
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseReleased(java.awt.event.MouseEvent e)
	{
		final int from = dragFrom;
		pressSlot = -1;
		pressPoint = null;
		dragFrom = -1;
		dragPoint = null;
		if (from < 0)
		{
			// Never crossed the threshold: it was a click, let the bank handle it (e.g. withdraw).
			return e;
		}
		final java.awt.Point p = e.getPoint();
		final int tab = currentTab;

		// We deliberately DON'T consume the release. The native bank drag completes underneath us, which is
		// what clears the client's drag state (so the next click isn't swallowed into a withdraw of the item
		// we just dragged). Its real reorder is already blocked by the item's drag-complete listener; here we
		// just rewrite the template to match where the item was dropped.

		// Dropped onto the "+" button -> create a new tab from this item (like the real bank).
		final Rectangle newTab = newTabRect;
		if (newTab != null && newTab.contains(p))
		{
			clientThread.invoke(() -> layoutEditor.moveToNewTab(tab, from));
			return e;
		}

		// Dropped onto a different tab's button -> move the item into that tab.
		for (Map.Entry<Integer, Rectangle> en : tabRects.entrySet())
		{
			final Rectangle r = en.getValue();
			if (r != null && r.contains(p) && en.getKey() != tab)
			{
				final int destTab = en.getKey();
				clientThread.invoke(() -> layoutEditor.moveToTab(tab, from, destTab));
				return e;
			}
		}

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
		return e;
	}

	// Native bank tab buttons by tab number (1-9 = the item-icon children of Bankmain.TABS; 0 = the
	// all-items button, identified by its sprite).
	private Map<Integer, Rectangle> captureTabRects()
	{
		final Map<Integer, Rectangle> map = new HashMap<>();
		this.newTabRect = null;
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
				if (c.getSpriteId() == SpriteID.BanktabIcons.ALL_ITEMS)
				{
					map.put(BankTemplate.MAIN_TAB, c.getBounds());
				}
				else if (c.getSpriteId() == SpriteID.BanktabIcons.ADD)
				{
					this.newTabRect = c.getBounds();
				}
				else if (c.getItemId() > 0 && tab <= 9)
				{
					map.put(tab++, c.getBounds());
				}
			}
		}
		return map;
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
			ItemSearch.open(client.getCanvas(), itemIndex, id ->
				clientThread.invoke(() -> layoutEditor.addItemOrReport(tab, id, msg ->
					javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
						null, msg, "Already in layout", javax.swing.JOptionPane.INFORMATION_MESSAGE))))));
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
