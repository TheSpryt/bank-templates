package com.banktemplates;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} that actually reports the height of its wrapped rows. The stock FlowLayout
 * always reports a single-row preferred size, so when buttons wrap to a second row the parent doesn't
 * allocate enough height and the wrapped row gets clipped. (Standard WrapLayout pattern.)
 */
class WrapLayout extends FlowLayout
{
	WrapLayout(int align, int hgap, int vgap)
	{
		super(align, hgap, vgap);
	}

	@Override
	public Dimension preferredLayoutSize(Container target)
	{
		return layoutSize(target, true);
	}

	@Override
	public Dimension minimumLayoutSize(Container target)
	{
		final Dimension d = layoutSize(target, false);
		d.width -= (getHgap() + 1);
		return d;
	}

	private Dimension layoutSize(Container target, boolean preferred)
	{
		synchronized (target.getTreeLock())
		{
			// Measure against the target's width - but on the first layout pass it's still 0, which would make
			// us assume everything fits on one row and under-report the height (the parent then allocates too
			// little, so wrapped rows overlap or clip). Fall back to the nearest laid-out ancestor's width so
			// the row count - and thus the height - is right from the very first pass.
			int targetWidth = target.getSize().width;
			if (targetWidth == 0)
			{
				Container c = target.getParent();
				while (c != null && c.getSize().width == 0)
				{
					c = c.getParent();
				}
				targetWidth = c != null ? c.getSize().width : 0;
			}
			if (targetWidth == 0)
			{
				targetWidth = Integer.MAX_VALUE;
			}

			final int hgap = getHgap();
			final int vgap = getVgap();
			final Insets insets = target.getInsets();
			final int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
			final int maxWidth = targetWidth - horizontalInsetsAndGap;

			final Dimension dim = new Dimension(0, 0);
			int rowWidth = 0;
			int rowHeight = 0;

			for (int i = 0; i < target.getComponentCount(); i++)
			{
				final Component m = target.getComponent(i);
				if (!m.isVisible())
				{
					continue;
				}
				final Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
				if (rowWidth + d.width > maxWidth)
				{
					addRow(dim, rowWidth, rowHeight);
					rowWidth = 0;
					rowHeight = 0;
				}
				if (rowWidth != 0)
				{
					rowWidth += hgap;
				}
				rowWidth += d.width;
				rowHeight = Math.max(rowHeight, d.height);
			}
			addRow(dim, rowWidth, rowHeight);

			dim.width += horizontalInsetsAndGap;
			dim.height += insets.top + insets.bottom + vgap * 2;

			// Avoid a width feedback loop inside a scroll pane.
			final Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
			if (scrollPane != null && target.isValid())
			{
				dim.width -= (hgap + 1);
			}
			return dim;
		}
	}

	private void addRow(Dimension dim, int rowWidth, int rowHeight)
	{
		dim.width = Math.max(dim.width, rowWidth);
		if (dim.height > 0)
		{
			dim.height += getVgap();
		}
		dim.height += rowHeight;
	}
}
