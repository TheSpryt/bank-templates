package com.banktemplates;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BankTemplatesConfig.GROUP)
public interface BankTemplatesConfig extends Config
{
	String GROUP = "bank-templates";

	// Persisted selection of the active template (by name). Hidden from the config panel; driven
	// by the side panel instead. Empty/absent means "None".
	String ACTIVE_TEMPLATE_KEY = "activeTemplate";

	// Last changelog version whose notes the user has already seen. Hidden; managed by the side panel.
	// Used to decide whether the panel should open on the Updates tab.
	String LAST_SEEN_UPDATE_KEY = "lastSeenUpdate";

	@ConfigItem(
		keyName = "alertUpdates",
		name = "Notify me about updates",
		description = "Show an Updates tab in the side panel with the latest patch notes. The panel opens on it once after each update, until you have seen the notes.",
		position = 0
	)
	default boolean alertUpdates()
	{
		return true;
	}

	@ConfigSection(
		name = "Layout",
		description = "How the active template is drawn over the bank.",
		position = 0
	)
	String layoutSection = "layout";

	@ConfigSection(
		name = "Reorganise helper",
		description = "Guides for manually rearranging your real bank to match a template.",
		position = 1
	)
	String reorgSection = "reorg";

	@ConfigSection(
		name = "Reorganise tab colours",
		description = "Colours the reorganise helper's colour-coding mode uses for each bank tab.",
		position = 2,
		closedByDefault = true
	)
	String reorgColorSection = "reorgColors";

	@ConfigSection(
		name = "Community repository",
		description = "Browse, import and share templates from the community repository.",
		position = 3,
		closedByDefault = true
	)
	String repositorySection = "repository";

	@ConfigSection(
		name = "Exchange Insights account",
		description = "Link this character to your Exchange Insights account so your bank templates sync between the game and the website.",
		position = 4,
		closedByDefault = true
	)
	String accountSection = "eiAccount";

	// Momentary action, not a stored preference: ticking it starts the one-click browser link and the panel
	// clears it straight back to false. Kept as a config item so it's available from the settings screen as
	// well as the side-panel button.
	@ConfigItem(
		keyName = "linkAccountInBrowser",
		name = "Link account in browser",
		description = "One-click linking: opens exchange-insights.gg in your browser to approve linking this character - no token to copy. Be logged into OSRS and signed in on the website first. Ticking this starts the link (it clears itself once done). Requires the community repository to be enabled.",
		position = 0,
		section = accountSection
	)
	default boolean linkAccountInBrowser()
	{
		return false;
	}

