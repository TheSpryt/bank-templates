package com.banktemplates;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/**
 * Small monochrome UI glyphs for the template-card action icons, drawn at runtime with Java2D so
 * there is no bundled asset to ship. Each takes the colour it should be drawn in.
 */
final class PanelIcons
{
	private PanelIcons()
	{
	}

	// Icons are immutable and redrawn constantly - every card rebuild asks for the same handful again, and
	// each call was allocating a fresh BufferedImage + Graphics2D on the EDT (a 24-card page meant ~120 of
	// them, on every sort, search keystroke and poll). Keyed by name + colour; Swing is happy for many
	// labels to share one Icon.
	private static final java.util.Map<String, ImageIcon> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	private static ImageIcon cached(String name, Color c, java.util.function.Function<Color, ImageIcon> draw)
	{
		return CACHE.computeIfAbsent(name + "#" + c.getRGB(), k -> draw.apply(c));
	}

	static ImageIcon magnifier(Color c)
	{
		return cached("magnifier", c, PanelIcons::drawMagnifier);
	}

	static ImageIcon globe(Color c)
	{
		return cached("globe", c, PanelIcons::drawGlobe);
	}

	static ImageIcon xMark(Color c)
	{
		return cached("xMark", c, PanelIcons::drawXMark);
	}

	static ImageIcon download(Color c)
	{
		return cached("download", c, PanelIcons::drawDownload);
	}

	static ImageIcon upload(Color c)
	{
		return cached("upload", c, PanelIcons::drawUpload);
	}

	static ImageIcon power(Color c)
	{
		return cached("power", c, PanelIcons::drawPower);
	}

	static ImageIcon flag(Color c)
	{
		return cached("flag", c, PanelIcons::drawFlag);
	}

	static ImageIcon pencil(Color c)
	{
		return cached("pencil", c, PanelIcons::drawPencil);
	}

	static ImageIcon profile(Color c)
	{
		return cached("profile", c, PanelIcons::drawProfile);
	}

	/** Double chevron pointing up or down - the expand/collapse indicator on the Reorganise card. */
	static ImageIcon chevron(Color c, boolean up)
	{
		return cached(up ? "chevronUp" : "chevronDown", c, col -> drawChevron(col, up));
	}

	private static Graphics2D canvas(BufferedImage img, Color c, float stroke)
	{
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(c);
		g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		return g;
	}

	/** Magnifying glass (lens + handle) - the "preview / view" action. */
	private static ImageIcon drawMagnifier(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.6f);
		g.drawOval(2, 2, 7, 7);
		g.drawLine(9, 9, 12, 12);
		g.dispose();
		return new ImageIcon(img);
	}

	/** Globe (outline + equator + a meridian) - the "open on the website" action. */
	private static ImageIcon drawGlobe(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.3f);
		g.drawOval(1, 1, 11, 11);                        // outline
		g.drawLine(1, 6, 12, 6);                         // equator
		g.draw(new Ellipse2D.Float(4f, 1f, 5f, 11f));    // meridian
		g.dispose();
		return new ImageIcon(img);
	}

	/** X mark - the "delete" action. */
	private static ImageIcon drawXMark(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.8f);
		g.drawLine(3, 3, 11, 11);
		g.drawLine(11, 3, 3, 11);
		g.dispose();
		return new ImageIcon(img);
	}

	/** Download tray (arrow into a baseline) - the "import a copy" action. */
	private static ImageIcon drawDownload(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.6f);
		g.drawLine(7, 1, 7, 9);      // shaft
		g.drawLine(4, 6, 7, 9);      // left arrowhead
		g.drawLine(10, 6, 7, 9);     // right arrowhead
		g.drawLine(3, 12, 11, 12);   // tray / baseline
		g.dispose();
		return new ImageIcon(img);
	}

	/** Upload (arrow rising out of a baseline) - the "share to the community" action. */
	private static ImageIcon drawUpload(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.6f);
		g.drawLine(7, 12, 7, 4);     // shaft
		g.drawLine(4, 7, 7, 4);      // left arrowhead
		g.drawLine(10, 7, 7, 4);     // right arrowhead
		g.drawLine(3, 2, 11, 2);     // rail the arrow leaves from
		g.dispose();
		return new ImageIcon(img);
	}

	/** Power symbol (ring with a top gap + stem) - the enable/disable toggle. Coloured by the caller
	 *  (green = click to enable, red = click to disable). */
	private static ImageIcon drawPower(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.7f);
		g.drawArc(2, 3, 10, 10, 65, 410 - 65 - 130);  // ring with a ~40deg gap centred at top
		g.drawLine(7, 1, 7, 7);                        // stem through the gap
		g.dispose();
		return new ImageIcon(img);
	}

	/** Flag on a pole - the report count / report action. */
	private static ImageIcon drawFlag(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.4f);
		g.drawLine(3, 1, 3, 13);   // pole
		g.drawRect(3, 2, 8, 5);    // flag body
		g.dispose();
		return new ImageIcon(img);
	}

	/** Head + shoulders - "open this uploader's Exchange Insights profile". */
	private static ImageIcon drawProfile(Color c)
	{
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.3f);
		g.drawOval(4, 1, 6, 6);                 // head
		g.drawArc(1, 8, 12, 11, 0, 180);        // shoulders
		g.dispose();
		return new ImageIcon(img);
	}

	/** Two stacked chevrons, so the indicator reads as "there is a panel here to open" rather than as a
	 *  single arrow that could be mistaken for a sort direction. */
	private static ImageIcon drawChevron(Color c, boolean up)
	{
		final BufferedImage img = new BufferedImage(16, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.9f);
		// Each chevron is drawn from its own baseline; flipping the apex is what turns it over.
		for (int i = 0; i < 2; i++)
		{
			final int base = 4 + i * 5;
			if (up)
			{
				g.drawLine(3, base + 3, 8, base);
				g.drawLine(8, base, 13, base + 3);
			}
			else
			{
				g.drawLine(3, base, 8, base + 3);
				g.drawLine(8, base + 3, 13, base);
			}
		}
		g.dispose();
		return new ImageIcon(img);
	}

	/** Pencil (slanted body + nib) - the "edit" state of the view/edit toggle. */
	private static ImageIcon drawPencil(Color c)
	{
		// Drawn as an actual OUTLINED pencil - two parallel edges for the barrel, a ferrule band, and a
		// triangular tip - rather than the single diagonal stroke it was, which just read as a slash.
		final BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas(img, c, 1.1f);
		// Barrel: a long thin rectangle rotated 45 degrees, running bottom-left to top-right.
		final java.awt.geom.Path2D.Float barrel = new java.awt.geom.Path2D.Float();
		barrel.moveTo(3.2f, 10.8f);   // tip
		barrel.lineTo(4.6f, 7.2f);    // left edge up
		barrel.lineTo(9.6f, 2.2f);    // to the top-left corner
		barrel.lineTo(11.8f, 4.4f);   // across the butt end
		barrel.lineTo(6.8f, 9.4f);    // right edge down
		barrel.closePath();           // back to the tip
		g.draw(barrel);
		g.drawLine(6, 4, 8, 6);       // ferrule band across the barrel
		// Solid graphite point, so the writing end reads dark and definite.
		final java.awt.geom.Path2D.Float nib = new java.awt.geom.Path2D.Float();
		nib.moveTo(3.2f, 10.8f);
		nib.lineTo(4.6f, 7.2f);
		nib.lineTo(6.8f, 9.4f);
		nib.closePath();
		g.fill(nib);
		g.dispose();
		return new ImageIcon(img);
	}
}
