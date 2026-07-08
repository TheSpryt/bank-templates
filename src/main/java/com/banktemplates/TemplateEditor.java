package com.banktemplates;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Interactive side-panel layout editor: a bank-like grid for the selected tab where you can search and
 * add items (as faded placeholders you don't need to own), then drag to rearrange, swap or insert, and
 * mark filler/empty slots. Every change writes through {@link LayoutEditor}, so it shows live over the
 * bank too. Opened as a non-modal window so the game stays usable.
 */
final class TemplateEditor
{
	private static final int CELL = 38;
	private static final Border CELL_BORDER = BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR);
	// Client property on each tab button holding its tab number; the "+" (new tab) button uses NEW_TAB.
	private static final String TAB_PROP = "bt.tab";
	private static final Integer NEW_TAB = Integer.MIN_VALUE;

	// Template names cap at this length (matches the panel's capture/new-layout naming).
	private static final int MAX_NAME_LENGTH = 25;

	private final ItemManager itemManager;
	private final ItemIndex itemIndex;
	private final ClientThread clientThread;
	private final LayoutEditor editor;
	private final TemplateManager templateManager;
	private final BankTemplate template;
	// Run after a successful Apply (used to offer pushing changes to the shared copy). May be null.
	private final Runnable onApplied;

	private final JPanel gridHolder = new JPanel(new BorderLayout());
	private final JPanel tabBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 2));
	private final List<JLabel> cells = new ArrayList<>();
	// The item id currently shown in each cell, so an edit can refresh only the cells that actually changed.
	private int[] cellIds = new int[0];

	private int tab;
	private boolean swapMode = false;
	private Runnable listener;
	private JDialog dialog;
	private JTextField nameField;
	private JTextArea descArea;
	private final DragGlass glass = new DragGlass();

	private TemplateEditor(ItemManager itemManager, ItemIndex itemIndex, ClientThread clientThread, LayoutEditor editor,
		TemplateManager templateManager, BankTemplate template, Runnable onApplied)
	{
		this.itemManager = itemManager;
		this.itemIndex = itemIndex;
		this.clientThread = clientThread;
		this.editor = editor;
		this.templateManager = templateManager;
		this.template = template;
		this.onApplied = onApplied;
		this.tab = template.definedTabs().isEmpty() ? BankTemplate.MAIN_TAB : template.definedTabs().get(0);
	}

	/**
	 * Starts an edit session on {@code template} (if not already) and opens the editor window beside
	 * {@code parent}. {@code onApplied} runs after a successful Apply (e.g. to offer pushing the changes
	 * to the shared copy); it may be null.
	 */
	static void open(Component parent, ItemManager itemManager, ItemIndex itemIndex, ClientThread clientThread,
		LayoutEditor editor, TemplateManager templateManager, BankTemplate template, Runnable onApplied)
	{
		if (!editor.isEditing(template) && !editor.start(template))
		{
			return;
		}
		new TemplateEditor(itemManager, itemIndex, clientThread, editor, templateManager, template, onApplied).show(parent);
	}

	private void show(Component parent)
	{
		final Window owner = SwingUtilities.getWindowAncestor(parent);
		dialog = new JDialog(owner, "Edit: " + template.getName(), Dialog.ModalityType.MODELESS);
		// Intercept the close so we can confirm discarding unsaved changes before disposing.
		dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		dialog.setGlassPane(glass);

		final JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		root.setPreferredSize(new Dimension(template.getColumns() * CELL + 36, 460));

		tabBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gridHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JScrollPane scroll = new JScrollPane(gridHolder,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		ThinScrollBarUI.style(scroll);
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		final JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel hint = buildHint();
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(hint);
		top.add(Box.createVerticalStrut(4));

		// Editable name, saved on Apply. The name is also the template's storage key, so Apply renames the
		// on-disk file too (Cancel/close leaves it unchanged).
		final JLabel nameLabel = new JLabel("Name:");
		nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(nameLabel);
		top.add(Box.createVerticalStrut(2));

		nameField = new JTextField(template.getName() == null ? "" : template.getName());
		nameField.setFont(FontManager.getRunescapeSmallFont());
		nameField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nameField.setForeground(Color.WHITE);
		nameField.setCaretColor(Color.WHITE);
		nameField.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
		nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameField.getPreferredSize().height));
		nameField.setToolTipText("Rename this template (up to " + MAX_NAME_LENGTH + " characters). Saved on Apply.");
		top.add(nameField);
		top.add(Box.createVerticalStrut(4));

		// Editable, multi-line description, saved when Apply is clicked (Cancel/close leaves it unchanged).
		final JLabel descLabel = new JLabel("Description:");
		descLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		descLabel.setFont(FontManager.getRunescapeSmallFont());
		descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(descLabel);
		top.add(Box.createVerticalStrut(2));

		descArea = new JTextArea(template.getDescription() == null ? "" : template.getDescription(), 3, 0);
		descArea.setLineWrap(true);
		descArea.setWrapStyleWord(true);
		descArea.setFont(FontManager.getRunescapeSmallFont());
		descArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		descArea.setForeground(Color.WHITE);
		descArea.setCaretColor(Color.WHITE);
		descArea.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		descArea.setToolTipText("Shown to others when you share this template (up to 500 characters). Saved on Apply.");

		final JScrollPane descScroll = new JScrollPane(descArea,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		descScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		descScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		descScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, descScroll.getPreferredSize().height));
		top.add(descScroll);
		top.add(Box.createVerticalStrut(4));

		tabBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(tabBar);

		root.add(top, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);
		root.add(buildToolbar(parent), BorderLayout.SOUTH);

		// Refresh whenever the layout changes (including from in-bank edits); close if the session ends
		// (e.g. the "Done" button on the side-panel card finished editing).
		listener = () -> SwingUtilities.invokeLater(() ->
		{
			if (!editor.isEditing(template))
			{
				dialog.dispose();
				return;
			}
			rebuildTabs();
			rebuildGrid();
		});
		editor.addListener(listener);

		dialog.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				attemptClose();
			}

			@Override
			public void windowClosed(WindowEvent e)
			{
				editor.removeListener(listener);
				// Closing WITHOUT Apply discards the session's changes (restores the pre-edit snapshot).
				if (editor.isEditing(template))
				{
					editor.discard();
				}
			}
		});

		rebuildTabs();
		rebuildGrid();

		// Widen so the tab row fits on one line by default (it still wraps if the window is resized narrower).
		final int tabsWidth = tabBar.getPreferredSize().width + 16;
		final int w = Math.max(root.getPreferredSize().width, Math.max(tabsWidth, 320));
		root.setPreferredSize(new Dimension(w, root.getPreferredSize().height));

		dialog.setContentPane(root);
		dialog.pack();
		if (owner != null)
		{
			final Rectangle screen = owner.getGraphicsConfiguration().getBounds();
			final Point loc = owner.getLocationOnScreen();
			int x = loc.x - dialog.getWidth() - 8;
			if (x < screen.x)
			{
				x = loc.x + owner.getWidth() + 8;
			}
			// Keep the whole window on the client's screen, so a maximised/fullscreen client can't push it
			// off-screen where it looks like nothing opened.
			x = Math.max(screen.x, Math.min(x, screen.x + screen.width - dialog.getWidth()));
			final int y = Math.max(screen.y, Math.min(loc.y, screen.y + screen.height - dialog.getHeight()));
			dialog.setLocation(x, y);
		}
		else
		{
			dialog.setLocationRelativeTo(null);
		}
		dialog.setVisible(true);
	}

	// Confirm before throwing away unsaved changes, then dispose (windowClosed does the actual discard).
	private void attemptClose()
	{
		if (editor.isEditing(template) && editor.hasUnsavedChanges())
		{
			final int choice = JOptionPane.showConfirmDialog(dialog,
				"Discard unsaved changes to \"" + template.getName() + "\"?",
				"Discard changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION)
			{
				return;
			}
		}
		dialog.dispose();
	}

	// Applies the (edited) name and description, then commits the layout. Cancel or closing leaves them as
	// they were. A name clash with a preset or another template is reported and Apply is held open.
	private void applyEdits()
	{
		String newName = nameField.getText().trim();
		if (newName.isEmpty())
		{
			JOptionPane.showMessageDialog(dialog, "Enter a name for this template.", "Name required", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (newName.length() > MAX_NAME_LENGTH)
		{
			newName = newName.substring(0, MAX_NAME_LENGTH).trim();
		}
		// Rename re-keys the template and moves its on-disk file; reject a clash rather than overwriting.
		if (!newName.equals(template.getName()) && !templateManager.renameTemplate(template, newName))
		{
			JOptionPane.showMessageDialog(dialog,
				"A template named \"" + newName + "\" already exists. Choose a different name.",
				"Name in use", JOptionPane.WARNING_MESSAGE);
			return;
		}

		String d = descArea.getText().trim();
		if (d.length() > 500)
		{
			d = d.substring(0, 500);
		}
		template.setDescription(d);
		editor.finish();

		// Once the editor has closed, optionally offer to push the changes to the shared copy.
		if (onApplied != null)
		{
			SwingUtilities.invokeLater(onApplied);
		}
	}

	private JLabel buildHint()
	{
		final JLabel hint = new JLabel("<html>Drag an item onto another slot to swap or insert. Right-click a "
			+ "slot for more. Apply to save; closing discards changes.</html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return hint;
	}

	private JPanel buildToolbar(Component parent)
	{
		final JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Left: the swap/insert toggle and the edit actions. WrapLayout flows onto a second row if narrow.
		final JPanel left = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
		left.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Swap/insert mode toggle: depressed (orange) = swap two slots; up = insert, shifting the rest.
		final JToggleButton swap = new JToggleButton("Swap mode", swapMode);
		swap.setFocusPainted(false);
		swap.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		swap.setToolTipText("Depressed: swap two slots. Up: insert - move a slot, shifting the rest down.");
		styleToggle(swap, swapMode);
		swap.addActionListener(e ->
		{
			swapMode = swap.isSelected();
			styleToggle(swap, swapMode);
		});
		left.add(swap);

		left.add(button("Add item", () -> ItemSearch.open(parent, itemIndex, id ->
		{
			// An item can only live in one bank tab. If the picked item is already in the layout, don't add a
			// duplicate: if it's in this same tab there's nothing to do, and if it's in another tab, offer to
			// move the original here instead. Otherwise add it normally.
			clientThread.invoke(() ->
			{
				final int[] loc = editor.find(id);
				if (loc == null)
				{
					editor.addItem(tab, id);
					return;
				}
				final String name = editor.displayName(id);
				if (loc[0] == tab)
				{
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(dialog,
						name + " is already in this tab.", "Already in tab", JOptionPane.INFORMATION_MESSAGE));
					return;
				}
				final String from = editor.tabLabel(loc[0]);
				SwingUtilities.invokeLater(() ->
				{
					final int choice = JOptionPane.showConfirmDialog(dialog,
						name + " is already in " + from + ". Move it to this tab?",
						"Move item", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
					if (choice == JOptionPane.OK_OPTION)
					{
						clientThread.invoke(() -> editor.moveToTab(loc[0], loc[1], tab));
					}
				});
			});
		})));
		left.add(button("Add filler", () -> editor.addItem(tab, BankTemplate.FILLER)));
		left.add(button("Add row", () -> editor.addRow(tab)));
		left.add(button("Clear tab", () -> editor.clearTab(tab)));
		left.add(button("Revert", editor::revert));
		bar.add(left, BorderLayout.CENTER);

		// Right: Apply / Cancel pinned to the bottom-right corner. Apply commits; Cancel (or X) discards.
		final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
		right.setBackground(ColorScheme.DARK_GRAY_COLOR);
		right.add(button("Cancel", this::attemptClose));
		final JButton apply = button("Apply", this::applyEdits);
		apply.setBackground(new Color(60, 110, 60));
		right.add(apply);
		bar.add(right, BorderLayout.EAST);

		return bar;
	}

	private static void styleToggle(JToggleButton b, boolean on)
	{
		b.setBackground(on ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_HOVER_COLOR);
		b.setForeground(on ? Color.BLACK : Color.WHITE);
	}

	private JButton button(String text, Runnable action)
	{
		final JButton b = new JButton(text);
		b.setFocusPainted(false);
		b.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		b.setForeground(Color.WHITE);
		b.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		b.addActionListener(e -> action.run());
		return b;
	}

	private void rebuildTabs()
	{
		tabBar.removeAll();
		final List<Integer> defined = template.definedTabs();
		if (!defined.contains(tab))
		{
			tab = defined.isEmpty() ? BankTemplate.MAIN_TAB : defined.get(0);
		}
		for (int t : defined)
		{
			tabBar.add(tabButton(t == BankTemplate.MAIN_TAB ? "Main" : "Tab " + t, t, t == tab));
		}
		final int next = nextFreeTab(defined);
		if (next > 0)
		{
			final JButton add = button("+ Tab", () ->
			{
				editor.addRow(next); // creates the tab and gives it a starting row
				tab = next;
				rebuildTabs();
				rebuildGrid();
			});
			add.putClientProperty(TAB_PROP, NEW_TAB);
			tabBar.add(add);
		}
		tabBar.revalidate();
		tabBar.repaint();
	}

	private JButton tabButton(String text, int t, boolean active)
	{
		// Numbered tabs show their icon (a custom one if chosen, else the tab's first item), like the real
		// bank; the main view keeps its text label. Empty numbered tabs fall back to the text label too.
		final int custom = t == BankTemplate.MAIN_TAB ? 0 : template.getTabIcon(t);
		final int iconId = t == BankTemplate.MAIN_TAB ? 0 : custom > 0 ? custom : firstItem(t);
		final JButton b = new JButton(iconId > 0 ? "" : text);
		b.putClientProperty(TAB_PROP, t);
		if (iconId > 0)
		{
			itemManager.getImage(iconId).addTo(b);
			TemplatePreview.setItemTooltip(b, itemManager, clientThread, iconId);
		}
		b.setFocusPainted(false);
		b.setBackground(active ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(active ? Color.BLACK : Color.WHITE);
		b.setBorder(BorderFactory.createEmptyBorder(2, iconId > 0 ? 3 : 8, 2, iconId > 0 ? 3 : 8));
		b.addActionListener(e ->
		{
			tab = t;
			rebuildTabs();
			rebuildGrid();
		});
		// Drag a numbered tab onto another tab to reorder them (the main view can't be moved). A plain
		// click still switches tabs (the action above); only a release over a different tab reorders.
		b.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e) || t == BankTemplate.MAIN_TAB || iconId <= 0)
				{
					return;
				}
				if (!glass.isVisible())
				{
					glass.start(itemManager.getImage(iconId));
				}
				glass.moveTo(SwingUtilities.convertPoint(b, e.getPoint(), glass));
			}
		});
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeTabIconPopup(e, t);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				glass.stop();
				if (maybeTabIconPopup(e, t) || t == BankTemplate.MAIN_TAB)
				{
					return;
				}
				final Integer dest = tabDropTarget(b, e.getPoint());
				if (dest != null && !dest.equals(NEW_TAB) && dest != BankTemplate.MAIN_TAB && dest != t)
				{
					final int newNum = editor.moveTab(t, dest);
					if (newNum > 0)
					{
						tab = newNum;
						rebuildTabs();
						rebuildGrid();
					}
				}
			}
		});
		return b;
	}

	// Right-click a numbered tab -> pick a custom icon for it, or reset to using its first item.
	private boolean maybeTabIconPopup(MouseEvent e, int t)
	{
		if (!e.isPopupTrigger() || t == BankTemplate.MAIN_TAB)
		{
			return false;
		}
		final JPopupMenu menu = new JPopupMenu();
		menu.add(item("Set custom icon...", () ->
			ItemSearch.open(dialog, itemIndex, id -> editor.setTabIcon(t, id), true)));
		if (template.getTabIcon(t) > 0)
		{
			menu.add(item("Use first item", () -> editor.setTabIcon(t, 0)));
		}
		menu.show(e.getComponent(), e.getX(), e.getY());
		return true;
	}

	// The icon for a tab is its first real item (filler/empty slots are skipped), or 0 if none.
	private int firstItem(int t)
	{
		for (Integer v : template.copyTab(t))
		{
			if (v != null && v > 0 && v != BankTemplate.FILLER)
			{
				return v;
			}
		}
		return 0;
	}

	private static int nextFreeTab(List<Integer> defined)
	{
		for (int t = 1; t <= 9; t++)
		{
			if (!defined.contains(t))
			{
				return t;
			}
		}
		return -1;
	}

	private void rebuildGrid()
	{
		final List<Integer> slots = template.copyTab(tab);
		final int columns = template.getColumns();
		// Pad to a full last row so there are obvious empty drop targets.
		final int rows = Math.max(1, (slots.size() + columns) / columns);
		final int total = rows * columns;

		// If the grid shape hasn't changed (e.g. a swap or a move), update the existing cells in place
		// instead of tearing the whole grid down and rebuilding it - that teardown is what made every
		// icon visibly reload. Only cells whose item actually changed get their icon/tooltip refreshed;
		// the rest just get their selection highlight re-applied.
		if (cells.size() == total && cellIds.length == total)
		{
			for (int i = 0; i < total; i++)
			{
				final int id = i < slots.size() ? slots.get(i) : BankTemplate.EMPTY;
				if (cellIds[i] != id)
				{
					setCellContent(cells.get(i), id);
					cellIds[i] = id;
				}
			}
			return;
		}

		cells.clear();
		cellIds = new int[total];

		final JPanel grid = new JPanel(new GridLayout(rows, columns, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		for (int i = 0; i < total; i++)
		{
			final int id = i < slots.size() ? slots.get(i) : BankTemplate.EMPTY;
			final JLabel cell = makeCell(i, id, grid);
			cells.add(cell);
			cellIds[i] = id;
			grid.add(cell);
		}

		gridHolder.removeAll();
		gridHolder.add(grid, BorderLayout.NORTH);
		gridHolder.revalidate();
		gridHolder.repaint();
	}

	private void styleCell(JLabel cell)
	{
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(CELL_BORDER);
	}

	// Sets the styling, icon and tooltip for a cell. Used both when building a cell and when refreshing
	// one in place after an edit, so a swap only touches the cells whose item actually changed.
	private void setCellContent(JLabel cell, int id)
	{
		styleCell(cell);
		cell.setIcon(null);
		cell.setToolTipText(null);
		if (id > 0 || id == BankTemplate.FILLER)
		{
			final AsyncBufferedImage img = itemManager.getImage(id);
			if (img != null)
			{
				img.addTo(cell);
			}
			TemplatePreview.setItemTooltip(cell, itemManager, clientThread, id);
		}
	}

	private JLabel makeCell(int index, int id, JPanel grid)
	{
		final JLabel cell = new JLabel();
		cell.setOpaque(true);
		cell.setPreferredSize(new Dimension(CELL, CELL));
		cell.setHorizontalAlignment(JLabel.CENTER);
		setCellContent(cell, id);

		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybePopup(e, index);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				glass.stop();
				if (maybePopup(e, index))
				{
					return;
				}
				// Dropped on a tab button -> move this item to that tab's end (or onto + for a new tab),
				// like dragging onto a tab over the real bank.
				final Integer destTab = tabDropTarget(cell, e.getPoint());
				if (destTab != null)
				{
					if (destTab.equals(NEW_TAB))
					{
						editor.moveToNewTab(tab, index);
					}
					else if (destTab != tab)
					{
						editor.moveToTab(tab, index, destTab);
					}
					return;
				}
				// Drag: pressed on this cell, released over another -> move/swap.
				final int target = cellAt(grid, SwingUtilities.convertPoint(cell, e.getPoint(), grid));
				if (target >= 0 && target != index)
				{
					apply(index, target);
				}
			}
		});

		// Show a ghost of the item under the cursor while dragging, so you can see what you're moving.
		if (id > 0 || id == BankTemplate.FILLER)
		{
			cell.addMouseMotionListener(new MouseMotionAdapter()
			{
				@Override
				public void mouseDragged(MouseEvent e)
				{
					if (!SwingUtilities.isLeftMouseButton(e))
					{
						return;
					}
					if (!glass.isVisible())
					{
						glass.start(itemManager.getImage(id));
					}
					glass.moveTo(SwingUtilities.convertPoint(cell, e.getPoint(), glass));
				}
			});
		}
		return cell;
	}

	// A transparent glass-pane layer that paints the dragged item image at the cursor.
	private static final class DragGlass extends javax.swing.JComponent
	{
		private transient Image image;
		private Point pos;

		DragGlass()
		{
			setVisible(false);
		}

		void start(Image image)
		{
			this.image = image;
			this.pos = null;
		}

		void moveTo(Point p)
		{
			this.pos = p;
			if (image != null)
			{
				setVisible(true);
				repaint();
			}
		}

		void stop()
		{
			image = null;
			pos = null;
			setVisible(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (image != null && pos != null)
			{
				g.drawImage(image, pos.x - image.getWidth(null) / 2, pos.y - image.getHeight(null) / 2, null);
			}
		}
	}

	// Right-click slot menu.
	private boolean maybePopup(MouseEvent e, int index)
	{
		if (!e.isPopupTrigger())
		{
			return false;
		}
		final JPopupMenu menu = new JPopupMenu();
		// Only real items can be "replaced"; empty/filler slots have nothing to swap out.
		final int current = index < cellIds.length ? cellIds[index] : BankTemplate.EMPTY;
		if (current > 0)
		{
			menu.add(item("Replace", () -> openReplace(e.getComponent(), index)));
		}
		// "Release" matches the in-bank right-click wording: removeSlot shifts the rest up to fill the gap.
		menu.add(item("Release", () -> editor.removeSlot(tab, index)));
		menu.add(item("Set Bank Filler", () -> editor.setSlot(tab, index, BankTemplate.FILLER)));
		menu.add(item("Set empty", () -> editor.setSlot(tab, index, BankTemplate.EMPTY)));
		menu.add(item("Insert empty before", () -> editor.insertEmpty(tab, index)));
		menu.show(e.getComponent(), e.getX(), e.getY());
		return true;
	}

	// Bring up the Add-item search box and swap the chosen item into this slot. The picker closes after
	// the pick, and a duplicate (the item is already elsewhere in the layout) is reported, not applied.
	private void openReplace(Component near, int index)
	{
		ItemSearch.open(near, itemIndex, id ->
			clientThread.invoke(() -> editor.replaceItemOrReport(tab, index, id, msg ->
				SwingUtilities.invokeLater(() ->
					JOptionPane.showMessageDialog(dialog, msg, "Already in layout", JOptionPane.INFORMATION_MESSAGE)))),
			true);
	}

	private JMenuItem item(String text, Runnable action)
	{
		final JMenuItem mi = new JMenuItem(text);
		mi.addActionListener(e ->
		{
			action.run();
		});
		return mi;
	}

	private void apply(int from, int to)
	{
		if (swapMode)
		{
			editor.swapSlots(tab, from, to);
		}
		else
		{
			editor.moveSlot(tab, from, to);
		}
	}

	// Slot index of the grid cell under a point (in grid coordinates), or -1.
	private int cellAt(JPanel grid, Point p)
	{
		final Component c = grid.getComponentAt(p);
		if (c instanceof JLabel)
		{
			return cells.indexOf(c);
		}
		return -1;
	}

	// The tab a point (in {@code src}'s coordinates) is over in the tab bar: a tab number, NEW_TAB for the
	// "+" button, or null if the point isn't over a tab button.
	private Integer tabDropTarget(Component src, Point p)
	{
		final Point inBar = SwingUtilities.convertPoint(src, p, tabBar);
		if (inBar.x < 0 || inBar.y < 0 || inBar.x >= tabBar.getWidth() || inBar.y >= tabBar.getHeight())
		{
			return null;
		}
		final Component c = tabBar.getComponentAt(inBar);
		if (c instanceof JComponent)
		{
			final Object t = ((JComponent) c).getClientProperty(TAB_PROP);
			if (t instanceof Integer)
			{
				return (Integer) t;
			}
		}
		return null;
	}
}
