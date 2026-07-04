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
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.VarClientInt;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseListener;
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
public class ReorgHelperOverlay extends Overlay implements MouseListener
{
	// Set true when the user clicks the title-bar "Skip" button to bypass reserving slots with fillers;
	// the sort then runs as-is (owned items left-shift into place). Reset when the reorg helper is closed.
	private volatile boolean skipFillers;
	// Screen bounds of the title-bar "Skip" button (set while the filler-setup step is shown).
	private volatile Rectangle skipButtonRect;
	// Single move steps the user has skipped this session (by signature), so each is bypassed and the next
	// step is shown instead. Cleared when the reorg helper is closed.
	private final java.util.Set<String> skippedSteps = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// Signature of the move step currently shown (null during the filler step or when nothing is shown). The
	// title-bar Skip button adds this to skippedSteps.
	private volatile String currentStepSig;
	// Bounds of the current step's source item, and the step we last auto-scrolled for, so the bank brings
	// the highlighted item into view once when the step changes (without fighting the user's own scrolling).
	private Rectangle stepItemBounds;
	private String lastScrollSig;

	private static final Color SOURCE_COLOR = new Color(255, 165, 0);   // orange: item to move
	private static final Color DONE_COLOR = new Color(110, 200, 110);
	private static final int[] TAB_COUNT_VARBITS = {
		VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4,
		VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8,
		VarbitID.BANK_TAB_9
	};

	private final Client client;
	private final BankTemplatesConfig config;
	private final TemplateManager templateManager;
	private final ItemManager itemManager;
	private final BankLayoutRenderer renderer;

	// The chosen swap/insert mode is fixed per tab so the user only toggles once. Cached against the
	// tab + template it was decided for.
	private int planTab = -1;
	private String planTemplate = "";
	private boolean planSwap;

	@Inject
	ReorgHelperOverlay(Client client, BankTemplatesConfig config, TemplateManager templateManager, ItemManager itemManager,
		BankLayoutRenderer renderer)
	{
		this.client = client;
		this.config = config;
		this.templateManager = templateManager;
		this.itemManager = itemManager;
		this.renderer = renderer;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showReorgHelper())
		{
			skipFillers = false;
			skipButtonRect = null;
			skippedSteps.clear();
			return null;
		}
		skipButtonRect = null;
		currentStepSig = null;
		stepItemBounds = null;

		final BankTemplate template = templateManager.getActive();
		if (template == null)
		{
			return null;
		}

		final BankTemplatesConfig.ReorgDisplay mode = config.reorgDisplay();
		final boolean color = mode.isColor();
		final boolean labels = mode.isLabels();
		final boolean steps = mode.isSteps();

		// In a Bank Tags tag tab, a search, or an Inventory Setups bank view the bank is filtered and can't be
		// reorganised - guide the user back to the normal bank view (the all-items tab) first.
		if (renderer.isBankFilteredNow())
		{
			final Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
			if (steps && items != null)
			{
				setBankTitle("Open the main bank tab to reorganise this template", Color.WHITE, null, false);
				final Widget allItems = tabButtons().get(BankTemplate.MAIN_TAB);
				if (allItems != null && !allItems.isHidden())
				{
					pulseRect(graphics, allItems.getBounds(), config.reorgHighlightColor());
				}
			}
			return null;
		}

		// Filler-setup guidance runs first, and even when the bank settings menu is open (which hides the
		// item container): guide reserving real bank slots for unowned-item placeholders and filler slots.
		if (steps && !skipFillers)
		{
			// Cap the ask at the free bank slots - never request more fillers than physically fit.
			final int free = freeBankSlots();
			final int wanted = reservedSlotsNeeded(template, ownedItems()) - bankFillerCount();
			final int needed = Math.min(wanted, free);
			if (needed > 0)
			{
				// If reserving would fill the bank to the brim, "All" is simpler than typing the count.
				final boolean fillAll = free != Integer.MAX_VALUE && wanted >= free;
				drawFillerSetup(graphics, needed, fillAll);
				return null;
			}
		}

		// Past filler setup (skipped, or none needed) but the bank settings / fillers screen is still open -
		// it hides the item list, so guide the user back to the bank before the sort steps can run.
		if (steps)
		{
			final Widget menu = client.getWidget(InterfaceID.Bankmain.MENU_CONTAINER);
			if (menu != null && !menu.isHidden() && menu.getBounds().width > 0)
			{
				setBankTitle("Close bank settings to return to the bank", Color.WHITE, null, false);
				final Widget spanner = client.getWidget(InterfaceID.Bankmain.MENU_BUTTON);
				if (spanner != null && !spanner.isHidden())
				{
					pulseRect(graphics, spanner.getBounds(), config.reorgHighlightColor());
				}
				return null;
			}
		}

		final Widget itemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (itemContainer == null || itemContainer.isHidden())
		{
			return null;
		}

		// Which exact variants you own separately must stay distinct while sorting (see reorgId). Rebuilt
		// each frame so it tracks the live template and bank.
		exactShared = sharedExactIds(template);

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
					current.add(reorgId(c.getItemId()));
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
				final int canon = reorgId(v);
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

		final Shape oldClip = graphics.getClip();

		// Colour-coding is its own cue now (combinable with labels and step-by-step): tint out-of-place items
		// and outline tabs with their destination colour, independent of whether step guidance is shown.
		if (color)
		{
			drawColorHints(graphics, itemContainer, widgets, current, itemTab, targetTab);
		}

		if (labels)
		{
			graphics.setClip(itemContainer.getBounds());
			drawLabels(graphics, widgets, current, itemTab, targetTab, targetRank, template.getColumns());
			graphics.setClip(oldClip);
		}