	@ConfigItem(
		keyName = "eiAccountToken",
		name = "Account token",
		description = "Alternative to the one-click link above: paste your Exchange Insights account token to link this character. Get it free at exchange-insights.gg (Account → RuneLite plugin). Templates you make then sync to your website My Templates and back. This works alongside the Exchange Insights plugin - you can set the same token in both.",
		position = 1,
		section = accountSection,
		secret = true
	)
	default String eiAccountToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "enableRepository",
		name = "Enable community repository",
		description = "Allow the side panel to browse, import and upload templates from the community repository server.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 0,
		section = repositorySection
	)
	default boolean enableRepository()
	{
		return false;
	}

	@ConfigItem(
		keyName = "applyLayout",
		name = "Apply template to bank",
		description = "Virtually arrange your bank items to match the active template when the bank is open. Your real bank is never changed.",
		position = 0,
		section = layoutSection
	)
	default boolean applyLayout()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPlaceholders",
		name = "Show placeholders for unowned items",
		description = "Draw a faded icon in slots for items the template wants but you don't own yet.",
		position = 1,
		section = layoutSection
	)
	default boolean showPlaceholders()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideNonTemplateItems",
		name = "Hide items not in the template",
		description = "When a template is applied, hide bank items it doesn't include instead of showing them below. Note: those items stay invisible in the bank view until you switch the template off.",
		position = 2,
		section = layoutSection
	)
	default boolean hideNonTemplateItems()
	{
		return false;
	}

	// Toggled from the side panel rather than shown here; kept as a (hidden) config key so the choice
	// persists.
	@ConfigItem(
		keyName = "showReorgHelper",
		name = "Show reorganise helper",
		description = "Guided reorganise helper (toggled from the Bank Templates side panel).",
		hidden = true,
		position = 0,
		section = reorgSection
	)
	default boolean showReorgHelper()
	{
		return false;
	}

	// Reorg display style. Panel-driven (hidden here), persisted so the choice survives restarts.
	@ConfigItem(
		keyName = "reorgDisplay",
		name = "Reorganise display",
		description = "How the reorganise helper guides you.",
		hidden = true,
		position = 2,
		section = reorgSection
	)
	default ReorgDisplay reorgDisplay()
	{
		return ReorgDisplay.STEP_BY_STEP;
	}

	// Reorganise display styles. Colour-coding, labels and step-by-step are three independent cues that can be
	// combined, enumerated here as the selectable combinations. The constant NAMES of the original three
	// (STEP_BY_STEP, LABELS, BOTH) are kept so existing saved settings still resolve.
	enum ReorgDisplay
	{
		COLOR("Colour-coded", true, false, false,
			"Tints each out-of-place item and every tab with the destination tab's colour, so you can see where things belong at a glance."),
		LABELS("Labels", false, true, false,
			"Tags every out-of-place item with where it belongs: first its destination tab, then its row and column within that tab."),
		STEP_BY_STEP("Step-by-step", false, false, true,
			"Sorts items into the right tabs first, then guides each one into its exact slot, one move at a time - choosing swap or insert to keep the number of drags down."),
		COLOR_LABELS("Colour-coded + Labels", true, true, false,
			"Destination-tab colour-coding and labels together: each item tinted by where it belongs, with a tag for its exact tab, row and column."),
		COLOR_STEP_BY_STEP("Colour-coded + Step-by-step", true, false, true,
			"Destination-tab colour-coding while you are guided through each move, one at a time."),
		BOTH("Labels + Step-by-step", false, true, true,
			"Shows the destination tags on every item and the step-by-step guidance at the same time."),
		COLOR_BOTH("Colour-coded + Labels + Step-by-step", true, true, true,
			"Everything at once: destination-tab colour-coding, labels, and step-by-step guidance.");

		private final String label;
		private final boolean color;
		private final boolean labels;
		private final boolean steps;
		private final String description;

		ReorgDisplay(String label, boolean color, boolean labels, boolean steps, String description)
		{
			this.label = label;
			this.color = color;
			this.labels = labels;
			this.steps = steps;
			this.description = description;
		}

		public boolean isColor()
		{
			return color;
		}

		public boolean isLabels()
		{
			return labels;
		}

		public boolean isSteps()
		{
			return steps;
		}

		public String getDescription()
		{
			return description;
		}

		@Override
		public String toString()
		{
			return label;
		}

		// Reverse lookup from the dropdown label (toString()); valueOf() only matches the constant name.
		public static ReorgDisplay fromLabel(String label)
		{
			for (ReorgDisplay d : values())
			{
				if (d.label.equals(label))
				{
					return d;
				}
			}
			return null;
		}
	}

	@Alpha
	@ConfigItem(
		keyName = "reorgHighlightColor",
		name = "Target highlight",
		description = "Colour used to highlight the destination slot for the next item to move.",
		position = 1,
		section = reorgSection
	)
	default Color reorgHighlightColor()
	{
		return new Color(0xCD00FFFF, true);
	}

	// Per-tab identity colours for the colour-coding display. Defaults match the built-in palette. The
	// reorganise helper uses each colour's RGB at a fixed faint alpha, so the picker is opaque (no @Alpha).
	@ConfigItem(keyName = "reorgTabColorMain", name = "Main tab", description = "Colour for items belonging in the main (all-items) tab.", position = 0, section = reorgColorSection)
	default Color reorgTabColorMain()
	{
		return new Color(170, 200, 225);
	}

	@ConfigItem(keyName = "reorgTabColor1", name = "Tab 1", description = "Colour for items belonging in tab 1.", position = 1, section = reorgColorSection)
	default Color reorgTabColor1()
	{
		return new Color(205, 80, 80);
	}

	@ConfigItem(keyName = "reorgTabColor2", name = "Tab 2", description = "Colour for items belonging in tab 2.", position = 2, section = reorgColorSection)
	default Color reorgTabColor2()
	{
		return new Color(210, 145, 60);
	}

	@ConfigItem(keyName = "reorgTabColor3", name = "Tab 3", description = "Colour for items belonging in tab 3.", position = 3, section = reorgColorSection)
	default Color reorgTabColor3()
	{
		return new Color(200, 190, 80);
	}

	@ConfigItem(keyName = "reorgTabColor4", name = "Tab 4", description = "Colour for items belonging in tab 4.", position = 4, section = reorgColorSection)
	default Color reorgTabColor4()
	{
		return new Color(95, 175, 95);
	}

	@ConfigItem(keyName = "reorgTabColor5", name = "Tab 5", description = "Colour for items belonging in tab 5.", position = 5, section = reorgColorSection)
	default Color reorgTabColor5()
	{
		return new Color(85, 135, 205);
	}

	@ConfigItem(keyName = "reorgTabColor6", name = "Tab 6", description = "Colour for items belonging in tab 6.", position = 6, section = reorgColorSection)
	default Color reorgTabColor6()
	{
		return new Color(155, 110, 200);
	}

	@ConfigItem(keyName = "reorgTabColor7", name = "Tab 7", description = "Colour for items belonging in tab 7.", position = 7, section = reorgColorSection)
	default Color reorgTabColor7()
	{
		return new Color(205, 120, 170);
	}

	@ConfigItem(keyName = "reorgTabColor8", name = "Tab 8", description = "Colour for items belonging in tab 8.", position = 8, section = reorgColorSection)
	default Color reorgTabColor8()
	{
		return new Color(85, 190, 180);
	}

	@ConfigItem(keyName = "reorgTabColor9", name = "Tab 9", description = "Colour for items belonging in tab 9.", position = 9, section = reorgColorSection)
	default Color reorgTabColor9()
	{
		return new Color(180, 180, 185);
	}
}
