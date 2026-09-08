package com.banktemplates;

import java.util.List;

/**
 * One entry of the catalogue's index.json: a template's metadata, without its layout. The layout is a
 * separate immutable file (see {@link TemplateRepositoryClient#fetchLayout}) fetched only when the
 * player previews or imports.
 */
class RemoteTemplate
{
	long id;
	// Bumped on every edit; names the layout file for this version of the template.
	int rev;
	String name;
	String author;
	// Set by the server when the uploader chose to share anonymously: clients show "Anonymous" instead
	// of the author's name (the server still keeps the real name privately for moderation).
	boolean anonymous;
	String description;
	long created;
	long updated;
	int downloads;
	// Imports in the last 30 days, for the "Popular" sort.
	int recent;
	int reports;
	// Distinct item ids across every tab, so a card can say how big the template is without its layout.
	int items;
	int columns;
	List<TabRef> tabs;

	/** One tab of the index entry: its number and the icon its bank button would show (0 when empty). */
	static class TabRef
	{
		int tab;
		int icon;
	}

	int tabCount()
	{
		return tabs == null ? 0 : tabs.size();
	}

	/** Builds the local template from this entry's name and description plus a fetched layout. */
	BankTemplate toTemplate(TemplateRepositoryClient.LayoutFile layout)
	{
		final BankTemplate t = new BankTemplate();
		t.setName(name);
		t.setDescription(description);
		t.setColumns(layout.columns > 0 ? layout.columns : columns);
		if (layout.tabs != null)
		{
			for (TabLayout tl : layout.tabs)
			{
				t.putTab(tl.getTab(), tl.getLayout());
				// The uploader's chosen tab icons are part of the layout they shared, so an import gets
				// them too. putTab can't carry them here: it only keeps an icon a tab already had, and
				// this template was built empty just above.
				t.setTabIcon(tl.getTab(), tl.getCustomIconId());
			}
		}
		return t;
	}
}
