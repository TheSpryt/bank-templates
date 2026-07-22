package com.banktemplates;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.border.Border;

/**
 * A rounded outline for the panel's inputs, matching the template cards' corner radius so the whole panel
 * reads as one family. It only STROKES the outline - a border paints after the component's own content, so
 * filling here would cover the text. Components that want a rounded body stay non-opaque and paint the fill
 * themselves in paintComponent via {@link #fill}.
 */
final class RoundedBorder implements Border
{
	/** Corner radius shared by the cards (ProfileCardStyle) and every input. */
	static final int ARC = 10;

	private final Color line;
	private final Insets insets;

	RoundedBorder(Color line, Insets insets)
	{
		this.line = line;
		this.insets = insets;
	}

	/** Fills a non-opaque component's rounded body. Call from paintComponent before painting content. */
	static void fill(Graphics g, Component c, Color bg)
	{
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(bg);
		g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, c.getWidth() - 1f, c.getHeight() - 1f, ARC, ARC));
		g2.dispose();
	}

	@Override
	public void paintBorder(Component c, Graphics g, int x, int y, int w, int h)
	{
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(line);
		g2.setStroke(new BasicStroke(1f));
		g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, ARC, ARC));
		g2.dispose();
	}

	@Override
	public Insets getBorderInsets(Component c)
	{
		return (Insets) insets.clone();
	}

	@Override
	public boolean isBorderOpaque()
	{
		return false;
	}
}
