package com.banktemplates;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * A thin, borderless scrollbar with a rounded gold thumb and no arrow buttons or track, replacing Swing's
 * chunky default. Apply with {@link #style(JScrollPane)}.
 */
final class ThinScrollBarUI extends BasicScrollBarUI
{
	private static final int THICKNESS = 7;
	// The panel's icon gold, slightly translucent at rest and solid on hover.
	private static final Color THUMB = new Color(0xE8, 0xC0, 0x50, 170);
	private static final Color THUMB_HOVER = new Color(0xE8, 0xC0, 0x50, 255);

	static void style(JScrollPane scrollPane)
	{
		apply(scrollPane.getVerticalScrollBar());
		apply(scrollPane.getHorizontalScrollBar());
	}

	private static void apply(JScrollBar bar)
	{
		bar.setUI(new ThinScrollBarUI());
		bar.setOpaque(false);
		bar.setUnitIncrement(16);
		if (bar.getOrientation() == JScrollBar.VERTICAL)
		{
			bar.setPreferredSize(new Dimension(THICKNESS, bar.getPreferredSize().height));
		}
		else
		{
			bar.setPreferredSize(new Dimension(bar.getPreferredSize().width, THICKNESS));
		}
	}

	@Override
	protected void configureScrollBarColors()
	{
		trackColor = new Color(0, 0, 0, 0);
	}

	@Override
	protected JButton createDecreaseButton(int orientation)
	{
		return zeroButton();
	}

	@Override
	protected JButton createIncreaseButton(int orientation)
	{
		return zeroButton();
	}

	private JButton zeroButton()
	{
		final JButton button = new JButton();
		final Dimension zero = new Dimension(0, 0);
		button.setPreferredSize(zero);
		button.setMinimumSize(zero);
		button.setMaximumSize(zero);
		return button;
	}

	@Override
	protected void paintTrack(Graphics g, JComponent c, Rectangle bounds)
	{
		// Transparent track.
	}

	@Override
	protected void paintThumb(Graphics g, JComponent c, Rectangle bounds)
	{
		if (bounds.isEmpty() || !scrollbar.isEnabled())
		{
			return;
		}
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(isThumbRollover() ? THUMB_HOVER : THUMB);
		final int pad = 1;
		final int arc = Math.min(bounds.width, bounds.height);
		g2.fillRoundRect(bounds.x + pad, bounds.y + pad, bounds.width - pad * 2, bounds.height - pad * 2, arc, arc);
		g2.dispose();
	}
}