		if (steps)
		{
			drawSteps(graphics, itemContainer, template, currentTab, widgets, current, itemTab, targetTab, owned);
			// Let the user skip whatever single move is currently shown - the next step then appears.
			if (currentStepSig != null)
			{
				drawSkipButton(graphics);
			}
			// Bring the highlighted item into view when the step changes (once, so it doesn't fight scrolling).
			if (currentStepSig != null && !currentStepSig.equals(lastScrollSig) && stepItemBounds != null)
			{
				scrollItemIntoView(itemContainer, stepItemBounds);
			}
			lastScrollSig = currentStepSig;
		}

		return null;
	}

	// How many real bank fillers the user should place: one per template filler slot, plus one per slot
	// holding a placeholder for an item they don't own yet (so the space is reserved until they get it).
	private int reservedSlotsNeeded(BankTemplate template, Set<Integer> owned)
	{
		int n = 0;
		for (TabLayout tl : template.getTabs())
		{
			for (Integer v : tl.getLayout())
			{
				if (v == null || v <= 0)
				{
					continue;
				}
				if (v == BankTemplate.FILLER || !owned.contains(reorgId(v)))
				{
					n++;
				}
			}
		}
		return n;
	}

	// Genuine bank fillers currently in the real bank.
	private int bankFillerCount()
	{
		int n = 0;
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			for (Item it : bank.getItems())
			{
				if (it.getId() == ItemID.BANK_FILLER)
				{
					n++;
				}
			}
		}
		return n;
	}

	// Free slots in the real bank = its capacity (varies per account) minus items currently stored. Used to
	// cap how many fillers we ask for, so a template bigger than the player's bank doesn't request more
	// fillers than can physically fit (the sort then left-shifts the slots that couldn't be reserved).
	private int freeBankSlots()
	{
		final int capacity = parseCount(client.getWidget(InterfaceID.Bankmain.CAPACITY));
		if (capacity <= 0)
		{
			return Integer.MAX_VALUE; // unknown capacity - don't cap
		}
		int occupied = 0;
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			for (Item it : bank.getItems())
			{
				if (it.getId() > 0)
				{
					occupied++;
				}
			}
		}
		return Math.max(0, capacity - occupied);
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

	// Fillers each tab needs (indexed by tab 0..9): one per template filler slot and per unowned-item
	// placeholder, so those slots can be reserved.
	private int[] fillersNeededPerTab(BankTemplate template, Set<Integer> owned)
	{
		final int[] need = new int[LayoutEditor.MAX_TABS + 1];
		for (TabLayout tl : template.getTabs())
		{
			final int tab = tl.getTab();
			if (tab < 0 || tab >= need.length)
			{
				continue;
			}
			for (Integer v : tl.getLayout())
			{
				if (v == null || v <= 0)
				{
					continue;
				}
				if (v == BankTemplate.FILLER || !owned.contains(reorgId(v)))
				{
					need[tab]++;
				}
			}
		}
		return need;
	}

	// Genuine bank fillers currently in each real bank tab (indexed by tab 0..9), bucketed by the per-tab
	// item counts (items run tab 1..9 then the main view).
	private int[] fillersPerTab()
	{
		final int[] have = new int[LayoutEditor.MAX_TABS + 1];
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return have;
		}
		final Item[] items = bank.getItems();
		int idx = 0;
		for (int t = 1; t <= LayoutEditor.MAX_TABS; t++)
		{
			final int count = client.getVarbitValue(TAB_COUNT_VARBITS[t - 1]);
			for (int k = 0; k < count && idx < items.length; k++, idx++)
			{
				if (items[idx] != null && items[idx].getId() == ItemID.BANK_FILLER)
				{
					have[t]++;
				}
			}
		}
		for (; idx < items.length; idx++)
		{
			if (items[idx] != null && items[idx].getId() == ItemID.BANK_FILLER)
			{
				have[BankTemplate.MAIN_TAB]++;
			}
		}
		return have;
	}

	// Guide the user through the bank's Bank Fillers control to add the fillers that reserve unowned slots.
	private void drawFillerSetup(Graphics2D g, int needed, boolean fillAll)
	{
		final Widget menu = client.getWidget(InterfaceID.Bankmain.MENU_CONTAINER);
		final boolean settingsOpen = menu != null && !menu.isHidden() && menu.getBounds().width > 0;
		final String slots = needed + (needed == 1 ? " slot" : " slots");
		final String goal = "Reserve " + slots + " for items you don't own yet";

		// The title just states the goal; the pulsing buttons (and the chatbox number) guide the clicks.
		setBankTitle(goal, Color.WHITE, null, false);
		if (!settingsOpen)
		{
			highlight(g, InterfaceID.Bankmain.MENU_BUTTON);
		}
		else if (fillAll)
		{
			// Reserving fills the bank completely: click All, then Fill (no count to type).
			highlight(g, InterfaceID.Bankmain.BANK_FILLER_ALL);
			highlight(g, InterfaceID.Bankmain.BANK_FILLER_CONFIRM);
		}
		else
		{
			// In the settings menu: walk through the Bank Fillers control - click X, type the count, Fill.
			highlight(g, InterfaceID.Bankmain.BANK_FILLER_X);
			highlight(g, InterfaceID.Bankmain.BANK_FILLER_CONFIRM);
		}

		// Once the "Enter amount" input is open, show the number big in the chatbox so it's easy to read
		// while typing. (We only display it - typing/entering it for you would be against the rules.)
		if (!fillAll)
		{
			drawChatboxAmount(g, needed);
		}

		// A "Skip" button in the title bar to bypass reserving slots entirely (sort runs as-is).
		drawSkipButton(g);
	}

	// Draws the "Skip" button at the right of the bank title bar and records its bounds for click handling.
	private void drawSkipButton(Graphics2D g)
	{
		final Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (title == null || title.isHidden() || title.getBounds().width <= 0)
		{
			skipButtonRect = null;
			return;
		}
		final Rectangle tb = title.getBounds();
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
		final FontMetrics fm = g.getFontMetrics();
		final String label = "Skip";
		final int w = fm.stringWidth(label) + 12;
		final int h = Math.min(tb.height - 2, 18);
		// Sit left of the bank's close (X) button so it isn't crowded against it.
		final Rectangle r = new Rectangle(tb.x + tb.width - w - 46, tb.y + (tb.height - h) / 2, w, h);
		g.setColor(new Color(110, 30, 30));
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setColor(new Color(220, 90, 90));
		g.drawRect(r.x, r.y, r.width, r.height);
		g.setColor(Color.WHITE);
		g.drawString(label, r.x + 6, r.y + (h + fm.getAscent()) / 2 - 2);
		skipButtonRect = r;
	}

	@Override
	public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
	{
		final Rectangle r = skipButtonRect;
		if (r != null && r.contains(e.getPoint()))
		{
			final String sig = currentStepSig;
			if (sig != null)
			{
				// Skip just the move currently shown; the next step appears.
				skippedSteps.add(sig);
			}
			else
			{
				// Filler-reservation step: bypass reserving slots entirely.
				skipFillers = true;
			}
			e.consume();
		}
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseClicked(java.awt.event.MouseEvent e)
	{
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseReleased(java.awt.event.MouseEvent e)
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
	public java.awt.event.MouseEvent mouseDragged(java.awt.event.MouseEvent e)
	{
		return e;
	}

	@Override
	public java.awt.event.MouseEvent mouseMoved(java.awt.event.MouseEvent e)
	{
		return e;
	}

	// Draws the filler count prominently at the top of the chatbox while the "Enter amount" input is open.
	private void drawChatboxAmount(Graphics2D g, int needed)
	{
		// INPUT_TYPE is non-zero whenever a chatbox input dialog (here, "Enter amount") is open.
		if (client.getVarcIntValue(VarClientInt.INPUT_TYPE) == 0)
		{
			return;
		}
		// Anchor over the chatbox message layer (the parchment) and draw the number near its top.
		final Widget area = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		if (area == null || area.isHidden() || area.getBounds().width <= 0)
		{
			return;
		}
		final Rectangle b = area.getBounds();
		final String num = "Type " + needed;
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 20f));
		final FontMetrics fm = g.getFontMetrics();
		final int x = b.x + (b.width - fm.stringWidth(num)) / 2;
		final int y = b.y + fm.getAscent() + 8;
		g.setColor(new Color(0, 0, 0, 190));
		g.fillRect(x - 6, y - fm.getAscent(), fm.stringWidth(num) + 12, fm.getHeight());
		g.setColor(new Color(120, 180, 255));
		g.drawString(num, x, y);
	}

	private void highlight(Graphics2D g, int widgetId)
	{
		final Widget w = client.getWidget(widgetId);
		if (w != null && !w.isHidden() && w.getBounds().width > 0)
		{
			pulseRect(g, w.getBounds(), config.reorgHighlightColor());
		}
	}

	// Draw a destination tag on each out-of-place item: tab number if in the wrong tab, else row-col.
	// Subtle colour cues layered under the Step-by-step guidance: each numbered tab button gets an identity
	// colour, and items sitting in the wrong tab are washed with their DESTINATION tab's colour so you can
	// see where to drag them at a glance. Items already in their correct tab are left clean (look-ahead).
	private void drawColorHints(Graphics2D g, Widget itemContainer, List<Widget> widgets, List<Integer> current,
		int[] itemTab, Map<Integer, Integer> targetTab)
	{
		final Shape oldClip = g.getClip();
		final Stroke oldStroke = g.getStroke();

		// Tab button identity outlines (above the item container, so drawn without the item-area clip).
		for (Map.Entry<Integer, Widget> e : tabButtons().entrySet())
		{
			final Color c = hintColor(e.getKey());
			final Widget btn = e.getValue();
			if (c == null || btn == null || btn.isHidden())
			{
				continue;
			}
			final Rectangle r = btn.getBounds();
			if (r.width > 0)
			{
				g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 150));
				g.setStroke(new BasicStroke(2f));
				g.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
			}
		}

		// + (new tab) button: when the template needs a tab you don't have yet, colour it with that tab's
		// colour so it matches the items destined for it.
		final Widget addBtn = addTabButton();
		if (addBtn != null && !addBtn.isHidden())
		{
			int existing = 0;
			for (int key : tabButtons().keySet())
			{
				if (key != BankTemplate.MAIN_TAB && key > existing)
				{
					existing = key;
				}
			}
			int lowestMissing = Integer.MAX_VALUE;
			for (int k = 0; k < current.size(); k++)
			{
				final Integer tt = targetTab.get(current.get(k));
				if (tt != null && tt != BankTemplate.MAIN_TAB && tt > existing && tt < lowestMissing)
				{
					lowestMissing = tt;
				}
			}
			final Color c = lowestMissing != Integer.MAX_VALUE ? hintColor(lowestMissing) : null;
			final Rectangle r = addBtn.getBounds();
			if (c != null && r.width > 0)
			{
				g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 150));
				g.setStroke(new BasicStroke(2f));
				g.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
			}
		}
		g.setStroke(oldStroke);

		// Wrong-tab items: a faint wash of their destination tab's colour (including items that belong in the
		// main/all-items tab but are currently sitting in a numbered tab).
		g.setClip(itemContainer.getBounds());
		for (int k = 0; k < current.size(); k++)
		{
			final Integer tt = targetTab.get(current.get(k));
			if (tt == null || itemTab[k] == tt)
			{
				continue;
			}
			final Color c = hintColor(tt);
			final Rectangle b = widgets.get(k).getBounds();
			if (c != null && b.width > 0)
			{
				g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
				g.fillRect(b.x, b.y, b.width, b.height);
			}
		}
		g.setClip(oldClip);
	}

	// Per-tab identity colour from config (index = tab number; 0 = the main/all-items tab). null for any tab
	// outside 0..9.
	private Color hintColor(int tab)
	{
		switch (tab)
		{
			case 0:
				return config.reorgTabColorMain();
			case 1:
				return config.reorgTabColor1();
			case 2:
				return config.reorgTabColor2();
			case 3:
				return config.reorgTabColor3();
			case 4:
				return config.reorgTabColor4();
			case 5:
				return config.reorgTabColor5();
			case 6:
				return config.reorgTabColor6();
			case 7:
				return config.reorgTabColor7();
			case 8:
				return config.reorgTabColor8();
			case 9:
				return config.reorgTabColor9();
			default:
				return null;
		}
	}

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
			if (b.width > 0)
			{
				final int idx = targetRank.get(current.get(k));
				if (idx != rank)
				{
					// Right tab, wrong slot: tag it with the row-column it should sit at.
					drawTag(g, b, (idx / columns + 1) + "-" + (idx % columns + 1), config.reorgHighlightColor());
				}
				else
				{
					// Right tab and right slot - a green tick to confirm it's already where it belongs.
					drawCheckTag(g, b);
				}
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
		List<Widget> widgets, List<Integer> current, int[] itemTab, Map<Integer, Integer> targetTab, Set<Integer> owned)
	{
		// Tab phase: the first item sitting in the wrong tab. Highlight it and its destination tab button.
		final Map<Integer, Widget> buttons = tabButtons();
		int existingTabs = 0;
		for (int key : buttons.keySet())
		{
			if (key != BankTemplate.MAIN_TAB && key > existingTabs)
			{
				existingTabs = key;
			}
		}
		final int fillerCanon = reorgId(BankTemplate.FILLER);

		// The + button for creating a new tab, and the lowest template tab you don't have yet. Tabs can only be
		// created in sequence, so that lowest missing tab is the only new one you can make next - higher ones
		// must wait until it exists. A new tab routes exactly like an existing destination, except its items are
		// dragged onto + (which creates the tab) instead of onto a tab header.
		final Widget addBtn = addTabButton();
		final boolean canCreateTab = addBtn != null && !addBtn.isHidden();
		int lowestMissing = Integer.MAX_VALUE;
		if (canCreateTab)
		{
			for (int t = BankTemplate.MAIN_TAB; t <= 9; t++)
			{
				for (int canon : tabItemsCanonical(t))
				{
					final Integer tt = targetTab.get(canon);
					if (tt != null && tt != BankTemplate.MAIN_TAB && tt > existingTabs && tt < lowestMissing
						&& !skippedSteps.contains("N:" + canon + ":" + tt))
					{
						lowestMissing = tt;
					}
				}
			}
		}

		// Pass 1a: a single forward sweep over the tab you're VIEWING, slot by slot (left to right, top to
		// bottom). Each wrong-tab item is dragged to its destination as you reach it - to a tab that already
		// exists, or onto + to create the next new tab when one of its items comes up. A higher new tab that
		// can't be created yet (its lower tabs don't exist) is passed over and picked up later, still going
		// forwards - no step ever sends you back to an earlier slot.
		for (int k = 0; k < current.size(); k++)
		{
			final Integer tt = targetTab.get(current.get(k));
			if (tt == null || itemTab[k] == tt || itemTab[k] != currentTab)
			{
				continue;
			}
			final boolean isNew = tt != BankTemplate.MAIN_TAB && tt > existingTabs;
			if (isNew && (!canCreateTab || tt != lowestMissing))
			{
				continue;
			}
			final String sig = (isNew ? "N:" : "T:") + current.get(k) + ":" + tt;
			if (skippedSteps.contains(sig))
			{
				continue;
			}
			currentStepSig = sig;
			final Widget from = widgets.get(k);
			final String name = client.getItemDefinition(from.getItemId()).getName();
			markSource(g, itemContainer, from.getBounds());
			if (isNew)
			{
				final Color addColor = hintColor(tt);
				pulseRect(g, addBtn.getBounds(), addColor != null ? addColor : config.reorgHighlightColor());
				setBankTitle("Drag " + name + " onto the + button to create tab " + tt, Color.WHITE, null, false);
			}
			else
			{
				final Widget tabBtn = buttons.get(tt);
				if (tabBtn != null)
				{
					pulseRect(g, tabBtn.getBounds(), config.reorgHighlightColor());
				}
				setBankTitle("Drag " + name + " to " + (tt == BankTemplate.MAIN_TAB ? "the main tab" : "tab " + tt), Color.WHITE, null, false);
			}
			return;
		}

		// Pass 1b: items stuck in OTHER tabs - send the user to the next such tab (you can only drag an item to a
		// tab from the tab it's sitting in). Reads the bank directly so it works from any view, with no detour
		// through all-items. Covers both existing-tab destinations and items for the next new tab to be created.
		// Skipped moves don't count, so a skipped move can't pin routing as unfinished forever.
		for (int t = BankTemplate.MAIN_TAB; t <= 9; t++)
		{
			if (t == currentTab)
			{
				continue;
			}
			boolean misrouted = false;
			for (int canon : tabItemsCanonical(t))
			{
				if (canon == fillerCanon)
				{
					continue;
				}
				final Integer tt = targetTab.get(canon);
				if (tt == null || tt == t)
				{
					continue;
				}
				final boolean reachable = tt == BankTemplate.MAIN_TAB || tt <= existingTabs || (canCreateTab && tt == lowestMissing);
				if (reachable
					&& !skippedSteps.contains("T:" + canon + ":" + tt)
					&& !skippedSteps.contains("N:" + canon + ":" + tt))
				{
					misrouted = true;
					break;
				}
			}
			if (misrouted)
			{
				// Opening a tab to reach an item is navigation, not a move - it isn't skippable.
				final Widget tabBtn = buttons.get(t);
				if (tabBtn != null)
				{
					pulseRect(g, tabBtn.getBounds(), config.reorgHighlightColor());
				}
				final String open = t == BankTemplate.MAIN_TAB ? "Open the main tab" : "Open tab " + t;
				setBankTitle(open + " to move its items to their tabs", Color.WHITE, null, false);
				return;
			}
		}

		// Filler-balancing: every item is in its tab now - move SURPLUS bank fillers (a tab holding more than
		// its template needs) into tabs that still need them.
		final int[] need = fillersNeededPerTab(template, owned);
		final int[] have = fillersPerTab();
		int deficitTab = -1;
		for (int t = 0; t < need.length; t++)
		{
			if (have[t] < need[t] && !skippedSteps.contains("F:" + t))
			{
				deficitTab = t;
				break;
			}
		}
		if (deficitTab != -1)
		{
			for (int k = 0; k < current.size(); k++)
			{
				final int src = itemTab[k];
				if (current.get(k) != fillerCanon || src == deficitTab || have[src] <= need[src])
				{
					continue;
				}
				currentStepSig = "F:" + deficitTab;
				final Widget from = widgets.get(k);
				markSource(g, itemContainer, from.getBounds());
				final Widget tabBtn = buttons.get(deficitTab);
				final String tabName = deficitTab == BankTemplate.MAIN_TAB ? "the main tab" : "tab " + deficitTab;
				if (tabBtn != null)
				{
					pulseRect(g, tabBtn.getBounds(), config.reorgHighlightColor());
				}
				setBankTitle("Drag the bank filler to " + tabName, Color.WHITE, null, false);
				return;
			}
		}

		// Ordering phase: every item is now in its tab. Order each tab's slots, lowest tab first - the current
		// view directly, and for any other tab still out of order, send the user there (skipping tabs that are
		// already ordered) to sort it.
		boolean currentOrdered = false;
		for (int t = BankTemplate.MAIN_TAB; t <= 9; t++)
		{
			final int[] layout = template.tabLayout(t);
			if (layout == null || layout.length == 0)
			{
				continue;
			}
			if (t == currentTab)
			{
				final boolean shownPos;
				if (currentTab == BankTemplate.MAIN_TAB)
				{
					final List<Widget> mainWidgets = new ArrayList<>();
					final List<Integer> mainCurrent = new ArrayList<>();
					for (int k = 0; k < current.size(); k++)
					{
						if (itemTab[k] == BankTemplate.MAIN_TAB)
						{
							mainWidgets.add(widgets.get(k));
							mainCurrent.add(current.get(k));
						}
					}
					shownPos = drawPositionStep(g, itemContainer, template, BankTemplate.MAIN_TAB, mainWidgets, mainCurrent);
				}
				else
				{
					shownPos = drawPositionStep(g, itemContainer, template, currentTab, widgets, current);
				}
				if (shownPos)
				{
					return;
				}
				currentOrdered = true;
			}
			else if (!tabSorted(template, t))
			{
				final Widget tabBtn = buttons.get(t);
				if (tabBtn != null)
				{
					pulseRect(g, tabBtn.getBounds(), config.reorgHighlightColor());
				}
				final String open = (t == BankTemplate.MAIN_TAB ? "Open the main tab" : "Open tab " + t) + " to sort it";
				setBankTitle(currentOrdered ? "Tab organised! " + open + " next" : open, Color.WHITE, null, false);
				return;
			}
		}

		// Everything is in its tab and ordered.
		setBankTitle(skippedSteps.isEmpty() ? "Bank matches the template" : "Done (some steps skipped)", DONE_COLOR, null, false);
	}

	// Builds the desired packed order for a tab: each template slot takes the real item if owned, otherwise a
	// filler; leftovers are appended. Mirrors drawPositionStep so tabSorted agrees with it.
	private List<Integer> buildTabTarget(int[] desired, List<Integer> current)
	{
		final int fillerCanon = reorgId(BankTemplate.FILLER);
		final Map<Integer, Integer> remaining = new HashMap<>();
		for (int canon : current)
		{
			remaining.merge(canon, 1, Integer::sum);
		}
		final List<Integer> target = new ArrayList<>();
		for (int id : desired)
		{
			if (id == BankTemplate.EMPTY)
			{
				continue;
			}
			final int want = id != BankTemplate.FILLER && remaining.getOrDefault(reorgId(id), 0) > 0
				? reorgId(id)
				: fillerCanon;
			final int count = remaining.getOrDefault(want, 0);
			if (count > 0)
			{
				target.add(want);
				remaining.put(want, count - 1);
			}
		}
		for (int canon : current)
		{
			final Integer count = remaining.get(canon);
			if (count != null && count > 0)
			{
				target.add(canon);
				remaining.put(canon, count - 1);
			}
		}
		return target;
	}

	// Canonical ids currently in a tab, in bank order (main = the untabbed items after all numbered tabs).
	private List<Integer> tabItemsCanonical(int tab)
	{
		final List<Integer> out = new ArrayList<>();
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return out;
		}
		final Item[] items = bank.getItems();
		int idx = 0;
		for (int t = 1; t < (tab == BankTemplate.MAIN_TAB ? 10 : tab); t++)
		{
			idx += client.getVarbitValue(TAB_COUNT_VARBITS[t - 1]);
		}
		final int count = tab == BankTemplate.MAIN_TAB ? items.length - idx : client.getVarbitValue(TAB_COUNT_VARBITS[tab - 1]);
		for (int k = 0; k < count && idx < items.length; k++, idx++)
		{
			if (items[idx] != null && items[idx].getId() > 0 && items[idx].getId() != ItemID.BLANKOBJECT)
			{
				out.add(reorgId(items[idx].getId()));
			}
		}
		return out;
	}

	// Whether a tab needs no further ordering move. True in EXACTLY the cases drawPositionStep would find
	// nothing to do (same target packing, same skip handling, same "wanted item not reachable" give-up), so
	// the two can never disagree about a tab and bounce the user back and forth between it and another tab.
	private boolean tabSorted(BankTemplate template, int tab)
	{
		final int[] desired = template.tabLayout(tab);
		if (desired == null || desired.length == 0)
		{
			return true;
		}
		final List<Integer> cur = tabItemsCanonical(tab);
		final List<Integer> target = buildTabTarget(desired, cur);
		// Advance over slots that already match or were skipped, to the first slot that's both wrong and live.
		int i = 0;
		while (i < cur.size() && i < target.size())
		{
			if (cur.get(i).equals(target.get(i)) || skippedSteps.contains("P:" + tab + ":" + i))
			{
				i++;
				continue;
			}
			break;
		}
		if (i >= cur.size() || i >= target.size())
		{
			return true;
		}
		// A move only exists if the item this slot wants is still sitting at/after it. If it isn't (e.g. it was
		// consumed by an earlier skipped slot), drawPositionStep gives up too - so treat the tab as sorted.
		final int wantCanon = target.get(i);
		int j = i;
		while (j < cur.size() && !cur.get(j).equals(wantCanon))
		{
			j++;
		}
		return j >= cur.size();
	}

	// Orders items within the current tab. Returns true if it showed a move (or a mode-switch prompt), false
	// if the current tab is already fully ordered.
	private boolean drawPositionStep(Graphics2D graphics, Widget itemContainer, BankTemplate template, int currentTab,
		List<Widget> widgets, List<Integer> current)
	{
		// Always order against this single tab's layout (the main view orders only its untabbed items).
		final int[] desired = template.tabLayout(currentTab);
		if (desired == null || desired.length == 0)
		{
			return false;
		}

		// Desired order = the template tab walked slot by slot, packed into what you actually have. Each
		// slot wants either the real item (if you own it) or a genuine bank filler (template filler slots,
		// and placeholders for items you don't own - so the space is reserved). Whatever isn't present is
		// skipped, so the remaining items/fillers LEFT-SHIFT into the packed real bank (which has no gaps):
		// e.g. an item meant for slot 75 lands in slot 72 if the three slots before it are absent.
		//
		// This must stay a permutation of `current` (same items, same multiplicity) or the position maths
		// below drifts - so every slot consumes from `remaining`, and anything left over is appended.
		final int fillerCanon = reorgId(BankTemplate.FILLER);
		final Map<Integer, Integer> remaining = new HashMap<>();
		for (int canon : current)
		{
			remaining.merge(canon, 1, Integer::sum);
		}
		final List<Integer> target = new ArrayList<>();
		for (int id : desired)
		{
			if (id == BankTemplate.EMPTY)
			{
				continue;
			}
			// What this slot wants: the item itself if owned/available, else a filler to reserve it.
			final int want = id != BankTemplate.FILLER && remaining.getOrDefault(reorgId(id), 0) > 0
				? reorgId(id)
				: fillerCanon;
			final int count = remaining.getOrDefault(want, 0);
			if (count > 0)
			{
				target.add(want);
				remaining.put(want, count - 1);
			}
			// else: neither the item nor a spare filler is present - skip it so the rest left-shift.
		}
		for (int canon : current)
		{
			final Integer count = remaining.get(canon);
			if (count != null && count > 0)
			{
				target.add(canon);
				remaining.put(canon, count - 1);
			}
		}

		// Advance over slots that already match, and over any the user chose to skip, so the move shown is the
		// first slot that's both wrong and not skipped.
		int i = 0;
		while (i < current.size() && i < target.size())
		{
			if (current.get(i).equals(target.get(i)) || skippedSteps.contains("P:" + currentTab + ":" + i))
			{
				i++;
				continue;
			}
			break;
		}

		final Shape oldClip = graphics.getClip();
		graphics.setClip(itemContainer.getBounds());

		if (i >= target.size() || i >= current.size())
		{
			graphics.setClip(oldClip);
			planTab = -1;
			return false;
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
			return false;
		}
		currentStepSig = "P:" + currentTab + ":" + i;

		// Longest run of items already in the right relative order - the scaffold an optimal insert sort keeps
		// in place, moving only the items around it. Also drives the mode choice below.
		final int[] perm = permutation(current, target);
		final boolean[] keep = lisKeep(perm);

		// Pick ONE mode for the whole tab so the user only toggles swap/insert once: whichever sorts this tab
		// in fewer drags (min swaps = n - cycles; min inserts = n - the kept run).
		if (planTab != currentTab || !template.getName().equals(planTemplate))
		{
			int kept = 0;
			for (boolean b : keep)
			{
				if (b)
				{
					kept++;
				}
			}
			planSwap = (perm.length - cycleCount(perm)) <= (perm.length - kept);
			planTab = currentTab;
			planTemplate = template.getName();
		}

		final boolean swap = planSwap;
		final int neededMode = swap ? 0 : 1;          // BANK_INSERTMODE: 0 = swap, 1 = insert
		final boolean modeOk = client.getVarbitValue(VarbitID.BANK_INSERTMODE) == neededMode;

		// By default bring the item this slot wants into it (from j to i). But in insert mode, if the item
		// currently sitting in slot i is itself out of place (not part of the kept run) - e.g. one you just
		// dragged to the wrong spot - guide THAT item straight to its own correct slot instead. That single
		// insert slides the shifted run back into place, rather than nudging each item over one slot to make
		// room for it. Gated on insert mode (the slide only works there) and on j being reachable above, so
		// tabSorted still agrees on whether a move exists. (#27)
		int fromIdx = j;
		int toIdx = i;
		if (!swap && i < keep.length && !keep[i])
		{
			fromIdx = i;
			toIdx = perm[i];
		}

		final Widget from = widgets.get(fromIdx);
		final Widget to = widgets.get(toIdx);
		final Color highlight = config.reorgHighlightColor();

		drawGhost(graphics, from.getItemId(), to.getBounds());
		outline(graphics, to.getBounds(), highlight);
		markSource(graphics, itemContainer, from.getBounds());
		drawArrow(graphics, from.getBounds(), to.getBounds(), highlight);

		graphics.setClip(oldClip);

		final String name = client.getItemDefinition(from.getItemId()).getName();
		setBankTitle(
			(swap ? "Swap " : "Insert ") + name + " into the highlighted slot",
			Color.WHITE,
			modeOk ? null : "Switch the bank to " + (swap ? "Swap" : "Insert") + " mode (highlighted)",
			!modeOk);

		if (!modeOk)
		{
			highlightToggle(graphics);
		}
		return true;
	}

	// Canonical ids present in BOTH the active template and your bank, rebuilt each frame. reorgId uses this
	// to keep exact variants you own separately (an item and its ornamented version) distinct while sorting.
	private Set<Integer> exactShared = java.util.Collections.emptySet();

	/**
	 * A stable id for matching a template item to a bank item. Canonicalises placeholders to the real item,
	 * then: if that exact item is in both the template and your bank, returns its own id, so two different
	 * variants of one base that you own separately (e.g. an item and its ornamented version) stay distinct
	 * and sort into their own slots; otherwise it collapses to the variation base, so a single owned variant
	 * still satisfies a template slot asking for a different variant. Bases are negated so an exact base item
	 * can never collide with its own group's collapsed id.
	 */
	private int reorgId(int id)
	{
		final int canon = itemManager.canonicalize(id);
		return exactShared.contains(canon) ? canon : -(ItemVariationMapping.map(canon) + 1);
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
					owned.add(reorgId(it.getId()));
				}
			}
		}
		return owned;
	}

	// Canonical ids present in BOTH the template and the bank - the exact variants reorgId keeps distinct.
	private Set<Integer> sharedExactIds(BankTemplate template)
	{
		final Set<Integer> bankCanon = new HashSet<>();
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			for (Item it : bank.getItems())
			{
				if (it.getId() > 0)
				{
					bankCanon.add(itemManager.canonicalize(it.getId()));
				}
			}
		}
		final Set<Integer> shared = new HashSet<>();
		for (TabLayout tl : template.getTabs())
		{
			for (Integer v : tl.getLayout())
			{
				if (v != null && v > 0)
				{
					final int canon = itemManager.canonicalize(v);
					if (bankCanon.contains(canon))
					{
						shared.add(canon);
					}
				}
			}
		}
		return shared;
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
		// All-items view: assign each visible item to its native tab. Walk the raw bank container by slot,
		// charging every slot against the tab-count varbits (tabs are packed by slot budget, blanks included),
		// but only emitting a tab for slots that pass the SAME filter as `current` (real, non-blank items).
		// Counting filtered widgets against the varbits instead would drift whenever a tab holds a blank slot,
		// misplacing the main-tab boundary - which made tabSorted and drawPositionStep disagree and loop.
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			Arrays.fill(tabs, BankTemplate.MAIN_TAB);
			return tabs;
		}
		final Item[] items = bank.getItems();
		int out = 0;
		int idx = 0;
		for (int t = 1; t <= 9 && out < n; t++)
		{
			final int count = client.getVarbitValue(TAB_COUNT_VARBITS[t - 1]);
			for (int k = 0; k < count && idx < items.length && out < n; k++, idx++)
			{
				if (items[idx] != null && items[idx].getId() > 0 && items[idx].getId() != ItemID.BLANKOBJECT)
				{
					tabs[out++] = t;
				}
			}
		}
		while (out < n)
		{
			tabs[out++] = BankTemplate.MAIN_TAB;
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

	// The bank's "+" (new tab) button, used to guide creating a tab the template needs but you don't have.
	private Widget addTabButton()
	{
		final Widget tabs = client.getWidget(InterfaceID.Bankmain.TABS);
		if (tabs == null)
		{
			return null;
		}
		for (Widget[] group : new Widget[][]{tabs.getDynamicChildren(), tabs.getStaticChildren(), tabs.getNestedChildren()})
		{
			if (group == null)
			{
				continue;
			}
			for (Widget c : group)
			{
				if (c != null && c.getSpriteId() == SpriteID.BanktabIcons.ADD)
				{
					return c;
				}
			}
		}
		return null;
	}

	private static int[] permutation(List<Integer> current, List<Integer> target)
	{
		// Map each value to its target slots in order, so repeated (functionally-identical) items are
		// matched up one-to-one instead of all collapsing onto the same index.
		final Map<Integer, Deque<Integer>> slots = new HashMap<>();
		for (int k = 0; k < target.size(); k++)
		{
			slots.computeIfAbsent(target.get(k), x -> new ArrayDeque<>()).add(k);
		}
		final int[] perm = new int[current.size()];
		for (int k = 0; k < current.size(); k++)
		{
			final Deque<Integer> q = slots.get(current.get(k));
			perm[k] = q != null && !q.isEmpty() ? q.poll() : k;
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

	// Marks the indices of `perm` that lie on ONE longest strictly-increasing subsequence: the items already
	// in the correct relative order, which an optimal insert sort leaves in place while moving everything else
	// into the gaps between them. n - (kept count) is therefore the minimum number of insert-drags to sort.
	// perm is a true permutation of 0..n-1 (each item maps to a distinct target slot), so ties never arise.
	private static boolean[] lisKeep(int[] perm)
	{
		final int n = perm.length;
		final boolean[] keep = new boolean[n];
		if (n == 0)
		{
			return keep;
		}
		final int[] tail = new int[n];   // tail[l] = index into perm of the smallest tail of a length-(l+1) run
		final int[] prev = new int[n];   // predecessor index, for reconstructing the run
		int len = 0;
		for (int i = 0; i < n; i++)
		{
			int lo = 0;
			int hi = len;
			while (lo < hi)
			{
				final int mid = (lo + hi) >>> 1;
				if (perm[tail[mid]] < perm[i])
				{
					lo = mid + 1;
				}
				else
				{
					hi = mid;
				}
			}
			prev[i] = lo > 0 ? tail[lo - 1] : -1;
			tail[lo] = i;
			if (lo == len)
			{
				len++;
			}
		}
		for (int k = tail[len - 1]; k >= 0; k = prev[k])
		{
			keep[k] = true;
		}
		return keep;
	}

	private void drawTag(Graphics2D g, Rectangle b, String text, Color color)
	{
		g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
		final FontMetrics fm = g.getFontMetrics();
		// Bottom-right of the slot, clear of the stack/quantity count the game draws at the top-left.
		final int x = b.x + b.width - fm.stringWidth(text) - 2;
		final int y = b.y + b.height - fm.getDescent() - 1;
		g.setColor(color);
		g.drawString(text, x, y);
	}

	// A small green tick in the slot's bottom-right, confirming an item is already in its correct slot.
	private void drawCheckTag(Graphics2D g, Rectangle b)
	{
		final float bx = b.x + b.width - 11f;
		final float by = b.y + b.height - 9f;
		final GeneralPath p = new GeneralPath();
		p.moveTo(bx, by + 4f);
		p.lineTo(bx + 3f, by + 7f);
		p.lineTo(bx + 9f, by);
		final Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		final Stroke old = g.getStroke();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(80, 210, 90));
		g.draw(p);
		g.setStroke(old);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			aa != null ? aa : RenderingHints.VALUE_ANTIALIAS_DEFAULT);
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

	// Outlines the current step's source item (clipped to the items window) and records it so the bank can
	// auto-scroll it into view when the step changes.
	private void markSource(Graphics2D g, Widget itemContainer, Rectangle b)
	{
		stepItemBounds = b;
		outlineInContainer(g, itemContainer, b, SOURCE_COLOR);
	}

	// Scrolls the bank item container so the given item (screen bounds) is fully visible.
	private void scrollItemIntoView(Widget itemContainer, Rectangle item)
	{
		if (itemContainer == null || item == null || item.height <= 0)
		{
			return;
		}
		final int relY = item.y - itemContainer.getBounds().y;
		final int viewH = itemContainer.getHeight();
		final int scroll = itemContainer.getScrollY();
		int newScroll = scroll;
		if (relY < 0)
		{
			newScroll = scroll + relY;
		}
		else if (relY + item.height > viewH)
		{
			newScroll = scroll + (relY + item.height - viewH);
		}
		final int max = Math.max(0, itemContainer.getScrollHeight() - viewH);
		newScroll = Math.max(0, Math.min(newScroll, max));
		if (newScroll != scroll)
		{
			itemContainer.setScrollY(newScroll);
			itemContainer.revalidateScroll();
			client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Bankmain.SCROLLBAR, InterfaceID.Bankmain.ITEMS, newScroll);
		}
	}

	// Like outline(), but clipped to the item container so a highlight for an item scrolled out of the bank
	// view is clipped to the items window instead of rendering over the buttons below it.
	private void outlineInContainer(Graphics2D g, Widget itemContainer, Rectangle b, Color c)
	{
		final Shape old = g.getClip();
		g.setClip(itemContainer.getBounds());
		outline(g, b, c);
		g.setClip(old);
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

	// Shows a step instruction in the bank's real title bar (with an optional second clause), instead of a
	// floating box. Set each frame; the bank restores its own title when the reorg helper is switched off.
	private void setBankTitle(String line, Color color, String warn, boolean warnActive)
	{
		final Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (title == null)
		{
			return;
		}
		String text = "<col=" + hex(color) + ">" + line + "</col>";
		if (warn != null)
		{
			text += "  <col=" + hex(warnActive ? SOURCE_COLOR : Color.LIGHT_GRAY) + ">" + warn + "</col>";
		}
		title.setText(text);
	}

	private static String hex(Color c)
	{
		return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

}
