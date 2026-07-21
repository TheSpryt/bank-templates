package com.banktemplates;

import com.google.gson.annotations.SerializedName;
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
	// The uploader's public Exchange Insights profile (null when anonymous or not linked). Drives the
	// profile-styled Browse cards: themed background, avatar, display name. Refreshed each fetch, so it
	// always reflects what the uploader currently has set.
	Profile profile;

	/** Subset of the uploader's public profile the plugin renders. */
	static class Profile
	{
		String handle;
		@SerializedName("display_name") String displayName;
		@SerializedName("is_premium") boolean premium;
		@SerializedName("is_admin") boolean admin;
		@SerializedName("profile_badge") String profileBadge;
		@SerializedName("profile_bg") String profileBg;
		@SerializedName("avatar_icon") String avatarIcon;
		@SerializedName("avatar_item_id") Integer avatarItemId;
	}

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
