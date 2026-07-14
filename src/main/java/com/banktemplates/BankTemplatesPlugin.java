package com.banktemplates;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.GameState;
import net.runelite.api.Item;
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
	description = "Create, share and apply bank layout templates that virtually arrange your bank. Free forever.",
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

	@Inject
	private BankTemplatesConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService executor;

	// The account hash we've already sent an Exchange Insights identity link for this session, so linking
	// happens once per character per session (and can retry until the player's name has loaded).
	private long eiLinkedHash = -1;

	// Opt-in bank snapshot sync (bank-value tracking): bank changes arrive in bursts while the bank is
	// open, so debounce, then read once and only send when the contents actually changed.
	private static final long BANK_SNAPSHOT_DEBOUNCE_MS = 5_000;
	private static final long BANK_SNAPSHOT_MIN_INTERVAL_MS = 60_000;
	private ScheduledFuture<?> pendingBankSnapshot;
	// Volatile: written on the client thread, but also reset from other threads (account switch via
	// startUp, the failed-send retry callback) - visibility matters for the "unchanged" suppression.
	private volatile long lastBankSnapshotChecksum;
	private volatile long lastBankSnapshotAt;
	// True after shutDown: cancel(false) can't stop an already-executing task, and the retry/rate-gap
	// paths re-arm - this flag stops a disabled plugin from resurrecting the snapshot loop.
	private volatile boolean snapshotStopped;

	private NavigationButton navButton;

	// The layout-change listener registered with the singleton LayoutEditor, stored so it can be removed
	// on shutDown (otherwise it would accumulate across disable/enable cycles).
	private Runnable layoutListener;

	@Override
	protected void startUp()
	{
		snapshotStopped = false; // re-enable after a previous shutDown (the panel/plugin are singletons)
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

		updateRepoIdentity(client.getAccountHash());
		requestBankRebuild();
	}

	// The last account hash we backfilled the profile link for, so we only send one claim per account
	// per session (on login or an account switch) rather than on every game-state change.
	private long claimedAccountHash = -1;

	// Point the repository client at the current account and, the first time we see a given account this
	// session, ask the server to link that account's existing shared templates to its Exchange Insights
	// profile (a no-op for accounts that aren't linked, and idempotent otherwise).
	private void updateRepoIdentity(long accountHash)
	{
		repositoryClient.setIdentity(accountHash);
		if (accountHash != -1 && accountHash != claimedAccountHash)
		{
			claimedAccountHash = accountHash;
			// A different character's bank is a different snapshot - never suppress it as "unchanged".
			lastBankSnapshotChecksum = 0;
			// And its link state is unknown until its first sync answers - close the snapshot gate now.
			panel.resetLinkState();
			repositoryClient.claimTemplates();
			// Pull this account's website-made templates (incl. private) into My Templates.
			panel.syncWebTemplates();
		}
	}

	@Override
	protected void shutDown()
	{
		synchronized (this)
		{
			// The flag (not just the cancel) is what stops an already-executing task's retry/rate-gap
			// re-arm from resurrecting the loop after the plugin is disabled.
			snapshotStopped = true;
			if (pendingBankSnapshot != null)
			{
				pendingBankSnapshot.cancel(false);
				pendingBankSnapshot = null;
			}
		}
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
		if (panel != null)
		{
			panel.stopSync();
		}
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
			updateRepoIdentity(client.getAccountHash());
			// Load this account's cached bank counts (and pick up an account switch) without needing the bank open.
			panel.refreshOwnedCanon();
			maybeLinkEiAccount(false);
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
			scheduleBankSnapshot(BANK_SNAPSHOT_DEBOUNCE_MS);
		}
	}

	// (Re)arm the debounced snapshot send, replacing any pending one so a burst of bank changes
	// collapses into a single read + send once things settle.
	private synchronized void scheduleBankSnapshot(long delayMs)
	{
		if (snapshotStopped || !config.syncBankSnapshot() || !repositoryClient.isEnabled())
		{
			return;
		}
		if (pendingBankSnapshot != null)
		{
			pendingBankSnapshot.cancel(false);
		}
		pendingBankSnapshot = executor.schedule(
			() -> clientThread.invoke(this::sendBankSnapshotNow), delayMs, TimeUnit.MILLISECONDS);
	}

	// Reads the bank and sends the snapshot (client thread - container reads aren't safe elsewhere).
	// Skips silently when nothing changed since the last send; the server additionally only stores
	// snapshots for characters linked to an Exchange Insights account.
	private void sendBankSnapshotNow()
	{
		// panel.isWebLinked() is the privacy gate: bank contents must never leave the client unless the
		// duplex sync has CONFIRMED this character is linked to an Exchange Insights account (the setting
		// promises "never sent for unlinked characters" - merely being logged in isn't consent). While the
		// link state is still unknown (before the first sync answers), we skip; the next bank change after
		// confirmation sends normally.
		if (snapshotStopped || !config.syncBankSnapshot() || !repositoryClient.isEnabled()
			|| !repositoryClient.hasIdentity() || !panel.isWebLinked())
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - lastBankSnapshotAt < BANK_SNAPSHOT_MIN_INTERVAL_MS)
		{
			// Too soon - re-arm for the remainder of the window so the change still lands.
			scheduleBankSnapshot(BANK_SNAPSHOT_MIN_INTERVAL_MS - (now - lastBankSnapshotAt));
			return;
		}
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return;
		}
		// [id, qty, tab] triples, sliced into tabs the same way BankCapture does (container is ordered
		// tab 1..9 then the main view; per-tab counts live in the BANK_TAB_1..9 varbits). Tab 0 = main.
		final Item[] all = bank.getItems();
		final List<int[]> items = new ArrayList<>();
		long checksum = 7;
		int idx = 0;
		for (int tab = 1; tab <= 9; tab++)
		{
			final int count = client.getVarbitValue(BankCapture.TAB_COUNT_VARBITS[tab - 1]);
			for (int k = 0; k < count && idx < all.length; k++, idx++)
			{
				checksum = addSnapshotItem(items, all[idx], tab, checksum);
			}
		}
		for (; idx < all.length; idx++)
		{
			checksum = addSnapshotItem(items, all[idx], 0, checksum);
		}
		if (items.isEmpty() || checksum == lastBankSnapshotChecksum)
		{
			return;
		}
		lastBankSnapshotChecksum = checksum;
		lastBankSnapshotAt = now;
		// The response carries the bank's live GE value - surface it in the side panel (the free teaser
		// for bank-value tracking on the website). If the snapshot was NOT stored (network failure or
		// the server's write gap), forget the checksum and retry after the min interval so the latest
		// bank state isn't silently lost (e.g. the last change before a logout).
		repositoryClient.sendBankSnapshot(items,
			value -> javax.swing.SwingUtilities.invokeLater(() -> panel.setBankValue(value)),
			() ->
			{
				lastBankSnapshotChecksum = 0;
				scheduleBankSnapshot(BANK_SNAPSHOT_MIN_INTERVAL_MS);
			});
	}

	// Adds one bank stack to the snapshot and folds it into the change-detection checksum. Empty slots
	// are skipped, and placeholders are skipped BY DEFINITION (getPlaceholderTemplateId), not by their
	// quantity: the container reports quantity 1 for placeholders in some client states, which used to
	// leak thousands of placeholder-variant item ids (no icon, no price, not really held) into
	// snapshots. Runs on the client thread (sendBankSnapshotNow), so the composition read is safe.
	private long addSnapshotItem(List<int[]> items, Item item, int tab, long checksum)
	{
		if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
		{
			return checksum;
		}
		if (client.getItemComposition(item.getId()).getPlaceholderTemplateId() != -1)
		{
			return checksum; // a placeholder, not a held item
		}
		items.add(new int[]{item.getId(), item.getQuantity(), tab});
		checksum = checksum * 31 + item.getId();
		checksum = checksum * 31 + item.getQuantity();
		return checksum * 31 + tab;
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
		// The Exchange Insights plugin's token changed on this client. When we have no token of our
		// own we borrow that one (see TemplateRepositoryClient.effectiveToken), so re-run the same
		// link/refresh path as if our own token changed - set, cleared or replaced.
		if (TemplateRepositoryClient.EI_PLUGIN_CONFIG_GROUP.equals(event.getGroup())
			&& TemplateRepositoryClient.EI_PLUGIN_TOKEN_KEY.equals(event.getKey()))
		{
			eiLinkedHash = -1;
			maybeLinkEiAccount(false);
			javax.swing.SwingUtilities.invokeLater(panel::refreshLinkStatus);
			return;
		}
		if (BankTemplatesConfig.GROUP.equals(event.getGroup()))
		{
			// Pasting/changing the Exchange Insights token should link right away, without a relog.
			if ("eiAccountToken".equals(event.getKey()))
			{
				// Pasting a token here is an explicit link request: lift any earlier unlink
				// opt-out for the logged-in character so the link actually happens.
				final String pasted = event.getNewValue();
				final long h = client.getAccountHash();
				if (pasted != null && !pasted.trim().isEmpty() && h != -1)
				{
					repositoryClient.setUnlinkOptOut(h, false);
				}
				eiLinkedHash = -1;
				maybeLinkEiAccount(true); // explicit: a pasted token also lifts the server-side tombstone
				// Refresh the side panel's linked-as status for the new token.
				javax.swing.SwingUtilities.invokeLater(panel::refreshLinkStatus);
			}
			// The "Link account in browser" toggle is a momentary action: reset it straight back to false and
			// start the one-click device link from the panel (which owns the browser + poll flow).
			else if ("linkAccountInBrowser".equals(event.getKey()) && Boolean.parseBoolean(event.getNewValue()))
			{
				configManager.setConfiguration(BankTemplatesConfig.GROUP, "linkAccountInBrowser", false);
				javax.swing.SwingUtilities.invokeLater(panel::startOneClickLink);
			}
			requestBankRebuild();
		}
	}

	// Link the logged-in character to the Exchange Insights account whose token is set in the config, so
	// bank templates sync to that account. Idempotent + ownership-safe server-side, and does nothing until
	// a token is set, the community repository is enabled, and a character is logged in - so it coexists
	// with the Exchange Insights plugin (both may send the same link). Runs once per character per session.
	// `explicit` marks a deliberate user action (a pasted token) and lifts the server-side unlink
	// tombstone; ambient triggers (login, the borrowed token changing) pass false and respect it.
	private void maybeLinkEiAccount(boolean explicit)
	{
		final String effective = repositoryClient.effectiveToken();
		final String token = effective == null ? "" : effective;
		final long hash = client.getAccountHash();
		if (token.isEmpty() || !repositoryClient.isEnabled() || hash == -1 || hash == eiLinkedHash)
		{
			return;
		}
		// The user explicitly unlinked this character from the panel: never auto-relink it.
		if (repositoryClient.isUnlinkOptedOut(hash))
		{
			return;
		}
		// Wait (on the client thread) until the local player's name has loaded, then link exactly once.
		clientThread.invokeLater(() ->
		{
			if (client.getAccountHash() != hash)
			{
				return true; // logged out / switched before the name loaded - abandon this attempt
			}
			final Player p = client.getLocalPlayer();
			if (p == null || p.getName() == null || p.getName().isEmpty())
			{
				return false; // not ready yet - retry next tick
			}
			eiLinkedHash = hash;
			repositoryClient.linkEiAccount(token, hash, p.getName(), explicit,
				panel::syncWebTemplates, // linked - kick a sync now that the account resolves
				error -> {});
			return true;
		});
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
