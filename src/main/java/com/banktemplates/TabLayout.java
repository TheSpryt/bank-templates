package com.banktemplates;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout for a single native bank tab. {@code tab} 0 is the main "all items" view; 1-9 are the
 * numbered tabs.
 */
class TabLayout
{
	private int tab;
	private List<Integer> layout = new ArrayList<>();

	TabLayout()
	{
	}

	TabLayout(int tab, List<Integer> layout)
	{
		this.tab = tab;
		this.layout = layout != null ? layout : new ArrayList<>();
	}

	int getTab()
	{
		return tab;
	}

	/**
	 * The live, mutable slot list for this tab. Callers that edit a template (the layout editor) rely
	 * on this being the backing list so their changes stick; everyone else just reads it.
	 */
	List<Integer> getLayout()
	{
		if (layout == null)
		{
			layout = new ArrayList<>();
		}
		return layout;
	}
}
