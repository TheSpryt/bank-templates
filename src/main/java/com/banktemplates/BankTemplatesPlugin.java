package com.banktemplates;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
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
	private net.runelite.client.eventbus.EventBus eventBus;

	@Inject
	private BankValuePreRenderer bankValuePreRenderer;

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
	private ChatboxItemSearch itemSearch;

	@Inject
	private ItemManager itemManager;

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

		// Second, higher-priority ScriptPreFired subscriber (see BankValuePreRenderer): renders virtual tabs
		// before RuneLite's Bank plugin values the title. It's not auto-registered like the plugin's own
		// subscriptions, so register it here and unregister on shutdown.
		eventBus.register(bankValuePreRenderer);

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
		eventBus.unregister(bankValuePreRenderer);
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
	public void onMenuOpened(MenuOpened event)
	{
		// A REAL numbered tab: the game already provides View/Collapse/Remove placeholders, so just slot in
		// "Set icon"/"Reset icon" as the 2nd/3rd options. Only over the applied template's own bank view (not
		// a tag tab, a search or a different template). Virtual tabs have no game menu - their whole menu is
		// built each tick in onClientTick instead (so it also works as the hover action).
		if (templateManager.getActive() == null || !layoutEditor.liveOverBank()
			|| BankLayoutRenderer.isBankFiltered(client))
		{
			return;
		}
		final Point m = client.getMouseCanvasPosition();
		if (m == null)
		{
			return;
		}
		final int tab = layoutEditorOverlay.tabAt(new java.awt.Point(m.getX(), m.getY()));
		if (tab < 1 || tab > renderer.realTabCount())
		{
			return;
		}
		final BankTemplate template = layoutEditor.liveTemplate();
		if (template == null)
		{
			return;
		}
		final String target = "<col=ff9040>Tab " + tab + "</col>";
		if (template.getTabIcon(tab) > 0)
		{
			client.createMenuEntry(-2).setOption("Reset icon").setTarget(target)
				.setType(MenuAction.RUNELITE).onClick(e -> layoutEditor.setTabIcon(tab, 0));
		}
		client.createMenuEntry(-2).setOption("Set icon").setTarget(target)
			.setType(MenuAction.RUNELITE).onClick(e -> openTabIconSearch(tab));
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		// A virtual tab is overlay-drawn with no game widget, so it has no hover action or right-click menu of
		// its own. Build the full menu each tick the cursor is over one, so the top-left reads "View tab N /
		// x more options" and right-click shows every option. Skip when it's already there - RuneLite only
		// rebuilds the menu when the cursor moves, so re-adding while it sits still piles up duplicates.
		if (templateManager.getActive() == null || !layoutEditor.liveOverBank()
			|| BankLayoutRenderer.isBankFiltered(client))
		{
			return;
		}
		final Point m = client.getMouseCanvasPosition();
		if (m == null)
		{
			return;
		}
		final int tab = layoutEditorOverlay.tabAt(new java.awt.Point(m.getX(), m.getY()));
		if (tab <= renderer.realTabCount() || menuHasOption("View tab"))
		{
			return;
		}
		final BankTemplate template = layoutEditor.liveTemplate();
		if (template == null)
		{
			return;
		}
		final String target = "<col=ff9040>Tab " + tab + "</col>";
		// Built bottom-up (each -1 goes to the top): View tab, Set icon, [Reset icon], Collapse, Remove placeholders.
		client.createMenuEntry(-1).setOption("Remove placeholders").setTarget(target)
			.setType(MenuAction.RUNELITE).onClick(e -> removeTabPlaceholders(tab));
		client.createMenuEntry(-1).setOption("Collapse tab").setTarget(target)
			.setType(MenuAction.RUNELITE).onClick(e -> collapseVirtualTab(tab));
		if (template.getTabIcon(tab) > 0)
		{
			client.createMenuEntry(-1).setOption("Reset icon").setTarget(target)
				.setType(MenuAction.RUNELITE).onClick(e -> layoutEditor.setTabIcon(tab, 0));
		}
		client.createMenuEntry(-1).setOption("Set icon").setTarget(target)
			.setType(MenuAction.RUNELITE).onClick(e -> openTabIconSearch(tab));
		client.createMenuEntry(-1).setOption("View tab").setTarget(target)
			.setType(MenuAction.RUNELITE).onClick(e -> viewTab(tab));
	}

	// Whether the current right-click menu already contains an entry with this option text.
	private boolean menuHasOption(String option)
	{
		for (MenuEntry e : client.getMenuEntries())
		{
			if (option.equals(e.getOption()))
			{
				return true;
			}
		}
		return false;
	}

	// Opens RuneLite's chatbox item search to choose a tab's custom icon.
	private void openTabIconSearch(int tab)
	{
		itemSearch
			.tooltipText("Set the icon for tab " + tab)
			.onItemSelected(itemId -> clientThread.invokeLater(() -> layoutEditor.setTabIcon(tab, itemId)))
			.build();
	}

	// Views a virtual tab (as clicking its overlay button does): make it the current bank tab and re-lay-out.
	private void viewTab(int tab)
	{
		client.setVarbit(VarbitID.BANK_CURRENTTAB, tab);
		bankSearch.layoutBank();
	}

	// Collapses a virtual tab into the all-items view (moves its items to the main tab, drops the tab), then
	// shows the main view since the tab you were on is gone.
	private void collapseVirtualTab(int tab)
	{
		layoutEditor.collapseTab(tab);
		client.setVarbit(VarbitID.BANK_CURRENTTAB, BankTemplate.MAIN_TAB);
		bankSearch.layoutBank();
	}

	// Removes a tab's placeholder slots: items you don't own (shown faded) and any native placeholder items.
	private void removeTabPlaceholders(int tab)
	{
		final BankTemplate template = layoutEditor.liveTemplate();
		if (template == null)
		{
			return;
		}
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		synchronized (template)
		{
			template.editableTab(tab).removeIf(v ->
			{
				if (v == null || v <= 0 || v == BankTemplate.FILLER)
				{
					return false;
				}
				if (client.getItemDefinition(v).getPlaceholderTemplateId() != -1)
				{
					return true;
				}
				return bank == null || bank.count(itemManager.canonicalize(v)) <= 0;
			});
		}
		templateManager.saveUserTemplate(template);
		requestBankRebuild();
		panel.rebuild();
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
