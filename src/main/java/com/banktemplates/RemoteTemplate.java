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
	// Set by the server when the uploader chose to share anonymously: clients show "Anonymous" instead
	// of the author's name (the server still keeps the real name privately for moderation).
	boolean anonymous;
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
