package com.banktemplates;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.Map;

/**
 * Paints a template Browse card's themed background from the uploader's profile_bg key - the
 * Swing translation of the website's pbg-* cards: a 135-degree dark base gradient, a soft
 * accent glow in the top-right corner, and a matching border. Unknown/absent keys fall back
 * to the neutral default so a card always renders.
 */
final class ProfileCardStyle
{
	private static final class Style
	{
		final Color top;
		final Color bottom;
		final Color glow;   // null = no accent glow (default)
		final Color border;

		Style(Color top, Color bottom, Color glow, Color border)
		{
			this.top = top;
			this.bottom = bottom;
			this.glow = glow;
			this.border = border;
		}
	}

	private static final Style DEFAULT = new Style(
		new Color(0x22, 0x22, 0x22), new Color(0x16, 0x16, 0x16), null, new Color(205, 127, 50, 130));

	private static final Map<String, Style> STYLES = new HashMap<>();

	static
	{
		STYLES.put("ember", new Style(new Color(0x24, 0x13, 0x10), new Color(0x17, 0x11, 0x0e), new Color(255, 120, 60, 76), new Color(255, 120, 60, 71)));
		STYLES.put("frost", new Style(new Color(0x10, 0x19, 0x2a), new Color(0x0e, 0x13, 0x19), new Color(90, 170, 255, 76), new Color(90, 170, 255, 71)));
		STYLES.put("verdant", new Style(new Color(0x10, 0x24, 0x1a), new Color(0x0e, 0x17, 0x12), new Color(90, 214, 122, 71), new Color(90, 214, 122, 66)));
		STYLES.put("royal", new Style(new Color(0x1b, 0x12, 0x30), new Color(0x12, 0x0e, 0x1a), new Color(160, 107, 255, 76), new Color(160, 107, 255, 71)));
		STYLES.put("gilded", new Style(new Color(0x2a, 0x22, 0x13), new Color(0x17, 0x14, 0x0d), new Color(246, 207, 116, 87), new Color(230, 179, 77, 102)));
		STYLES.put("abyssal", new Style(new Color(0x0c, 0x1a, 0x1c), new Color(0x0a, 0x0f, 0x10), new Color(60, 220, 200, 66), new Color(60, 220, 200, 66)));
		STYLES.put("blueprint", new Style(new Color(0x16, 0x1b, 0x24), new Color(0x0f, 0x12, 0x16), new Color(140, 165, 205, 61), new Color(140, 165, 205, 66)));
		STYLES.put("radiant", new Style(new Color(0x28, 0x15, 0x21), new Color(0x18, 0x0e, 0x15), new Color(255, 135, 170, 76), new Color(255, 135, 170, 76)));
	}

	private ProfileCardStyle()
	{
	}

	static Color border(String bgKey)
	{
		return STYLES.getOrDefault(bgKey == null ? "" : bgKey, DEFAULT).border;
	}

	/** Fill the rounded card background + glow, then stroke its border. Caller owns the Graphics. */
	static void paint(Graphics2D g2, int w, int h, int arc, String bgKey)
	{
		final Style s = STYLES.getOrDefault(bgKey == null ? "" : bgKey, DEFAULT);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		final RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc);
		final Shape oldClip = g2.getClip();
		g2.clip(shape);

		g2.setPaint(new GradientPaint(0, 0, s.top, w, h, s.bottom)); // 135deg base
		g2.fillRect(0, 0, w, h);

		if (s.glow != null)
		{
			final float r = Math.max(w, h) * 1.3f;
			final Color clear = new Color(s.glow.getRed(), s.glow.getGreen(), s.glow.getBlue(), 0);
			g2.setPaint(new RadialGradientPaint(new Point2D.Float(w, 0), r,
				new float[]{0f, 0.75f}, new Color[]{s.glow, clear}));
			g2.fillRect(0, 0, w, h);
		}

		g2.setClip(oldClip);
		g2.setColor(s.border);
		g2.draw(shape);
	}
}
