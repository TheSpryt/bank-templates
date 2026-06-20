package com.banktemplates;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
	private BankTemplatesPanel panel;

	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		templateManager.load();

		panel.setOnActiveChanged(this::requestBankRebuild);
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

		repositoryClient.setIdentity(client.getAccountHash());
		requestBankRebuild();
	}

	@Override
	protected void shutDown()
	{
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
			requestBankRebuild();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		renderer.remapWithdraw(event);
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
