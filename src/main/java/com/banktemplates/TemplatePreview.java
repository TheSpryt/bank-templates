package com.banktemplates;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
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

	private TemplatePreview()
	{
	}

	static JPanel build(ItemManager itemManager, BankTemplate template)
	{
		final int columns = template.getColumns();
		final List<TabLayout> tabs = new ArrayList<>(template.getTabs());
		tabs.sort(Comparator.comparingInt(TabLayout::getTab));

		final JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setPreferredSize(new Dimension(columns * CELL + 28, 420));

		final JPanel gridHolder = new JPanel(new BorderLayout());
		gridHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JScrollPane scroll = new JScrollPane(gridHolder,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Tab buttons.
		final JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		tabBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final List<JButton> tabButtons = new ArrayList<>();
		for (int i = 0; i < tabs.size(); i++)
		{
			final TabLayout tab = tabs.get(i);
			final JButton b = new JButton(tab.getTab() == BankTemplate.MAIN_TAB ? "Main" : Integer.toString(tab.getTab()));
			b.setFocusPainted(false);
			b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			b.setForeground(Color.WHITE);
			b.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
			final int[] columnsRef = {columns};
			b.addActionListener(e ->
			{
				for (JButton other : tabButtons)
				{
					other.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
				b.setBackground(ColorScheme.BRAND_ORANGE);
				gridHolder.removeAll();
				gridHolder.add(grid(itemManager, BankTemplate.toArray(tab.getLayout()), columnsRef[0]), BorderLayout.NORTH);
				gridHolder.revalidate();
				gridHolder.repaint();
			});
			tabButtons.add(b);
			tabBar.add(b);
		}

		root.add(tabBar, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);

		// Select the first tab.
		if (!tabButtons.isEmpty())
		{
			tabButtons.get(0).doClick();
		}
		return root;
	}

	private static JPanel grid(ItemManager itemManager, int[] layout, int columns)
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
			}
			grid.add(cell);
		}
		return grid;
	}
}
