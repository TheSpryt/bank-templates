package com.banktemplates;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * An interactive mimic of the bank for previewing a {@link BankTemplate}: a row of tab buttons up
 * top and the item grid below, rendered at the template's designed column count. Clicking a tab
 * switches the grid, just like the real bank.
 */
final class TemplatePreview
{
	private static final int CELL = 36;
	// Minimum content width so preview windows are a consistent size regardless of description/columns.
	private static final int MIN_WIDTH = 320;

	private TemplatePreview()
	{
	}

	static JPanel build(ItemManager itemManager, ClientThread clientThread, BankTemplate template, String description)
	{
		final int columns = template.getColumns();
		final List<TabLayout> tabs = new ArrayList<>(template.getTabs());
		tabs.sort(Comparator.comparingInt(TabLayout::getTab));

		final JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel gridHolder = new JPanel(new BorderLayout());
		gridHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JScrollPane scroll = new JScrollPane(gridHolder,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		ThinScrollBarUI.style(scroll);

		// Tab buttons. WrapLayout so a bank with many tabs wraps onto further rows instead of being
		// clipped by the dialog width.
		final JPanel tabBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 2));
		tabBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final List<JButton> tabButtons = new ArrayList<>();
		for (int i = 0; i < tabs.size(); i++)
		{
			final TabLayout tab = tabs.get(i);
			final boolean isMain = tab.getTab() == BankTemplate.MAIN_TAB;
			// A bank tab's icon is its first item (that's how the game determines it).
			final int iconId = isMain ? 0 : firstItem(tab.getLayout());
			final JButton b = new JButton(isMain ? "Main" : iconId > 0 ? "" : Integer.toString(tab.getTab()));
			if (iconId > 0)
			{
				itemManager.getImage(iconId).addTo(b);
				b.setToolTipText("Tab " + tab.getTab());
			}
			b.setFocusPainted(false);
			b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			b.setForeground(Color.WHITE);
			b.setBorder(BorderFactory.createEmptyBorder(2, iconId > 0 ? 3 : 8, 2, iconId > 0 ? 3 : 8));
			b.setMargin(new Insets(0, 0, 0, 0));
			final int[] columnsRef = {columns};
			b.addActionListener(e ->
			{
				for (JButton other : tabButtons)
				{
					other.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
				b.setBackground(ColorScheme.BRAND_ORANGE);
				gridHolder.removeAll();
				gridHolder.add(grid(itemManager, clientThread, BankTemplate.toArray(tab.getLayout()), columnsRef[0]), BorderLayout.NORTH);
				gridHolder.revalidate();
				gridHolder.repaint();
			});
			tabButtons.add(b);
			tabBar.add(b);
		}

		// Width that fits both the grid and the tab row on one line (so tabs default to one row, and wrap
		// only if the window is resized narrower), with a minimum so the window size is consistent whether
		// or not there's a description.
		final int contentWidth = Math.max(Math.max(columns * CELL + 28, tabBar.getPreferredSize().width + 12), MIN_WIDTH);
		root.setPreferredSize(new Dimension(contentWidth, 420));

		final JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		if (description != null && !description.isEmpty())
		{
			final JLabel desc = new JLabel("<html><body style='width:" + (contentWidth - 24) + "px'>"
				+ escape(description) + "</body></html>");
			desc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			desc.setAlignmentX(Component.LEFT_ALIGNMENT);
			desc.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			top.add(desc);
		}
		tabBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(tabBar);

		root.add(top, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);

		// Select the first tab.
		if (!tabButtons.isEmpty())
		{
			tabButtons.get(0).doClick();
		}
		return root;
	}

	// Display name for a slot's item. MUST run on the client thread (getItemComposition asserts it);
	// use setItemTooltip from Swing code. Falls back to the id if unknown.
	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	static String itemName(ItemManager itemManager, int id)
	{
		try
		{
			final String name = itemManager.getItemComposition(id).getName();
			if (name != null && !name.isEmpty() && !"null".equals(name))
			{
				return name;
			}
		}
		catch (RuntimeException ignored)
		{
			// Unknown id (hand-edited template, or item this client version lacks) - fall through.
		}
		return "Item " + id;
	}

	// Sets a component's tooltip to an item's name, resolving the name on the client thread (required by
	// getItemComposition) and applying it back on the EDT. Safe to call from Swing code.
	static void setItemTooltip(JComponent comp, ItemManager itemManager, ClientThread clientThread, int id)
	{
		clientThread.invoke(() ->
		{
			final String name = itemName(itemManager, id);
			SwingUtilities.invokeLater(() -> comp.setToolTipText(name));
		});
	}

	// Fallback tab icon: the first real item in the tab.
	private static int firstItem(List<Integer> layout)
	{
		for (Integer v : layout)
		{
			if (v != null && v > 0 && v != BankTemplate.FILLER)
			{
				return v;
			}
		}
		return 0;
	}

	private static JPanel grid(ItemManager itemManager, ClientThread clientThread, int[] layout, int columns)
	{
		final int rows = Math.max(1, (layout.length + columns - 1) / columns);
		final JPanel grid = new JPanel(new GridLayout(rows, columns, 1, 1));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		for (int i = 0; i < rows * columns; i++)
		{
			final JLabel cell = new JLabel();
			cell.setPreferredSize(new Dimension(CELL, CELL - 4));
			cell.setHorizontalAlignment(JLabel.CENTER);

			final int id = i < layout.length ? layout[i] : BankTemplate.EMPTY;
			if (id > 0 || id == BankTemplate.FILLER)
			{
				final AsyncBufferedImage img = itemManager.getImage(id);
				img.addTo(cell);
				// Name on hover, so you can identify items in the preview without owning them.
				setItemTooltip(cell, itemManager, clientThread, id);
			}
			grid.add(cell);
		}
		return grid;
	}
}
