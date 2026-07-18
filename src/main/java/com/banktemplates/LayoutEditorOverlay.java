package com.banktemplates;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.SoundEffectID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.plugins.bank.BankSearch;
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
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final BankSearch bankSearch;
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
	// Overlay-drawn buttons (tab number -> bounds) for template tabs the real bank doesn't have. Drawn after
	// the native + and hit-tested by the mouse handlers (click to view, drag an item onto to move it there).
	private volatile Map<Integer, Rectangle> extraTabRects = Collections.emptyMap();
	// Native tab-background geometry (the bg sprite is a touch larger than the item icon), used to size and
	// place the overlay extra tabs so they match the real tabs exactly.
	private volatile Rectangle lastTabBg;
	private volatile int tabBgPitch;
	// A press armed on an overlay extra-tab button; a release on the same one (no drag) views that tab.
	private volatile int pressExtraTab = -1;

	// Drag state, set by the mouse handlers. The native bank drag draws the item on the cursor; we only
	// track this to know it's a drag (not a click) and for edge auto-scroll.
	private volatile int dragFrom = -1;
	private volatile java.awt.Point dragPoint;
	// A press is "armed" until it either moves past the threshold (-> drag/rearrange) or releases in
	// place (-> click passes through to the bank, e.g. withdraw).
	private volatile int pressSlot = -1;
	private volatile java.awt.Point pressPoint;
	// Where within the grabbed item you pressed, captured once at press time. The native drag-ghost keeps this
	// grab point under the cursor; we reuse it so our on-top ghost lines up, instead of re-deriving it from the
	// source slot each frame (the slot moves as the bank scrolls/re-lays-out mid-drag, skewing the ghost).
	private volatile int grabDx = 16;
	private volatile int grabDy = 16;
	// Tab-reorder drag: armed when a numbered tab button is pressed, promoted to dragTabBtn past the
	// threshold. Dropping it on another numbered tab reorders the template's tabs.
	private volatile int pressTabBtn = -1;
	private volatile int dragTabBtn = -1;
	// Whether the client itself considers a widget drag in progress. Only true once the pressed widget's
	// drag dead time/zone are exceeded, so it honours the vanilla drag delay and Anti Drag's settings
	// (including "on shift only"). Without this gate a fast withdraw click that slides a few pixels would
	// count as a drag and rearrange the template (issue #35).
	private volatile boolean nativeDragging;

	@Inject
	LayoutEditorOverlay(Client client, ItemIndex itemIndex, ItemManager itemManager, SpriteManager spriteManager,
		BankSearch bankSearch, LayoutEditor layoutEditor, ClientThread clientThread, BankTemplatesConfig config,
		BankLayoutRenderer renderer)
	{
		this.client = client;
		this.itemIndex = itemIndex;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.bankSearch = bankSearch;
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
		// Sampled on the client thread each frame for the AWT mouse handlers (matching the cached rects).
		nativeDragging = client.isDraggingWidget();

		// Editable whenever a user template is applied (always-on), or during an explicit edit session.
		final BankTemplate template = layoutEditor.liveTemplate();
		// Only edit in the bank when the editable template is the one actually applied/rendered. Editing a
		// template from the side panel doesn't activate it, so its window edits must not touch the bank.
		final boolean active = template != null
			&& layoutEditor.liveOverBank()
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

		// The viewed tab is just the native current tab - clicking an extra tab sets it to that tab's number,
		// even past the real bank's tab count, and the renderer fills the (empty) native view from the template.
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
			// Use the widget's displayed item (the matched/owned variant), not the template's stored id, so the
			// drag-ghost over a virtual tab matches the actual item you're dragging instead of the stored one.
			items[i] = c != null && c.getItemId() > 0 ? c.getItemId() : layout[i];
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
		this.extraTabRects = drawExtraTabs(graphics, template, tab);
		// While dragging an item over an extra tab, the native drag-ghost is drawn under our overlay icon, so
		// draw the dragged item again on top so it reads as "over" the tab (like the real bank).
		final java.awt.Point dp = dragPoint;
		if (dragFrom >= 0 && dp != null && dragFrom < slotItems.length && slotItems[dragFrom] > 0)
		{
			final BufferedImage ghost = itemManager.getImage(slotItems[dragFrom]);
			if (ghost != null)
			{
				// Match the native drag-ghost: keep the grab point (where you pressed within the item) under
				// the cursor so the copies line up. grabDx/grabDy were captured at press time, so they stay
				// correct even when the source slot scrolls or re-lays-out during the drag.
				final int gx = dp.x - grabDx;
				final int gy = dp.y - grabDy;
				// Redraw it (semi-transparent, like the game's ghost) whenever the ghost rectangle overlaps an
				// overlay tab or the +, not just when the cursor point is inside - otherwise the ghost slips
				// behind the overlay tab icon as it approaches.
				final Rectangle ghostRect = new Rectangle(gx, gy, ghost.getWidth(), ghost.getHeight());
				if (overlapsOverlayTab(ghostRect))
				{
					final java.awt.Composite oldC = graphics.getComposite();
					graphics.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f));
					graphics.drawImage(ghost, gx, gy, null);
					graphics.setComposite(oldC);
				}
			}
		}

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

	/**
	 * Draws buttons for template tabs the real bank doesn't have (numbered higher than your real tab count),
	 * after the native + button, and returns their hit-test rectangles. Empty if there's no tab bar to
	 * anchor to or no extra tabs.
	 */
	private Map<Integer, Rectangle> drawExtraTabs(Graphics2D g, BankTemplate template, int currentTab)
	{
		// Size and place from the native tab-background geometry so the buttons match the real tabs.
		final Rectangle lastReal = lastTabBg;
		final int pitch = tabBgPitch;
		if (lastReal == null || lastReal.width <= 0 || pitch <= 0)
		{
			this.newTabRect = null;
			return Collections.emptyMap();
		}
		int realTabCount = 0;
		for (int t : tabRects.keySet())
		{
			if (t >= 1)
			{
				realTabCount = Math.max(realTabCount, t);
			}
		}

		final Map<Integer, Rectangle> out = new HashMap<>();
		int numbered = 0;
		int k = 1;
		for (int tabNum : template.definedTabs())
		{
			if (tabNum == BankTemplate.MAIN_TAB)
			{
				continue;
			}
			numbered++;
			if (tabNum <= realTabCount)
			{
				continue;
			}
			final Rectangle r = new Rectangle(lastReal.x + pitch * k, lastReal.y, lastReal.width, lastReal.height);
			drawExtraTab(g, r, firstTabItem(template, tabNum), tabNum == currentTab);
			out.put(tabNum, r);
			k++;
		}
		// Draw the + after the extras (the native one is hidden), unless the template already has 9 tabs.
		if (numbered < 9)
		{
			final Rectangle plus = new Rectangle(lastReal.x + pitch * k, lastReal.y, lastReal.width, lastReal.height);
			drawPlus(g, plus);
			this.newTabRect = plus;
		}
		else
		{
			this.newTabRect = null;
		}
		return out;
	}

	private void drawPlus(Graphics2D g, Rectangle b)
	{
		final BufferedImage bg = spriteManager.getSprite(SpriteID.Banktabs.EMPTY, 0);
		if (bg != null)
		{
			g.drawImage(bg, b.x, b.y, b.width, b.height, null);
		}
		final BufferedImage glyph = spriteManager.getSprite(SpriteID.BanktabIcons.ADD, 0);
		if (glyph != null)
		{
			g.drawImage(glyph, b.x + (b.width - glyph.getWidth()) / 2, b.y + (b.height - glyph.getHeight()) / 2, null);
		}
	}

	private void drawExtraTab(Graphics2D g, Rectangle b, int itemId, boolean selected)
	{
		// Use the real bank tab sprites so the buttons match the native tabs exactly (selected = the same
		// highlighted sprite the game uses for the active tab).
		final BufferedImage bg = spriteManager.getSprite(
			selected ? SpriteID.Banktabs.SELECTED : SpriteID.Banktabs.TAB, 0);
		if (bg != null)
		{
			g.drawImage(bg, b.x, b.y, b.width, b.height, null);
		}
		if (itemId > 0)
		{
			final BufferedImage img = itemManager.getImage(itemId);
			if (img != null)
			{
				g.drawImage(img, b.x + (b.width - img.getWidth()) / 2, b.y + (b.height - img.getHeight()) / 2, null);
			}
		}
	}

	// The tab's icon: its chosen custom icon, else its first real item, or -1 if it has neither.
	private static int firstTabItem(BankTemplate template, int tabNum)
	{
		final int custom = template.getTabIcon(tabNum);
		if (custom > 0)
		{
			return custom;
		}
		final int[] layout = template.tabLayout(tabNum);
		if (layout != null)
		{
			for (int v : layout)
			{
				if (v > 0 && v != BankTemplate.FILLER)
				{
					return v;
				}
			}
		}
		return -1;
	}

	// After moving an item to another tab, if the tab we were viewing collapsed (its last item left), follow
	// the item to its destination so we're not stranded on a tab that no longer exists. Runs on the client
	// thread (called from clientThread.invoke).
	private void navigateIfCollapsed(int sourceTab, int dest)
	{
		if (dest < 0)
		{
			return;
		}
		final BankTemplate tpl = layoutEditor.liveTemplate();
		if (tpl != null && sourceTab != BankTemplate.MAIN_TAB && !tpl.definedTabs().contains(sourceTab))
		{
			client.setVarbit(VarbitID.BANK_CURRENTTAB, dest);
			bankSearch.layoutBank();
		}
	}

	// True if the rectangle overlaps any overlay-drawn extra tab or the + (so the drag-ghost should be
	// redrawn on top of them rather than slip underneath).
	private boolean overlapsOverlayTab(Rectangle r)
	{
		for (Rectangle tab : extraTabRects.values())
		{
			if (tab != null && tab.intersects(r))
			{
				return true;
			}
		}
		final Rectangle plus = newTabRect;
		return plus != null && plus.intersects(r);
	}

	// The overlay extra-tab whose button contains p, or -1.
	private int extraTabAt(java.awt.Point p)
	{
		for (Map.Entry<Integer, Rectangle> en : extraTabRects.entrySet())
		{
			final Rectangle r = en.getValue();
			if (r != null && r.contains(p))
			{
				return en.getKey();
			}
		}
		return -1;
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
		extraTabRects = Collections.emptyMap();
		lastTabBg = null;
		tabBgPitch = 0;
		pressExtraTab = -1;
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
			final Rectangle sr = slot < slotRects.length ? slotRects[slot] : null;
			grabDx = sr != null ? p.x - sr.x : 16;
			grabDy = sr != null ? p.y - sr.y : 16;
			dragFrom = -1;
			return e;
		}
		// Press on a numbered tab button -> arm a tab-reorder drag (a plain click still switches tabs).
		final int tabBtn = tabButtonAt(p);
		if (tabBtn >= 1)
		{
			pressTabBtn = tabBtn;
			pressPoint = p;
			dragTabBtn = -1;
			return e;
		}
		// Press on an overlay extra-tab button -> arm a click-to-view (it's also a drop target on release).
		// Don't consume: the press reaching the bank is what plays the native tab-select sound.
		final int extra = extraTabAt(p);
		if (extra >= 1)
		{
			pressExtraTab = extra;
			pressPoint = p;
		}
		return e;
	}

	// The numbered tab (1-9, real or virtual) whose button contains {@code p}, or -1. Used by the plugin to
	// add a "Set icon" entry to a bank tab's right-click menu.
	int tabAt(java.awt.Point p)
	{
		final int real = tabButtonAt(p);
		return real >= 1 ? real : extraTabAt(p);
	}

	// The numbered tab (1-9) whose button contains {@code p}, or -1 (the main/all-items tab isn't returned).
	private int tabButtonAt(java.awt.Point p)
	{
		for (Map.Entry<Integer, Rectangle> en : tabRects.entrySet())
		{
			final Rectangle r = en.getValue();
			if (r != null && en.getKey() >= 1 && r.contains(p))
			{
				return en.getKey();
			}
		}
		return -1;
	}

	@Override
	public java.awt.event.MouseEvent mouseDragged(java.awt.event.MouseEvent e)
	{
		final java.awt.Point press = pressPoint;
		// Movement alone isn't enough to call it a drag: the client must have engaged its own widget drag
		// too (past the drag dead time/zone), or a quick withdraw click that slides a few pixels rearranges
		// the template (issue #35). This also inherits Anti Drag's delay / on-shift-only settings.
		final boolean clientDragging = nativeDragging;
		if (dragFrom < 0 && pressSlot >= 0 && press != null && clientDragging && press.distance(e.getPoint()) > DRAG_THRESHOLD)
		{
			// Movement past the threshold means a drag (rearrange the template), not a click (withdraw).
			dragFrom = pressSlot;
		}
		if (dragTabBtn < 0 && pressTabBtn >= 1 && press != null && clientDragging && press.distance(e.getPoint()) > DRAG_THRESHOLD)
		{
			// Past the threshold from a tab button -> a tab-reorder drag, not a tab switch (click).
			dragTabBtn = pressTabBtn;
		}
		if (dragFrom >= 0 || dragTabBtn >= 0)
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
		final int fromTab = dragTabBtn;
		final int pressExtra = pressExtraTab;
		pressSlot = -1;
		pressPoint = null;
		dragFrom = -1;
		dragPoint = null;
		pressTabBtn = -1;
		dragTabBtn = -1;
		pressExtraTab = -1;
		final java.awt.Point p = e.getPoint();

		// A tab button was dragged: dropped onto a different numbered tab (real or overlay extra) -> swap the
		// template's tabs.
		if (fromTab >= 1)
		{
			int destTab = tabButtonAt(p);
			if (destTab < 1)
			{
				destTab = extraTabAt(p);
			}
			if (destTab >= 1 && destTab != fromTab)
			{
				final int dest = destTab;
				clientThread.invoke(() -> layoutEditor.moveTab(fromTab, dest));
			}
			return e;
		}

		if (from < 0)
		{
			// A plain click on an overlay extra-tab button makes that tab the current bank tab. Setting the
			// native current-tab varbit lets the game draw the selection, title, hover and connector itself;
			// the real bank has no items there, so the renderer fills the view from the template.
			if (pressExtra >= 1 && extraTabAt(p) == pressExtra)
			{
				final int view = pressExtra;
				clientThread.invoke(() ->
				{
					client.setVarbit(VarbitID.BANK_CURRENTTAB, view);
					bankSearch.layoutBank();
					client.playSoundEffect(SoundEffectID.UI_BOOP);
				});
				e.consume();
			}
			return e;
		}
		final int tab = currentTab;

		// We deliberately DON'T consume the release. The native bank drag completes underneath us, which is
		// what clears the client's drag state (so the next click isn't swallowed into a withdraw of the item
		// we just dragged). Its real reorder is already blocked by the item's drag-complete listener; here we
		// just rewrite the template to match where the item was dropped.

		// Dropped onto the "+" button -> create a new tab from this item (like the real bank).
		final Rectangle newTab = newTabRect;
		if (newTab != null && newTab.contains(p))
		{
			clientThread.invoke(() -> navigateIfCollapsed(tab, layoutEditor.moveToNewTab(tab, from)));
			return e;
		}

		// Dropped onto an overlay extra-tab button -> move the item into that tab.
		final int extraDest = extraTabAt(p);
		if (extraDest >= 1 && extraDest != tab)
		{
			clientThread.invoke(() -> navigateIfCollapsed(tab, layoutEditor.moveToTab(tab, from, extraDest)));
			return e;
		}

		// Dropped onto a different tab's button -> move the item into that tab.
		for (Map.Entry<Integer, Rectangle> en : tabRects.entrySet())
		{
			final Rectangle r = en.getValue();
			if (r != null && r.contains(p) && en.getKey() != tab)
			{
				final int destTab = en.getKey();
				clientThread.invoke(() -> navigateIfCollapsed(tab, layoutEditor.moveToTab(tab, from, destTab)));
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
			this.lastTabBg = null;
			this.tabBgPitch = 0;
			return map;
		}
		int tab = 1;
		final java.util.List<Rectangle> bgs = new java.util.ArrayList<>();
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
					// Hidden once the template has 9 tabs - don't make an invisible + a drop target.
					this.newTabRect = c.isHidden() ? null : c.getBounds();
				}
				else if (!c.isHidden() && (c.getSpriteId() == SpriteID.Banktabs.TAB
					|| c.getSpriteId() == SpriteID.Banktabs.SELECTED || c.getSpriteId() == SpriteID.Banktabs.HOVERED))
				{
					// Include HOVERED (a hovered tab swaps its background sprite - leaving it out would shift the
					// overlay extras), but skip HIDDEN backgrounds: with "hide items not in template" on, hidden
					// tab backgrounds keep stale far-right positions that would push the extras off-screen.
					bgs.add(c.getBounds());
				}
				else if (c.getItemId() > 0 && tab <= 9)
				{
					map.put(tab++, c.getBounds());
				}
			}
		}
		// The tab backgrounds (all-items + numbered, ascending x); the rightmost is the last real tab. Used to
		// size/place the overlay extra tabs to match the native tab buttons exactly.
		bgs.sort((a, b) -> a.x - b.x);
		// A selected/hovered tab can carry a second background at the same x as its base tab; collapse
		// duplicate x positions so the pitch (spacing between adjacent tabs) can't come out as 0 (which blanks
		// the overlay extra tabs).
		final java.util.List<Rectangle> distinct = new java.util.ArrayList<>();
		for (Rectangle r : bgs)
		{
			if (distinct.isEmpty() || distinct.get(distinct.size() - 1).x != r.x)
			{
				distinct.add(r);
			}
		}
		this.lastTabBg = distinct.isEmpty() ? null : distinct.get(distinct.size() - 1);
		this.tabBgPitch = distinct.size() >= 2 ? distinct.get(1).x - distinct.get(0).x : 0;
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
				clientThread.invoke(() ->
				{
					// An item can only live in one bank tab: don't add a duplicate. Already in this tab -> nothing
					// to do; already in another tab -> offer to move the original here; otherwise add it.
					final int[] loc = layoutEditor.find(id);
					if (loc == null)
					{
						layoutEditor.addItem(tab, id);
						return;
					}
					final String name = layoutEditor.displayName(id);
					if (loc[0] == tab)
					{
						javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
							null, name + " is already in this tab.", "Already in tab", javax.swing.JOptionPane.INFORMATION_MESSAGE));
						return;
					}
					final String from = layoutEditor.tabLabel(loc[0]);
					javax.swing.SwingUtilities.invokeLater(() ->
					{
						final int choice = javax.swing.JOptionPane.showConfirmDialog(null,
							name + " is already in " + from + ". Move it to this tab?",
							"Move item", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
						if (choice == javax.swing.JOptionPane.OK_OPTION)
						{
							clientThread.invoke(() -> layoutEditor.moveToTab(loc[0], loc[1], tab));
						}
					});
				})));
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
