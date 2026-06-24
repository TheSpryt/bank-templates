package com.banktemplates;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * A small non-modal "Add item" picker: type a name, click a result to add that item to the layout. It
 * stays open after each pick so several items can be added in a row. Adding an item never requires you
 * to own it - the editor places it as a faded placeholder.
 * <p>
 * Searches the full {@link ItemIndex} (every item by name, including untradeables), not just GE-priced
 * items, so things like Barrows gloves, Arclight and Emberlight can be added.
 */
final class ItemSearch
{
	private static final int MAX_RESULTS = 40;

	private ItemSearch()
	{
	}

	/**
	 * Opens the picker beside {@code parent}. {@code onPick} is called (on the EDT) with each chosen
	 * item id.
	 */
	static void open(Component parent, ItemIndex itemIndex, IntConsumer onPick)
	{
		final Window owner = SwingUtilities.getWindowAncestor(parent);
		final JDialog dialog = new JDialog(owner, "Add item", Dialog.ModalityType.MODELESS);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		final JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		root.setPreferredSize(new Dimension(260, 360));

		final net.runelite.client.ui.components.IconTextField field = new net.runelite.client.ui.components.IconTextField();
		field.setIcon(net.runelite.client.ui.components.IconTextField.Icon.SEARCH);
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		field.setPreferredSize(new Dimension(100, 30));

		final JPanel results = new JPanel();
		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JScrollPane scroll = new JScrollPane(results,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		final Runnable doSearch = () -> populate(itemIndex, field.getText(), results, scroll, onPick);
		field.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				doSearch.run();
			}
		});

		root.add(field, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);

		final JLabel hint = new JLabel("Type a name, then click an item to add it.");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		root.add(hint, BorderLayout.SOUTH);

		dialog.setContentPane(root);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);
		field.requestFocusInWindow();

		// Kick off the (lazy, one-time) item index build and refresh results once it's ready.
		itemIndex.ensureBuilt(doSearch);
	}

	private static void populate(ItemIndex itemIndex, String query, JPanel results, JScrollPane scroll, IntConsumer onPick)
	{
		results.removeAll();
		final String q = query == null ? "" : query.trim();
		if (q.length() >= 1)
		{
			if (!itemIndex.isReady())
			{
				results.add(message("Loading items..."));
			}
			else
			{
				final List<ItemIndex.Entry> matches = itemIndex.search(q, MAX_RESULTS);
				if (matches.isEmpty())
				{
					results.add(message("No items found."));
				}
				else
				{
					for (ItemIndex.Entry e : matches)
					{
						results.add(resultRow(itemIndex, e.getId(), e.getName(), onPick));
					}
				}
			}
		}
		results.revalidate();
		results.repaint();
		scroll.getVerticalScrollBar().setValue(0);
	}

	private static JButton resultRow(ItemIndex itemIndex, int id, String name, IntConsumer onPick)
	{
		final JButton row = new JButton(name);
		row.setHorizontalAlignment(JButton.LEFT);
		row.setFocusPainted(false);
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setForeground(Color.WHITE);
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		row.setToolTipText(name);

		final AsyncBufferedImage img = itemIndex.getImage(id);
		if (img != null)
		{
			img.addTo(row);
		}
		row.addActionListener(e -> onPick.accept(id));
		return row;
	}

	private static JLabel message(String text)
	{
		final JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}
}
