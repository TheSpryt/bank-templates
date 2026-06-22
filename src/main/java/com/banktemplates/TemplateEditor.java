package com.banktemplates;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
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
	private static final Border SELECTED_BORDER = BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2);

	private final ItemManager itemManager;
	private final LayoutEditor editor;
	private final BankTemplate template;

	private final JPanel gridHolder = new JPanel(new BorderLayout());
	private final JPanel tabBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 2));
	private final List<JLabel> cells = new ArrayList<>();

	private int tab;
	private int selected = -1;
	private boolean swapMode = false;
	private Runnable listener;

	private TemplateEditor(ItemManager itemManager, LayoutEditor editor, BankTemplate template)
	{
		this.itemManager = itemManager;
		this.editor = editor;
		this.template = template;
		this.tab = template.definedTabs().isEmpty() ? BankTemplate.MAIN_TAB : template.definedTabs().get(0);
	}

	/**
	 * Starts an edit session on {@code template} (if not already) and opens the editor window beside
	 * {@code parent}.
	 */
	static void open(Component parent, ItemManager itemManager, LayoutEditor editor, BankTemplate template)
	{
		if (!editor.isEditing(template) && !editor.start(template))
		{
			return;
		}
		new TemplateEditor(itemManager, editor, template).show(parent);
	}

	private void show(Component parent)
	{
		final Window owner = SwingUtilities.getWindowAncestor(parent);
		final JDialog dialog = new JDialog(owner, "Edit: " + template.getName(), Dialog.ModalityType.MODELESS);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

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
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		final JPanel top = new JPanel(new BorderLayout(0, 4));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(buildHint(), BorderLayout.NORTH);
		top.add(tabBar, BorderLayout.CENTER);

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
			public void windowClosed(WindowEvent e)
			{
				editor.removeListener(listener);
				// Closing the editor ends the edit session (also turns off in-bank editing).
				if (editor.isEditing(template))
				{
					editor.finish();
				}
			}
		});

		rebuildTabs();
		rebuildGrid();

		dialog.setContentPane(root);
		dialog.pack();
		if (owner != null)
		{
			final Point loc = owner.getLocationOnScreen();
			int x = loc.x - dialog.getWidth() - 8;
			if (x < 0)
			{
				x = loc.x + owner.getWidth() + 8;
			}
			dialog.setLocation(Math.max(0, x), Math.max(0, loc.y));
		}
		else
		{
			dialog.setLocationRelativeTo(null);
		}
		dialog.setVisible(true);
	}

	private JLabel buildHint()
	{
		final JLabel hint = new JLabel("<html>Click to select, click again to move here. "
			+ "Drag to rearrange. Right-click a slot for more.</html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return hint;
	}

	private JPanel buildToolbar(Component parent)
	{
		final JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);

		bar.add(button("Add item", () -> ItemSearch.open(parent, itemManager, id ->
		{
			editor.addItem(tab, id);
			selected = -1;
		})));
		bar.add(button("Add filler", () -> editor.addItem(tab, BankTemplate.FILLER)));
		bar.add(button("Add row", () -> editor.addRow(tab)));
		bar.add(button("Clear tab", () -> editor.clearTab(tab)));
		bar.add(button("Revert", editor::revert));

		final JCheckBox swap = new JCheckBox("Swap mode");
		swap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		swap.setForeground(Color.WHITE);
		swap.setFocusPainted(false);
		swap.setToolTipText("On: clicking/dragging swaps two slots. Off: it moves a slot, shifting the rest.");
		swap.addActionListener(e -> swapMode = swap.isSelected());
		bar.add(swap);

		return bar;
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
			tabBar.add(add);
		}
		tabBar.revalidate();
		tabBar.repaint();
	}

	private JButton tabButton(String text, int t, boolean active)
	{
		final JButton b = new JButton(text);
		b.setFocusPainted(false);
		b.setBackground(active ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(active ? Color.BLACK : Color.WHITE);
		b.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		b.addActionListener(e ->
		{
			tab = t;
			selected = -1;
			rebuildTabs();
			rebuildGrid();
		});
		return b;
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
		cells.clear();
		final List<Integer> slots = template.copyTab(tab);
		final int columns = template.getColumns();
		// Pad to a full last row so there are obvious empty drop targets.
		final int rows = Math.max(1, (slots.size() + columns) / columns);
		final int total = rows * columns;

		final JPanel grid = new JPanel(new GridLayout(rows, columns, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		for (int i = 0; i < total; i++)
		{
			final int id = i < slots.size() ? slots.get(i) : BankTemplate.EMPTY;
			final JLabel cell = makeCell(i, id, grid);
			cells.add(cell);
			grid.add(cell);
		}

		gridHolder.removeAll();
		gridHolder.add(grid, BorderLayout.NORTH);
		gridHolder.revalidate();
		gridHolder.repaint();
	}

	private JLabel makeCell(int index, int id, JPanel grid)
	{
		final JLabel cell = new JLabel();
		cell.setOpaque(true);
		cell.setBackground(index == selected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(index == selected ? SELECTED_BORDER : CELL_BORDER);
		cell.setPreferredSize(new Dimension(CELL, CELL));
		cell.setHorizontalAlignment(JLabel.CENTER);

		if (id > 0 || id == BankTemplate.FILLER)
		{
			final AsyncBufferedImage img = itemManager.getImage(id);
			if (img != null)
			{
				img.addTo(cell);
			}
			cell.setToolTipText(TemplatePreview.itemName(itemManager, id));
		}

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
				if (maybePopup(e, index))
				{
					return;
				}
				// Drag: pressed on this cell, released over another -> move/swap.
				final int target = cellAt(grid, SwingUtilities.convertPoint(cell, e.getPoint(), grid));
				if (target >= 0 && target != index)
				{
					apply(index, target);
				}
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				// Click select, then click again elsewhere to move/swap into that slot.
				if (selected < 0)
				{
					selected = index;
					rebuildGrid();
				}
				else if (selected == index)
				{
					selected = -1;
					rebuildGrid();
				}
				else
				{
					apply(selected, index);
				}
			}
		});
		return cell;
	}

	// Right-click slot menu.
	private boolean maybePopup(MouseEvent e, int index)
	{
		if (!e.isPopupTrigger())
		{
			return false;
		}
		final JPopupMenu menu = new JPopupMenu();
		menu.add(item("Set filler", () -> editor.setSlot(tab, index, BankTemplate.FILLER)));
		menu.add(item("Set empty", () -> editor.setSlot(tab, index, BankTemplate.EMPTY)));
		menu.add(item("Insert empty before", () -> editor.insertEmpty(tab, index)));
		menu.add(item("Remove slot", () -> editor.removeSlot(tab, index)));
		menu.show(e.getComponent(), e.getX(), e.getY());
		return true;
	}

	private JMenuItem item(String text, Runnable action)
	{
		final JMenuItem mi = new JMenuItem(text);
		mi.addActionListener(e ->
		{
			selected = -1;
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
		selected = -1;
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
}
