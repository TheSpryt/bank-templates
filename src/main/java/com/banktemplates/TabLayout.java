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
	private List<Integer> layout;

	TabLayout()
	{
	}

	TabLayout(int tab, List<Integer> layout)
	{
		this.tab = tab;
		this.layout = layout;
	}

	int getTab()
	{
		return tab;
	}

	List<Integer> getLayout()
	{
		return layout != null ? layout : new ArrayList<>();
	}
}
