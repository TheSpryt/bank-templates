package com.banktemplates;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A self-styled search field: a magnifying-glass icon on the left, white text on a dark field, and a red
 * clear "x" that appears once you have typed something. Built directly rather than using the client's
 * IconTextField, whose flat text field renders unreadably in this plugin's panel context - here every
 * colour is under our control, so it stays readable and matches the rest of the client.
 */
final class SearchBar extends JPanel
{
	private final JTextField field = new JTextField();
	private final JButton clear = new JButton("×");
	private final List<Runnable> clearListeners = new ArrayList<>();

	SearchBar()
	{
		super(new BorderLayout(4, 0));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 6, 3, 4)));

		final JLabel icon = new JLabel(magnifier());
		icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
		add(icon, BorderLayout.WEST);

		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		field.setBorder(null);
		add(field, BorderLayout.CENTER);

		clear.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		clear.setFont(FontManager.getRunescapeBoldFont());
		clear.setBorder(null);
		clear.setContentAreaFilled(false);
		clear.setFocusPainted(false);
		clear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		clear.setToolTipText("Clear");
		clear.setVisible(false);
		clear.addActionListener(e ->
		{
			field.setText("");
			field.requestFocusInWindow();
			for (Runnable r : clearListeners)
			{
				r.run();
			}
		});
		add(clear, BorderLayout.EAST);

		field.getDocument().addDocumentListener(new DocumentListener()
		{
			private void update()
			{
				clear.setVisible(!field.getText().isEmpty());
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				update();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				update();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				update();
			}
		});
	}

	// A simple magnifying glass (lens + handle) drawn at runtime, so there's no bundled asset to ship.
	private static ImageIcon magnifier()
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xA7A7A7));
		g.setStroke(new BasicStroke(1.6f));
		g.drawOval(2, 2, 7, 7);
		g.drawLine(9, 9, 12, 12);
		g.dispose();
		return new ImageIcon(img);
	}

	String getText()
	{
		return field.getText();
	}

	void setText(String text)
	{
		field.setText(text);
	}

	@Override
	public synchronized void addKeyListener(KeyListener l)
	{
		field.addKeyListener(l);
	}

	void addActionListener(ActionListener l)
	{
		field.addActionListener(l);
	}

	/** Runs when the red clear button empties the field, so callers can refresh their results. */
	void addClearListener(Runnable r)
	{
		clearListeners.add(r);
	}

	@Override
	public boolean requestFocusInWindow()
	{
		return field.requestFocusInWindow();
	}
}
