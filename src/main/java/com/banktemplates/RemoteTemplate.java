package com.banktemplates;

import java.util.List;

/**
 * A template as returned by the community repository API. Mirrors the JSON the Worker sends.
 */
class RemoteTemplate
{
	long id;
	String name;
	String description;
	String author;
	int downloads;
	int reports;
	int columns;
	List<TabLayout> tabs;

	BankTemplate toTemplate()
	{
		final BankTemplate t = new BankTemplate();
		t.setName(name);
		t.setDescription(description);
		t.setColumns(columns);
		if (tabs != null)
		{
			for (TabLayout tl : tabs)
			{
				t.putTab(tl.getTab(), tl.getLayout());
			}
		}
		return t;
	}
}
