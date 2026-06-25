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
		name = "Community repository",
		description = "Browse, import and share templates from the community repository.",
		position = 2,
		closedByDefault = true
	)
	String repositorySection = "repository";

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

	enum ReorgDisplay
	{
		STEP_BY_STEP("Step-by-step",
			"Sorts items into the right tabs first, then guides each one into its exact slot, one move at a time - choosing swap or insert to keep the number of drags down."),
		LABELS("Labels",
			"Tags every out-of-place item with where it belongs: first its destination tab, then its row and column within that tab."),
		BOTH("Labels + step-by-step",
			"Shows the destination tags on every item and the step-by-step guidance at the same time.");

		private final String label;
		private final String description;

		ReorgDisplay(String label, String description)
		{
			this.label = label;
			this.description = description;
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
}
