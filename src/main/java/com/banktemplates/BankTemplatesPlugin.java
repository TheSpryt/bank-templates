package com.banktemplates;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.bank.BankSearch;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Bank Templates",
	description = "Create, share and apply bank layout templates that virtually arrange your bank.",
	tags = {"bank", "layout", "template", "organise", "organize", "tabs", "placeholder", "sort"}
)
public class BankTemplatesPlugin extends Plugin
{
	/** Bank grid width in columns; exposed for templates and rendering. */
	public static final int ITEMS_PER_ROW = BankLayoutRenderer.ITEMS_PER_ROW;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private BankSearch bankSearch;

	@Inject
	private TemplateManager templateManager;

	@Inject
	private BankLayoutRenderer renderer;

	@Inject
	private TemplateRepositoryClient repositoryClient;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ReorgHelperOverlay reorgHelperOverlay;

	@Inject
	private LayoutEditor layoutEditor;

	@Inject
	private LayoutEditorOverlay layoutEditorOverlay;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private BankTemplatesPanel panel;

	private NavigationButton navButton;

	// The layout-change listener registered with the singleton LayoutEditor, stored so it can be removed
	// on shutDown (otherwise it would accumulate across disable/enable cycles).
	private Runnable layoutListener;

	@Override
	protected void startUp()
	{
		templateManager.load();

		panel.setOnActiveChanged(this::requestBankRebuild);
		// Editing a layout should redraw the bank and refresh the panel (Edit/Done state, item counts).
		layoutListener = () ->
		{
			requestBankRebuild();
			panel.rebuild();
		};
		layoutEditor.addListener(layoutListener);
		panel.rebuild();

		final BufferedImage icon = ImageUtil.loadImageResource(BankTemplatesPlugin.class, "/com/banktemplates/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Bank Templates")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		overlayManager.add(reorgHelperOverlay);
		overlayManager.add(layoutEditorOverlay);
		mouseManager.registerMouseListener(layoutEditorOverlay);
		mouseManager.registerMouseListener(reorgHelperOverlay);

		repositoryClient.setIdentity(client.getAccountHash());
		requestBankRebuild();
	}

	@Override
	protected void shutDown()
	{
		if (layoutEditor.isEditing())
		{
			layoutEditor.finish();
		}
		if (layoutListener != null)
		{
			layoutEditor.removeListener(layoutListener);
			layoutListener = null;
		}
		mouseManager.unregisterMouseListener(layoutEditorOverlay);
		mouseManager.unregisterMouseListener(reorgHelperOverlay);
		overlayManager.remove(layoutEditorOverlay);
		overlayManager.remove(reorgHelperOverlay);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		requestBankRebuild();
	}

	// Run after Bank Tags (default priority 0) so its "Tag tab …" title is already set by the time we
	// check whether to step aside. The bank build script still runs after all pre-fired subscribers.
	@Subscribe(priority = -1f)
	public void onScriptPreFired(ScriptPreFired event)
	{
		renderer.onScriptPreFired(event);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Tie the repository identity to the logged-in account so reports/bans/ownership can't be
		// reset by restarting or reinstalling the client.
		final GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			repositoryClient.setIdentity(client.getAccountHash());
			// Load this account's cached bank counts (and pick up an account switch) without needing the bank open.
			panel.refreshOwnedCanon();
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			repositoryClient.setIdentity(-1);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// On first open the bank's initial build doesn't always pick up our layout (items only snap
		// into place after a tab click, which forces a rebuild). Force that clean rebuild on open.
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			// Re-select a virtual template tab the game reset to all-items, before forcing the clean rebuild.
			renderer.restoreViewedTabOnOpen();
			requestBankRebuild();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Keep My Templates' "x / y items" counts current as the bank first loads or its contents change,
		// without the user having to leave and re-open the panel.
		if (event.getContainerId() == InventoryID.BANK)
		{
			panel.refreshOwnedCanon();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		renderer.handleRelease(event);
		renderer.remapWithdraw(event);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		renderer.onScriptPostFired(event);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (BankTemplatesConfig.GROUP.equals(event.getGroup()))
		{
			requestBankRebuild();
		}
	}

	/** Rebuilds the bank interface so the active template (or normal view) is re-applied. */
	private void requestBankRebuild()
	{
		clientThread.invoke(() ->
		{
			if (client.getWidget(InterfaceID.Bankmain.ITEMS) != null)
			{
				bankSearch.layoutBank();
			}
		});
	}

	@Provides
	BankTemplatesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankTemplatesConfig.class);
	}
}
