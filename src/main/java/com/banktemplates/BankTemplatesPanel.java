package com.banktemplates;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

@Slf4j
@Singleton
public class BankTemplatesPanel extends PluginPanel
{
	private static final Border CARD_BORDER = BorderFactory.createEmptyBorder(4, 6, 4, 6);
	private static final Border ACTIVE_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(0, 3, 0, 0, ColorScheme.BRAND_ORANGE),
		BorderFactory.createEmptyBorder(4, 3, 4, 6));

	private static final String LOCAL = "local";
	private static final String BROWSE = "browse";
	private static final String UPDATES = "updates";
	private static final int PAGE_SIZE = 20;
	private static final int MAX_NAME_LENGTH = 25;
	private static final int MAX_DESCRIPTION_LENGTH = 500;

	private static final String[] SORT_LABELS = {"Most imported", "Newest", "Popular (30 days)", "Items owned"};
	private static final String[] SORT_KEYS = {"imported", "newest", "popular", "closest"};
	// Client-only sort: the server can't rank by your bank (and we don't send it your items), so results are
	// fetched in this base order and re-sorted locally by how much of each template you already own.
	private static final String CLOSEST_SORT = "closest";

	private final TemplateManager templateManager;
	private final ItemManager itemManager;
	private final TemplateRepositoryClient repositoryClient;
	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final BankTemplatesConfig config;
	private final LayoutEditor layoutEditor;
	private final ItemIndex itemIndex;
	private final ScheduledExecutorService executor;
	// Persists each account's owned-item set so "x / y items" counts can show before the bank loads this session.
	private final OwnedBankCache ownedCache;

	// Tracks the scroll viewport's width so it never grows wider than the panel (e.g. a long dropdown item or
	// label can't push it out). Without this, "full-width" buttons stretch past the visible area and their
	// centred text ends up sitting left of centre.
	private final JPanel listContainer = new ListPanel();
	private final SearchBar searchBar = new SearchBar();
	private final JPanel tabsPanel = new JPanel();
	// Account link status/action row, pinned at the very top of the header (rebuilt in place as the link
	// state changes: a "Link account" button, a "Linking…" note, or a "linked as …" confirmation).
	private final JPanel accountRow = new JPanel();
	private final JButton localTab = new JButton("My Templates");
	private final JButton browseTab = new JButton("Browse");
	private final JButton updatesTab = new JButton("Updates");

	// The newest bundled patch notes (this build's version + notes), or null if none.
	private final Changelog.Entry latestUpdate;
	private final java.util.List<Changelog.Entry> allUpdates;

	private String mode = LOCAL;
	private String query = "";
	// Variant-collapsed ids the player owns in their bank (qty > 0), for the "x / y items" card meta. Computed
	// on the client thread (reading client state isn't EDT-safe), or loaded from the per-account cache until the
	// live bank has loaded; null when neither is available yet.
	private volatile Set<Integer> ownedCanon;
	// The account ownedCanon belongs to, so it's discarded (and reloaded) when the player switches accounts.
	private volatile long ownedAccountHash = -1;

	// Whether the last duplex sync found this character linked to an Exchange Insights account. null until
	// the first sync has answered, so the "link your account" note only shows once we actually know.
	private Boolean webSyncLinked;

	// One-click device-link state. `linking` is true from the moment the browser is opened until the poll
	// loop resolves (approved/denied/expired/timeout); linkedHandle is the Exchange Insights handle shown in
	// the status row once known (from a token ping). linkPollTask is the self-rescheduling poll, cancelled on
	// shutdown. All touched only on the EDT except linking (read from the poll thread).
	private volatile boolean linking;
	private String linkedHandle;
	private ScheduledFuture<?> linkPollTask;
	// Total time we'll wait for the browser approval before giving up (the server code lives ~10 minutes).
	private static final long LINK_WINDOW_MS = 10 * 60 * 1_000L;

	// Duplex sync scheduling. A single self-rescheduling task drives sync: it runs on login, ~1.5s after any
	// local change (debounced), and every SYNC_POLL_MS as a backstop to pull website-side changes down. On a
	// failed or rate-limited push it retries with backoff so a change is never lost to a brief outage.
	private static final long SYNC_DEBOUNCE_MS = 1_500;
	private static final long SYNC_RETRY_MIN_MS = 4_000;
	private static final long SYNC_POLL_MS = 90_000;
	private ScheduledFuture<?> pendingSyncTask;
	private volatile long changeSeq; // bumped on each local change
	private long syncedSeq; // highest changeSeq confirmed pushed to the website
	private long syncBackoffMs;

	private final List<RemoteTemplate> browseResults = new ArrayList<>();
	private String browseStatus;
	private String browseSort = "imported";
	private int browseOffset = 0;
	private boolean browseHasMore = false;
	// Total templates matching the current browse filter (from the server), for the count + pager labels.
	private int browseTotal = 0;

	private Runnable onActiveChanged = () ->
	{
	};

	@Inject
	BankTemplatesPanel(TemplateManager templateManager, ItemManager itemManager, TemplateRepositoryClient repositoryClient,
		Client client, ClientThread clientThread, ConfigManager configManager, BankTemplatesConfig config,
		LayoutEditor layoutEditor, ItemIndex itemIndex, ScheduledExecutorService executor, Gson gson)
	{
		// Don't let PluginPanel wrap us in its own scrollpane - we manage our own so the Updates bar can
		// stay pinned to the bottom while only the template list scrolls.
		super(false);
		this.templateManager = templateManager;
		this.itemManager = itemManager;
		this.repositoryClient = repositoryClient;
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.config = config;
		this.layoutEditor = layoutEditor;
		this.itemIndex = itemIndex;
		this.executor = executor;
		this.ownedCache = new OwnedBankCache(gson);
		this.latestUpdate = Changelog.latest(gson);
		this.allUpdates = Changelog.all(gson);

		// A genuine local template change (save/delete/rename) schedules a debounced duplex sync so it's
		// pushed to the website without waiting for the next login.
		templateManager.setChangeListener(this::requestSync);

		// Open onto the Updates tab the first time after an update, but only until the user has seen these
		// notes - after that, default to My Templates.
		if (updatesTabShown() && !updateAlreadySeen())
		{
			mode = UPDATES;
		}

		// Mark the version as seen once the panel is actually shown sitting on the Updates tab.
		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && UPDATES.equals(mode))
			{
				markUpdateSeen();
			}
		});

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);

		// The scrollable content (everything except the fixed bottom Updates bar) lives in the centre.
		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
		listContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final JScrollPane scroll = new JScrollPane(listContainer,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		ThinScrollBarUI.style(scroll);
		add(scroll, BorderLayout.CENTER);

		// Updates is pinned to the very bottom of the panel, shared by every tab (only when there's one).
		if (updatesTabShown())
		{
			updatesTab.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			final JPanel south = new JPanel(new BorderLayout());
			south.setBackground(ColorScheme.DARK_GRAY_COLOR);
			south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
			south.add(updatesTab, BorderLayout.CENTER);
			add(south, BorderLayout.SOUTH);
		}

		// If a token is already stored (from a previous session or the Exchange Insights plugin), resolve the
		// linked handle so the top-of-panel status can show "linked as …" straight away.
		refreshLinkStatus();
	}

	void setOnActiveChanged(Runnable r)
	{
		this.onActiveChanged = r != null ? r : () ->
		{
		};
	}

	private JPanel buildHeader()
	{
		final JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

		// Account link status/action, at the very top of the panel menu. Populated by refreshAccountRow().
		accountRow.setLayout(new BoxLayout(accountRow, BoxLayout.Y_AXIS));
		accountRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		accountRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(accountRow);
		refreshAccountRow();

		tabsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		localTab.setFocusPainted(false);
		browseTab.setFocusPainted(false);
		updatesTab.setFocusPainted(false);
		localTab.addActionListener(e -> switchMode(LOCAL));
		browseTab.addActionListener(e -> switchMode(BROWSE));
		updatesTab.addActionListener(e -> switchMode(UPDATES));
		layoutTabs();
		header.add(tabsPanel);

		header.add(Box.createVerticalStrut(8));

		searchBar.setPreferredSize(new Dimension(100, 30));
		searchBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		searchBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchBar.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				if (LOCAL.equals(mode))
				{
					query = searchBar.getText().trim().toLowerCase(Locale.ROOT);
					rebuildOnEdt();
				}
			}
		});
		searchBar.addActionListener(e ->
		{
			if (BROWSE.equals(mode))
			{
				newSearch();
			}
		});
		searchBar.addClearListener(() ->
		{
			query = "";
			if (LOCAL.equals(mode))
			{
				rebuildOnEdt();
			}
			else
			{
				newSearch();
			}
		});
		// No search box on the Updates/changelog view - there's nothing to search there.
		searchBar.setVisible(!UPDATES.equals(mode));
		header.add(searchBar);

		return header;
	}

	// True if a linked account should be shown: either the last sync confirmed the link, or (before any sync)
	// a token is set so we optimistically treat it as linked - the next sync corrects this if it's stale.
	private boolean isLinkedForDisplay()
	{
		return Boolean.TRUE.equals(webSyncLinked) || (webSyncLinked == null && hasEiToken());
	}

	private boolean hasEiToken()
	{
		final String t = config.eiAccountToken();
		return t != null && !t.trim().isEmpty();
	}

	private JLabel statusLabel(String text, Color color)
	{
		final JLabel l = new JLabel(text);
		l.setForeground(color);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	// (Re)draw the top-of-panel account row for the current link state. Only shown when the community
	// repository is enabled (all third-party server contact, including linking, is behind that opt-in).
	private void refreshAccountRow()
	{
		accountRow.removeAll();
		if (repositoryClient.isEnabled())
		{
			if (linking)
			{
				accountRow.add(statusLabel("Linking… approve it in your browser", ColorScheme.BRAND_ORANGE));
			}
			else if (isLinkedForDisplay())
			{
				final String who = linkedHandle != null && !linkedHandle.isEmpty() ? " as " + linkedHandle : "";
				accountRow.add(statusLabel("✓ Account linked" + who, new Color(95, 175, 95)));
				// Free teaser from the opt-in bank-value sync: the ingest response reports the bank's live
				// GE value, and the website tracks it over time.
				if (bankValue >= 0)
				{
					final JLabel bv = statusLabel("Bank value: " + fmtGp(bankValue) + " gp", ColorScheme.LIGHT_GRAY_COLOR);
					bv.setToolTipText("Your bank at live GE mid prices, updated as it changes. Track it over time at exchange-insights.gg.");
					accountRow.add(Box.createVerticalStrut(3));
					accountRow.add(bv);
				}
			}
			else
			{
				final JButton link = styledButton("Link Exchange Insights account");
				link.setToolTipText("One-click: opens exchange-insights.gg to approve linking this character - no token to copy.");
				link.setAlignmentX(Component.LEFT_ALIGNMENT);
				link.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
				link.addActionListener(e -> startOneClickLink());
				accountRow.add(link);
			}
			accountRow.add(Box.createVerticalStrut(8));
		}
		accountRow.revalidate();
		accountRow.repaint();
	}

	// Kick off the one-click device link (from the top-of-panel button or the config toggle). Requires the
	// community repository to be enabled and a logged-in character; reads the account identity on the client
	// thread before starting.
	void startOneClickLink()
	{
		if (!repositoryClient.isEnabled())
		{
			JOptionPane.showMessageDialog(this,
				"Turn on the community repository in the plugin settings first, then link your account.",
				"Enable the repository", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (linking)
		{
			return; // a link is already in progress
		}
		clientThread.invokeLater(() ->
		{
			final long hash = client.getAccountHash();
			final Player local = client.getLocalPlayer();
			final String name = local != null ? local.getName() : null;
			SwingUtilities.invokeLater(() -> beginLink(hash, name));
		});
	}

	private void beginLink(long accountHash, String rsn)
	{
		if (accountHash == -1 || rsn == null || rsn.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"Log into OSRS first (so the plugin knows which character to link), then try again.",
				"Not logged in", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		linking = true;
		refreshAccountRow();
		repositoryClient.startDeviceLink(accountHash, rsn,
			start -> SwingUtilities.invokeLater(() -> onLinkStarted(start)),
			error -> SwingUtilities.invokeLater(() ->
			{
				linking = false;
				refreshAccountRow();
				JOptionPane.showMessageDialog(this, error, "Couldn't start linking", JOptionPane.WARNING_MESSAGE);
			}));
	}

	private void onLinkStarted(TemplateRepositoryClient.LinkStart start)
	{
		if (!linking)
		{
			return; // cancelled before the server answered
		}
		LinkBrowser.browse(start.verificationUrl);
		final long deadline = System.currentTimeMillis() + LINK_WINDOW_MS;
		final long intervalMs = Math.max(2, start.pollSeconds) * 1_000L;
		scheduleLinkPoll(start.deviceSecret, deadline, intervalMs);
	}

	private synchronized void scheduleLinkPoll(String deviceSecret, long deadline, long intervalMs)
	{
		if (linkPollTask != null)
		{
			linkPollTask.cancel(false);
		}
		linkPollTask = executor.schedule(
			() -> pollOnce(deviceSecret, deadline, intervalMs), intervalMs, TimeUnit.MILLISECONDS);
	}

	// One poll tick (executor thread). Stops on timeout; otherwise asks the server and routes the answer.
	private void pollOnce(String deviceSecret, long deadline, long intervalMs)
	{
		if (!linking)
		{
			return;
		}
		if (System.currentTimeMillis() > deadline)
		{
			SwingUtilities.invokeLater(() ->
			{
				if (linking)
				{
					linking = false;
					refreshAccountRow();
					JOptionPane.showMessageDialog(this,
						"The link request timed out. Click Link account to try again.",
						"Link timed out", JOptionPane.INFORMATION_MESSAGE);
				}
			});
			return;
		}
		repositoryClient.pollDeviceLink(deviceSecret,
			poll -> SwingUtilities.invokeLater(() -> handlePoll(poll, deviceSecret, deadline, intervalMs)),
			err -> reschedulePoll(deviceSecret, deadline, intervalMs)); // transient error - keep trying until the deadline
	}

	private void reschedulePoll(String deviceSecret, long deadline, long intervalMs)
	{
		if (linking && System.currentTimeMillis() <= deadline)
		{
			scheduleLinkPoll(deviceSecret, deadline, intervalMs);
		}
		else if (linking)
		{
			SwingUtilities.invokeLater(() ->
			{
				linking = false;
				refreshAccountRow();
			});
		}
	}

	private void handlePoll(TemplateRepositoryClient.LinkPoll poll, String deviceSecret, long deadline, long intervalMs)
	{
		if (!linking)
		{
			return;
		}
		final String status = poll != null && poll.status != null ? poll.status : "";
		switch (status)
		{
			case "approved":
				finishLink(poll.token);
				break;
			case "pending":
				reschedulePoll(deviceSecret, deadline, intervalMs);
				break;
			case "denied":
				linking = false;
				refreshAccountRow();
				JOptionPane.showMessageDialog(this,
					"The link was denied in the browser. Nothing was linked.",
					"Not linked", JOptionPane.INFORMATION_MESSAGE);
				break;
			default: // expired / invalid / claimed
				linking = false;
				refreshAccountRow();
				JOptionPane.showMessageDialog(this,
					"The link request is no longer valid. Click Link account to try again.",
					"Link expired", JOptionPane.INFORMATION_MESSAGE);
				break;
		}
	}

	private void finishLink(String token)
	{
		linking = false;
		if (token != null && !token.isEmpty())
		{
			// Persist the issued token so the link survives restarts and future logins re-assert identity, and
			// so the same token can be pasted into the Exchange Insights plugin if the player wants both. Setting
			// it fires onConfigChanged(eiAccountToken) in the plugin, which (idempotently) re-links identity too.
			configManager.setConfiguration(BankTemplatesConfig.GROUP, "eiAccountToken", token);
		}
		webSyncLinked = true;
		linkedHandle = null;
		refreshLinkStatus(); // fetch the handle for the "linked as …" label
		refreshAccountRow();
		syncWebTemplates();  // pull this account's website templates down now
		rebuildOnEdt();
		JOptionPane.showMessageDialog(this,
			"Your account is linked. Your bank templates now sync with exchange-insights.gg.",
			"Account linked", JOptionPane.INFORMATION_MESSAGE);
	}

	// Latest bank value reported by the snapshot sync (gp at live GE mid prices); -1 until the first sync.
	private long bankValue = -1;

	// Called (on the EDT) when a bank snapshot lands and the server reports the bank's live value.
	void setBankValue(long value)
	{
		bankValue = value;
		refreshAccountRow();
	}

	// Compact gp formatting for the panel (214.3M, 1.20B, 53,120), matching how players talk about gp.
	private static String fmtGp(long gp)
	{
		if (gp >= 1_000_000_000L)
		{
			return String.format(Locale.ROOT, "%.2fB", gp / 1_000_000_000.0);
		}
		if (gp >= 10_000_000L)
		{
			return String.format(Locale.ROOT, "%.1fM", gp / 1_000_000.0);
		}
		if (gp >= 1_000_000L)
		{
			return String.format(Locale.ROOT, "%.2fM", gp / 1_000_000.0);
		}
		return String.format(Locale.ROOT, "%,d", gp);
	}

	// Fetch the linked Exchange Insights handle for the status row, when a token is set. Best-effort: a
	// failure (offline, revoked) just leaves the row without a name.
	void refreshLinkStatus()
	{
		if (!hasEiToken())
		{
			return;
		}
		repositoryClient.pingLink(config.eiAccountToken(),
			handle -> SwingUtilities.invokeLater(() ->
			{
				linkedHandle = handle;
				refreshAccountRow();
			}),
			err ->
			{
			});
	}

	private void switchMode(String newMode)
	{
		if (mode.equals(newMode))
		{
			return;
		}
		mode = newMode;
		if (UPDATES.equals(mode))
		{
			markUpdateSeen();
		}
		searchBar.setVisible(!UPDATES.equals(mode));
		styleTabs();
		if (BROWSE.equals(mode))
		{
			newSearch();
		}
		else
		{
			rebuildOnEdt();
		}
	}

	private void styleTabs()
	{
		localTab.setBackground(LOCAL.equals(mode) ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
		localTab.setForeground(LOCAL.equals(mode) ? Color.BLACK : Color.WHITE);
		browseTab.setBackground(BROWSE.equals(mode) ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
		browseTab.setForeground(BROWSE.equals(mode) ? Color.BLACK : Color.WHITE);
		updatesTab.setBackground(UPDATES.equals(mode) ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
		updatesTab.setForeground(UPDATES.equals(mode) ? Color.BLACK : Color.WHITE);
	}

	// Each tab on its own full-width row, so the labels never truncate. Updates lives at the bottom of the
	// panel (see the constructor), not in this top row.
	private void layoutTabs()
	{
		tabsPanel.removeAll();
		tabsPanel.setLayout(new BoxLayout(tabsPanel, BoxLayout.Y_AXIS));

		addTabRow(localTab, false);
		addTabRow(browseTab, true);
		tabsPanel.revalidate();
		tabsPanel.repaint();
	}

	private void addTabRow(JButton tab, boolean gapAbove)
	{
		if (gapAbove)
		{
			tabsPanel.add(Box.createVerticalStrut(4));
		}
		tab.setAlignmentX(Component.LEFT_ALIGNMENT);
		tab.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		tabsPanel.add(tab);
	}

	private boolean updatesTabShown()
	{
		return config.alertUpdates() && latestUpdate != null && latestUpdate.version != null;
	}

	// True if the latest changelog version matches the one the user has already seen.
	private boolean updateAlreadySeen()
	{
		final String seen = configManager.getConfiguration(BankTemplatesConfig.GROUP, BankTemplatesConfig.LAST_SEEN_UPDATE_KEY);
		return latestUpdate != null && latestUpdate.version != null && latestUpdate.version.equals(seen);
	}

	private void markUpdateSeen()
	{
		if (latestUpdate == null || latestUpdate.version == null)
		{
			return;
		}
		final String seen = configManager.getConfiguration(BankTemplatesConfig.GROUP, BankTemplatesConfig.LAST_SEEN_UPDATE_KEY);
		if (!latestUpdate.version.equals(seen))
		{
			configManager.setConfiguration(BankTemplatesConfig.GROUP, BankTemplatesConfig.LAST_SEEN_UPDATE_KEY, latestUpdate.version);
		}
	}

	void rebuild()
	{
		SwingUtilities.invokeLater(() ->
		{
			styleTabs();
			rebuildOnEdt();
		});
	}

	private void rebuildOnEdt()
	{
		listContainer.removeAll();
		if (BROWSE.equals(mode))
		{
			buildBrowseView();
		}
		else if (UPDATES.equals(mode))
		{
			buildUpdatesView();
		}
		else
		{
			buildLocalView();
		}
		listContainer.revalidate();
		listContainer.repaint();
		// Keep the top-of-panel link status in step with the latest sync result (webSyncLinked) and the
		// repository-enabled state, both of which can change between rebuilds.
		refreshAccountRow();
	}

	// ---- Updates ----------------------------------------------------------------------------

	private void buildUpdatesView()
	{
		if (latestUpdate == null)
		{
			switchMode(LOCAL);
			return;
		}

		final JLabel heading = new JLabel("What's New?");
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setForeground(Color.WHITE);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		listContainer.add(heading);
		listContainer.add(Box.createVerticalStrut(8));

		// Full update history, newest first, each version separated by a divider.
		boolean first = true;
		for (Changelog.Entry entry : allUpdates)
		{
			if (entry == null || entry.version == null)
			{
				continue;
			}
			if (!first)
			{
				addUpdatesDivider();
			}
			first = false;
			renderUpdateVersion(entry);
		}
	}

	private void renderUpdateVersion(Changelog.Entry entry)
	{
		final JLabel version = new JLabel("Version " + entry.version);
		version.setFont(FontManager.getRunescapeBoldFont());
		version.setForeground(ColorScheme.BRAND_ORANGE);
		version.setAlignmentX(Component.LEFT_ALIGNMENT);
		version.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
		listContainer.add(version);

		// Known issues directly under the version.
		if (entry.knownIssues != null && !entry.knownIssues.isEmpty())
		{
			final JLabel kiHeading = new JLabel("Known issues");
			kiHeading.setFont(FontManager.getRunescapeBoldFont());
			kiHeading.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			kiHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
			kiHeading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			listContainer.add(kiHeading);
			for (String issue : entry.knownIssues)
			{
				listContainer.add(bulletLabel(issue, ColorScheme.LIGHT_GRAY_COLOR));
			}
			listContainer.add(Box.createVerticalStrut(8));
		}

		// Changelog.
		if (entry.notes != null && !entry.notes.isEmpty())
		{
			final JLabel clHeading = new JLabel("Changelog");
			clHeading.setFont(FontManager.getRunescapeBoldFont());
			clHeading.setForeground(Color.WHITE);
			clHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
			clHeading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			listContainer.add(clHeading);
			for (String note : entry.notes)
			{
				listContainer.add(bulletLabel(note, Color.WHITE));
			}
		}
	}

	// A thin horizontal divider between version blocks in the Updates history.
	private void addUpdatesDivider()
	{
		listContainer.add(Box.createVerticalStrut(8));
		final JPanel line = new JPanel();
		line.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		line.setPreferredSize(new Dimension(10, 1));
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		listContainer.add(line);
		listContainer.add(Box.createVerticalStrut(8));
	}

	// A wrapped bullet-point label sized for the side panel.
	private JLabel bulletLabel(String text, Color color)
	{
		// U+00B7 (middle dot), not U+2022 (bullet): the label inherits the RuneScape UI font, whose cmap has
		// no glyph for U+2022, so on macOS (no font substitution for a physical font) it renders as a .notdef
		// box that overstrikes the first letter. U+00B7 is in the RuneScape cmap, so it renders everywhere.
		final JLabel b = new JLabel("<html><body style='width:160px'>&#183;&nbsp;" + escape(text) + "</body></html>");
		b.setForeground(color);
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setBorder(BorderFactory.createEmptyBorder(0, 2, 7, 0));
		return b;
	}

	// ---- Local templates --------------------------------------------------------------------

	private void buildLocalView()
	{
		refreshOwnedCanon();
		if (query.isEmpty())
		{
			// Capture / new-template actions sit at the top, directly under the search bar.
			final JButton captureButton = styledButton("Capture current bank");
			captureButton.setHorizontalAlignment(SwingConstants.CENTER);
			captureButton.setAlignmentX(Component.LEFT_ALIGNMENT);
			captureButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			captureButton.setToolTipText("Save your current bank (all tabs, in order) as a new template");
			captureButton.addActionListener(e -> captureCurrentBank());
			listContainer.add(captureButton);

			listContainer.add(Box.createVerticalStrut(4));
			final JButton newButton = styledButton("New empty template");
			newButton.setHorizontalAlignment(SwingConstants.CENTER);
			newButton.setAlignmentX(Component.LEFT_ALIGNMENT);
			newButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			newButton.setToolTipText("Build a template from scratch - add items you don't own as placeholders");
			newButton.addActionListener(e -> createNewLayout());
			listContainer.add(newButton);
			listContainer.add(Box.createVerticalStrut(8));

			final boolean hasActive = templateManager.getActive() != null;
			final JButton remove = styledButton(hasActive ? "Disable Template" : "No template applied");
			remove.setEnabled(hasActive);
			remove.setAlignmentX(Component.LEFT_ALIGNMENT);
			remove.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
			remove.setToolTipText("Disable the active template and show your normal bank");
			if (hasActive)
			{
				remove.setBackground(new Color(120, 60, 60));
			}
			remove.addActionListener(e -> select(null));
			listContainer.add(remove);
			listContainer.add(Box.createVerticalStrut(10));
		}

		addLocalSection("Presets", templateManager.getPresets());
		final List<BankTemplate> userTemplates = templateManager.getUserTemplates();
		if (!userTemplates.isEmpty())
		{
			addLocalSection("My templates", userTemplates);
		}

		// Explain why My templates isn't syncing with the website, when we know the character isn't linked.
		if (Boolean.FALSE.equals(webSyncLinked) && repositoryClient.isEnabled())
		{
			listContainer.add(messageLabel("Your templates sync with the web at exchange-insights.gg when this "
				+ "character is linked to a free Exchange Insights account. Link it in one click with the "
				+ "\"Link Exchange Insights account\" button at the top of this panel (or paste your account token in "
				+ "the plugin's settings) - then create and edit these templates in your browser too."));
			listContainer.add(Box.createVerticalStrut(6));
		}

		listContainer.add(Box.createVerticalStrut(8));

		// Reorganise: the mode dropdown and a description of what the selected mode does, in one card.
		final JPanel reorgCard = new JPanel();
		reorgCard.setLayout(new BoxLayout(reorgCard, BoxLayout.Y_AXIS));
		reorgCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		reorgCard.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgCard.setBorder(reorgCardBorder(config.showReorgHelper()));

		final JLabel reorgLabel = new JLabel("Reorganise");
		reorgLabel.setForeground(Color.WHITE);
		reorgLabel.setToolTipText("Guide for rearranging your real bank to match the active template");
		reorgLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgCard.add(reorgLabel);
		reorgCard.add(Box.createVerticalStrut(4));

		final String off = "Off";
		final JComboBox<String> reorgMode = new JComboBox<>();
		reorgMode.addItem(off);
		for (BankTemplatesConfig.ReorgDisplay d : BankTemplatesConfig.ReorgDisplay.values())
		{
			reorgMode.addItem(d.toString());
		}
		final String initialSel = config.showReorgHelper() ? config.reorgDisplay().toString() : off;
		reorgMode.setSelectedItem(initialSel);
		reorgMode.setFocusable(false);
		reorgMode.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, reorgMode.getPreferredSize().height));
		reorgCard.add(reorgMode);
		reorgCard.add(Box.createVerticalStrut(6));

		final JLabel reorgDesc = new JLabel(reorgDescription(initialSel, off));
		reorgDesc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reorgDesc.setFont(FontManager.getRunescapeSmallFont());
		reorgDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgCard.add(reorgDesc);

		reorgMode.addActionListener(e ->
		{
			final String sel = (String) reorgMode.getSelectedItem();
			if (off.equals(sel))
			{
				configManager.setConfiguration(BankTemplatesConfig.GROUP, "showReorgHelper", false);
			}
			else
			{
				final BankTemplatesConfig.ReorgDisplay d = BankTemplatesConfig.ReorgDisplay.fromLabel(sel);
				if (d != null)
				{
					configManager.setConfiguration(BankTemplatesConfig.GROUP, "reorgDisplay", d);
				}
				configManager.setConfiguration(BankTemplatesConfig.GROUP, "showReorgHelper", true);
			}
			reorgCard.setBorder(reorgCardBorder(!off.equals(sel)));
			reorgDesc.setText(reorgDescription(sel, off));
			reorgCard.revalidate();
			onActiveChanged.run();
		});

		listContainer.add(reorgCard);
		listContainer.add(Box.createVerticalGlue());
	}

	// Reorganise card border: the orange accent bar only when a mode is active; when off, a plain border with
	// the same content inset (3px bar + 6px = 9px left) so nothing shifts as you toggle it on and off.
	private static javax.swing.border.Border reorgCardBorder(boolean active)
	{
		return active
			? BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 0, 0, ColorScheme.BRAND_ORANGE),
				BorderFactory.createEmptyBorder(6, 6, 6, 6))
			: BorderFactory.createEmptyBorder(6, 9, 6, 6);
	}

	// HTML so the text wraps inside the reorganise card. Width is sized to the side panel.
	private static String reorgDescription(String selectedLabel, String off)
	{
		final String text;
		if (off.equals(selectedLabel))
		{
			text = "Reorganise helper is off. Pick a mode to get guidance for rearranging your real bank to match the active template.";
		}
		else
		{
			final BankTemplatesConfig.ReorgDisplay d = BankTemplatesConfig.ReorgDisplay.fromLabel(selectedLabel);
			text = d != null ? d.getDescription() : "";
		}
		return "<html><body style='width:165px'>" + escape(text) + "</body></html>";
	}

	private void addLocalSection(String name, List<BankTemplate> templates)
	{
		boolean added = false;
		for (BankTemplate t : templates)
		{
			if (!matchesQuery(t))
			{
				continue;
			}
			if (!added)
			{
				listContainer.add(sectionLabel(name));
				added = true;
			}
			listContainer.add(buildLocalCard(t));
			listContainer.add(Box.createVerticalStrut(6));
		}
		if (added)
		{
			listContainer.add(Box.createVerticalStrut(6));
		}
	}

	private boolean matchesQuery(BankTemplate t)
	{
		if (query.isEmpty())
		{
			return true;
		}
		final String name = t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT);
		final String desc = t.getDescription() == null ? "" : t.getDescription().toLowerCase(Locale.ROOT);
		return name.contains(query) || desc.contains(query);
	}

	private JPanel buildLocalCard(BankTemplate template)
	{
		final boolean active = templateManager.isActive(template);
		final JPanel card = cardPanel(active);

		// Title in NORTH, buttons in SOUTH. NORTH always gets its full preferred height, so the name can't
		// be squeezed out; SOUTH gives the button row the card's full width, so WrapLayout wraps correctly
		// and every button (including Del on the second row) is shown. Putting the title in CENTER instead
		// let a wrapping button row collapse the title's height to zero and hide the name after sync.
		card.add(titleBlock(template.getName(), localMeta(template),
			active ? ColorScheme.BRAND_ORANGE : Color.WHITE), BorderLayout.NORTH);

		final JPanel buttons = buttonRow();
		buttons.add(activeOrUse(active, template));
		// One button does both: presets are read-only (View); your own templates open the editor (which
		// previews and edits in one window).
		if (template.isPreset())
		{
			buttons.add(iconButton("View", "Preview this template", () -> showPreview(template)));
		}
		else
		{
			final boolean editingThis = layoutEditor.isEditing(template);
			buttons.add(iconButton(editingThis ? "Done" : "Edit",
				editingThis ? "Finish editing this layout" : "View and edit this layout (add, move and arrange items - no need to own them)",
				() -> editTemplate(template)));
		}
		if (!template.isPreset() && repositoryClient.isEnabled())
		{
			final boolean update = template.isOwned() && template.getRepoId() != null;
			buttons.add(iconButton(update ? "Update" : "Share",
				update ? "Update your shared copy" : "Share to the community repository", () -> share(template)));
		}
		// Report only makes sense for templates you imported from someone else, not your own.
		if (template.getRepoId() != null && !template.isOwned() && repositoryClient.isEnabled())
		{
			buttons.add(iconButton("Report", "Report the shared version of this template", () -> reportRepo(template.getRepoId())));
		}
		// A shared, imported or web-synced template has a page on the site; link straight to it. Prefer your
		// own website copy (webId) over the community source it was imported from.
		final Long webLinkId = template.getWebId() != null ? template.getWebId() : template.getRepoId();
		if (webLinkId != null)
		{
			buttons.add(iconButton("Web", "Open this template on exchange-insights.gg", () -> openOnWeb(webLinkId)));
		}
		if (!template.isPreset())
		{
			buttons.add(iconButton("Del", "Delete this template", () -> deleteLocal(template)));
		}
		card.add(buttons, BorderLayout.SOUTH);
		return card;
	}

	// ---- Browse repository ------------------------------------------------------------------

	private void buildBrowseView()
	{
		if (!repositoryClient.isEnabled())
		{
			listContainer.add(buildEnablePrompt());
			return;
		}

		listContainer.add(buildSortRow());
		listContainer.add(Box.createVerticalStrut(6));
		listContainer.add(buildBrowseOnWebRow());
		listContainer.add(Box.createVerticalStrut(6));

		if (browseStatus != null)
		{
			listContainer.add(messageLabel(browseStatus));
			return;
		}

		final JLabel count = new JLabel("Count: " + browseTotal);
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		count.setAlignmentX(Component.LEFT_ALIGNMENT);
		listContainer.add(count);
		listContainer.add(Box.createVerticalStrut(6));

		// Re-sort the fetched page by how much of each template you own (most-owned first). Done here so it
		// always reflects the latest bank contents, even on a plain rebuild.
		if (CLOSEST_SORT.equals(browseSort) && ownedCanon != null)
		{
			browseResults.sort((a, b) -> Double.compare(ownershipScore(b), ownershipScore(a)));
		}

		for (RemoteTemplate rt : browseResults)
		{
			listContainer.add(buildRemoteCard(rt));
			listContainer.add(Box.createVerticalStrut(6));
		}

		listContainer.add(buildPaginationRow());
	}

	private JPanel buildBrowseOnWebRow()
	{
		final JButton web = new JButton("Browse on web");
		web.setFocusPainted(false);
		web.setToolTipText("Open the community bank templates on exchange-insights.gg in your browser");
		web.addActionListener(e -> LinkBrowser.browse("https://exchange-insights.gg/tools/osrs-bank-templates"));

		final JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(web, BorderLayout.CENTER);
		return row;
	}

	// Open a single template's page on the site (?t=<repoId> deep-links straight to it).
	private void openOnWeb(long repoId)
	{
		LinkBrowser.browse("https://exchange-insights.gg/tools/osrs-bank-templates?t=" + repoId);
	}

	private JPanel buildSortRow()
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel label = new JLabel("Sort");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(label, BorderLayout.WEST);

		final JComboBox<String> sort = new JComboBox<>(SORT_LABELS);
		sort.setSelectedIndex(sortIndex());
		sort.addActionListener(e ->
		{
			browseSort = SORT_KEYS[sort.getSelectedIndex()];
			newSearch();
		});
		row.add(sort, BorderLayout.CENTER);
		return row;
	}

	private int sortIndex()
	{
		for (int i = 0; i < SORT_KEYS.length; i++)
		{
			if (SORT_KEYS[i].equals(browseSort))
			{
				return i;
			}
		}
		return 0;
	}

	private JPanel buildPaginationRow()
	{
		final int currentPage = browseOffset / PAGE_SIZE + 1;
		final int totalPages = Math.max(currentPage, (browseTotal + PAGE_SIZE - 1) / PAGE_SIZE);
		final boolean hasPrev = browseOffset > 0;

		// Nav buttons on one row; the "Page X of N" label on its own row below. The list has no horizontal
		// scrollbar, so a row wider than the panel clips under the scrollbar - keeping the label off the
		// button row means large (4-6 digit) page counts never widen it.
		final JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 2));
		nav.setBackground(ColorScheme.DARK_GRAY_COLOR);
		nav.setAlignmentX(Component.LEFT_ALIGNMENT);
		nav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		nav.add(pagerButton("«", "First page", hasPrev, () ->
		{
			browseOffset = 0;
			loadBrowse();
		}));
		nav.add(pagerButton("< Prev", "Previous page", hasPrev, () ->
		{
			browseOffset = Math.max(0, browseOffset - PAGE_SIZE);
			loadBrowse();
		}));
		nav.add(pagerButton("Next >", "Next page", browseHasMore, () ->
		{
			browseOffset += PAGE_SIZE;
			loadBrowse();
		}));
		nav.add(pagerButton("»", "Last page", browseTotal > 0 && currentPage < totalPages, () ->
		{
			browseOffset = (totalPages - 1) * PAGE_SIZE;
			loadBrowse();
		}));

		final JLabel page = new JLabel(browseTotal > 0
			? "Page " + currentPage + " of " + totalPages
			: "Page " + currentPage);
		page.setFont(FontManager.getRunescapeSmallFont());
		page.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		final JPanel pageRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		pageRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		pageRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		pageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		pageRow.add(page);

		final JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setAlignmentX(Component.LEFT_ALIGNMENT);
		container.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
		container.add(nav);
		container.add(pageRow);
		return container;
	}

	// A compact pager button (First/Prev/Next/Last), kept narrow so the row fits the fixed panel width.
	private JButton pagerButton(String text, String tooltip, boolean enabled, Runnable action)
	{
		final JButton b = styledButton(text);
		b.setToolTipText(tooltip);
		b.setEnabled(enabled);
		b.addActionListener(e -> action.run());
		return b;
	}

	private JPanel buildRemoteCard(RemoteTemplate rt)
	{
		final JPanel card = cardPanel(false);
		card.add(remoteTitleBlock(rt), BorderLayout.CENTER);

		final JPanel buttons = buttonRow();
		buttons.add(iconButton("View", "Preview this template", () -> showPreview(rt.toTemplate())));
		buttons.add(iconButton("Web", "Open this template on exchange-insights.gg", () -> openOnWeb(rt.id)));
		buttons.add(iconButton("Import", "Save a copy to My Templates", () -> importRemote(rt)));
		buttons.add(iconButton("Report", "Report this template", () -> reportRepo(rt.id, this::loadBrowse)));
		if (ownsRemote(rt.id))
		{
			buttons.add(iconButton("Del", "Delete your shared template", () -> deleteRemote(rt.id)));
		}
		card.add(buttons, BorderLayout.SOUTH);
		return card;
	}

	private boolean ownsRemote(long repoId)
	{
		for (BankTemplate t : templateManager.getUserTemplates())
		{
			if (t.isOwned() && t.getRepoId() != null && t.getRepoId() == repoId)
			{
				return true;
			}
		}
		return false;
	}

	private void newSearch()
	{
		browseOffset = 0;
		loadBrowse();
	}

	private void loadBrowse()
	{
		browseStatus = "Searching…";
		browseResults.clear();
		rebuildOnEdt();
		// "Closest to my bank" is sorted client-side (see buildBrowseView); fetch in a neutral order.
		final String serverSort = CLOSEST_SORT.equals(browseSort) ? "imported" : browseSort;
		repositoryClient.search(searchBar.getText(), serverSort, browseOffset,
			page -> SwingUtilities.invokeLater(() ->
			{
				browseResults.clear();
				if (page.templates != null)
				{
					browseResults.addAll(page.templates);
				}
				browseHasMore = page.hasMore;
				browseTotal = page.total;
				browseStatus = browseResults.isEmpty() ? "No templates found." : null;
				rebuildOnEdt();
			}),
			error -> SwingUtilities.invokeLater(() ->
			{
				browseStatus = error;
				rebuildOnEdt();
			}));
	}

	private void importRemote(RemoteTemplate rt)
	{
		final BankTemplate t = rt.toTemplate();
		t.setRepoId(rt.id);
		t.setOwned(false);
		t.setName(uniqueName(capName(t.getName())));
		if (templateManager.saveUserTemplate(t))
		{
			// Record the import, then refresh the Browse list so the count reflects the server's
			// (deduped) truth rather than an optimistic guess.
			repositoryClient.recordImport(rt.id, () -> SwingUtilities.invokeLater(this::loadBrowse));
			JOptionPane.showMessageDialog(this, "Imported \"" + t.getName() + "\" to My Templates.", "Imported", JOptionPane.INFORMATION_MESSAGE);
		}
		else
		{
			JOptionPane.showMessageDialog(this, "Could not import that template.", "Import failed", JOptionPane.ERROR_MESSAGE);
		}
	}

	private boolean requireLogin()
	{
		if (!repositoryClient.hasIdentity())
		{
			JOptionPane.showMessageDialog(this,
				"Log in to your RuneScape account first - sharing, reporting and deleting are tied to your account.",
				"Not logged in", JOptionPane.WARNING_MESSAGE);
			return false;
		}
		return true;
	}

	private void reportRepo(long repoId)
	{
		reportRepo(repoId, null);
	}

	private void reportRepo(long repoId, Runnable onReported)
	{
		if (!requireLogin())
		{
			return;
		}
		final int confirm = JOptionPane.showConfirmDialog(this,
			"Report this template to the moderators?", "Report template", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		repositoryClient.report(repoId,
			() -> SwingUtilities.invokeLater(() ->
			{
				if (onReported != null)
				{
					onReported.run();
				}
				JOptionPane.showMessageDialog(this, "Reported. Thanks.", "Reported", JOptionPane.INFORMATION_MESSAGE);
			}),
			error -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, error, "Report failed", JOptionPane.WARNING_MESSAGE)));
	}

	private void deleteRemote(long repoId)
	{
		if (!requireLogin())
		{
			return;
		}
		final int confirm = JOptionPane.showConfirmDialog(this,
			"Remove your shared template from the community repository?", "Delete shared template", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		repositoryClient.delete(repoId,
			() -> SwingUtilities.invokeLater(() ->
			{
				unlinkLocal(repoId);
				loadBrowse();
			}),
			error -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, error, "Delete failed", JOptionPane.WARNING_MESSAGE)));
	}

	// After a shared template is deleted from the repo, unlink the local owned copy so it stops
	// offering Update/Delete-from-repo.
	private void unlinkLocal(long repoId)
	{
		for (BankTemplate t : templateManager.getUserTemplates())
		{
			if (t.isOwned() && t.getRepoId() != null && t.getRepoId() == repoId)
			{
				t.setRepoId(null);
				t.setOwned(false);
				templateManager.saveUserTemplate(t);
			}
		}
	}

	private void share(BankTemplate template)
	{
		final boolean update = template.isOwned() && template.getRepoId() != null;

		final JPanel message = new JPanel(new BorderLayout(0, 8));
		message.add(new JLabel("<html><body style='width:240px'>"
			+ (update ? "Update your shared copy of \"" : "Share \"") + escape(template.getName()) + "\""
			+ (update ? "?" : " to the community repository?<br>It will be visible to other players.")
			+ "</body></html>"), BorderLayout.NORTH);

		final JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

		final JLabel descLabel = new JLabel("Description (optional, max " + MAX_DESCRIPTION_LENGTH + " chars):");
		descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		center.add(descLabel);
		center.add(Box.createVerticalStrut(4));

		final javax.swing.JTextArea descArea = new javax.swing.JTextArea(
			template.getDescription() == null ? "" : template.getDescription(), 4, 24);
		descArea.setLineWrap(true);
		descArea.setWrapStyleWord(true);
		descArea.setToolTipText("Explain your layout - why certain tabs, what it's for, etc. Shown to other players.");
		final JScrollPane descScroll = new JScrollPane(descArea);
		descScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		descScroll.setPreferredSize(new Dimension(280, 84));
		center.add(descScroll);
		center.add(Box.createVerticalStrut(8));

		final javax.swing.JCheckBox anon = new javax.swing.JCheckBox(
			"Share anonymously (show me as \"Anonymous\")", template.isSharedAnonymously());
		anon.setToolTipText("Other players won't see your RuneScape name. The repository still records it "
			+ "privately for moderation.");
		anon.setAlignmentX(Component.LEFT_ALIGNMENT);
		center.add(anon);

		message.add(center, BorderLayout.CENTER);

		final int confirm = JOptionPane.showConfirmDialog(this, message,
			update ? "Update template" : "Share template", JOptionPane.OK_CANCEL_OPTION);
		if (confirm != JOptionPane.OK_OPTION)
		{
			return;
		}

		if (!requireLogin())
		{
			return;
		}

		final boolean anonymous = anon.isSelected();
		template.setSharedAnonymously(anonymous);

		String desc = descArea.getText() == null ? "" : descArea.getText().trim();
		if (desc.length() > MAX_DESCRIPTION_LENGTH)
		{
			desc = desc.substring(0, MAX_DESCRIPTION_LENGTH);
		}
		template.setDescription(desc.isEmpty() ? null : desc);
		templateManager.saveUserTemplate(template);

		clientThread.invoke(() ->
		{
			final Player local = client.getLocalPlayer();
			final String author = local != null && local.getName() != null ? local.getName() : "";

			if (update)
			{
				repositoryClient.update(template.getRepoId(), template, author, anonymous,
					() -> SwingUtilities.invokeLater(() ->
					{
						templateManager.saveUserTemplate(template);
						JOptionPane.showMessageDialog(this, "Updated your shared \"" + template.getName() + "\".", "Updated", JOptionPane.INFORMATION_MESSAGE);
					}),
					error -> SwingUtilities.invokeLater(() ->
						JOptionPane.showMessageDialog(this, error, "Update failed", JOptionPane.WARNING_MESSAGE)));
			}
			else
			{
				repositoryClient.create(template, author, anonymous,
					newId -> SwingUtilities.invokeLater(() ->
					{
						if (newId != null)
						{
							template.setRepoId(newId);
							template.setOwned(true);
						}
						templateManager.saveUserTemplate(template);
						rebuildOnEdt();
						JOptionPane.showMessageDialog(this, "Shared \"" + template.getName() + "\" to the repository.", "Shared", JOptionPane.INFORMATION_MESSAGE);
					}),
					error -> SwingUtilities.invokeLater(() ->
						JOptionPane.showMessageDialog(this, error, "Share failed", JOptionPane.WARNING_MESSAGE)));
			}
		});
	}

	private void captureCurrentBank()
	{
		final String input = JOptionPane.showInputDialog(this, "Name for the captured template (max " + MAX_NAME_LENGTH + " chars):", "My bank");
		if (input == null || input.trim().isEmpty())
		{
			return;
		}
		final String name = uniqueName(capName(input));

		clientThread.invoke(() ->
		{
			final BankTemplate captured = BankCapture.capture(client, itemManager, name);
			SwingUtilities.invokeLater(() ->
			{
				if (captured == null)
				{
					JOptionPane.showMessageDialog(this, "Open your bank first, then capture.", "Capture failed", JOptionPane.WARNING_MESSAGE);
					return;
				}
				if (templateManager.saveUserTemplate(captured))
				{
					rebuildOnEdt();
					JOptionPane.showMessageDialog(this,
						"Captured your bank as \"" + name + "\" (" + captured.tabCount() + " tabs).", "Captured", JOptionPane.INFORMATION_MESSAGE);
				}
				else
				{
					JOptionPane.showMessageDialog(this, "Could not save the captured template.", "Capture failed", JOptionPane.ERROR_MESSAGE);
				}
			});
		});
	}

	private void deleteLocal(BankTemplate template)
	{
		final boolean shared = template.isOwned() && template.getRepoId() != null && repositoryClient.isEnabled();
		// A duplex-synced template lives in your website My Templates too, so deleting it here has to delete
		// it there - otherwise the next sync just pulls it straight back.
		final boolean webBacked = !template.isOwned() && template.getWebId() != null && repositoryClient.isEnabled();
		final String msg = shared
			? "Delete \"" + template.getName() + "\" locally AND remove it from the community repository?"
			: webBacked
				? "Delete \"" + template.getName() + "\" locally AND from your Exchange Insights website My Templates?"
				: "Delete template \"" + template.getName() + "\"?";
		final int choice = JOptionPane.showConfirmDialog(this, msg, "Delete template", JOptionPane.YES_NO_OPTION);
		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}

		final Runnable removeLocally = () -> SwingUtilities.invokeLater(() ->
		{
			templateManager.deleteUserTemplate(template);
			rebuildOnEdt();
			onActiveChanged.run();
		});
		final Consumer<String> onDeleteError = error -> SwingUtilities.invokeLater(() ->
		{
			final int alsoLocal = JOptionPane.showConfirmDialog(this,
				error + "\n\nDelete the local copy anyway?", "Delete failed",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (alsoLocal == JOptionPane.YES_OPTION)
			{
				removeLocally.run();
			}
		});

		if (shared)
		{
			if (!requireLogin())
			{
				return;
			}
			repositoryClient.delete(template.getRepoId(), removeLocally, onDeleteError);
		}
		else if (webBacked)
		{
			if (!requireLogin())
			{
				return;
			}
			// If this copy was also imported from someone else's community share, drop that import count too.
			if (template.getRepoId() != null)
			{
				repositoryClient.unimport(template.getRepoId());
			}
			repositoryClient.delete(template.getWebId(), removeLocally, onDeleteError);
		}
		else
		{
			// Deleting an imported copy: tell the repo so its import count drops back.
			if (!template.isOwned() && template.getRepoId() != null && repositoryClient.isEnabled())
			{
				repositoryClient.unimport(template.getRepoId());
			}
			removeLocally.run();
		}
	}

	private JPanel buildEnablePrompt()
	{
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel heading = new JLabel("Community templates");
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setForeground(Color.WHITE);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(heading);
		panel.add(Box.createVerticalStrut(4));

		panel.add(messageLabel("Browse and share bank layouts made by other players."));
		panel.add(messageLabel("This sends your IP address to a third-party server that is not "
			+ "controlled or verified by RuneLite developers."));
		panel.add(Box.createVerticalStrut(4));

		final JButton enable = styledButton("Enable community repository");
		enable.setBackground(ColorScheme.BRAND_ORANGE);
		enable.setForeground(Color.BLACK);
		enable.setAlignmentX(Component.LEFT_ALIGNMENT);
		enable.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		enable.addActionListener(e ->
		{
			configManager.setConfiguration(BankTemplatesConfig.GROUP, "enableRepository", true);
			newSearch();
			syncWebTemplates(); // start the duplex sync loop now that the repo is on
		});
		panel.add(enable);
		return panel;
	}

	// ---- Shared UI helpers ------------------------------------------------------------------

	private static final Color UPVOTE_COLOR = new Color(110, 190, 110);
	private static final Color DOWNVOTE_COLOR = new Color(220, 110, 110);
	// The narrow side panel can't fit long names (e.g. auto-generated Exchange Insights display names),
	// so clamp them with an ellipsis and show the full text on hover.
	private static final int CARD_NAME_MAX_WIDTH = 205;
	private static final int CARD_AUTHOR_MAX_WIDTH = 118;

	// A left-aligned label whose text is ellipsised to fit maxWidth px; the full text shows in a tooltip.
	private static JLabel clampedLabel(String text, Font font, Color fg, int maxWidth)
	{
		final JLabel label = new JLabel();
		label.setFont(font);
		label.setForeground(fg);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		final String full = text == null ? "" : text;
		final FontMetrics fm = label.getFontMetrics(font);
		if (fm.stringWidth(full) <= maxWidth)
		{
			label.setText(full);
			return label;
		}
		// Walk code points (not chars) so a surrogate pair - e.g. an emoji in a remote name - is never
		// split in half, and measure the accumulated prefix as a string for correct kerned widths.
		final int ellW = fm.stringWidth("\u2026");
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < full.length(); )
		{
			final int cp = full.codePointAt(i);
			final int next = i + Character.charCount(cp);
			if (fm.stringWidth(full.substring(0, next)) + ellW > maxWidth)
			{
				break;
			}
			sb.append(full, i, next);
			i = next;
		}
		label.setText(sb.toString().trim() + "\u2026");
		label.setToolTipText(full);
		return label;
	}

	// Browse card title: name + imports (▲, green) and reports (▼, red) shown as up/down votes.
	private JPanel remoteTitleBlock(RemoteTemplate rt)
	{
		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		final JLabel name = clampedLabel(rt.name, FontManager.getRunescapeFont(), Color.WHITE, CARD_NAME_MAX_WIDTH);
		text.add(name);

		final JPanel votes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		votes.setOpaque(false);
		votes.setAlignmentX(Component.LEFT_ALIGNMENT);
		votes.add(voteLabel("<html><span style='font-family:Dialog'>&#9650;</span>&nbsp;" + rt.downloads + "</html>", UPVOTE_COLOR));
		votes.add(voteLabel("<html><span style='font-family:Dialog'>&#9660;</span>&nbsp;" + rt.reports + "</html>", DOWNVOTE_COLOR));

		final String by = rt.anonymous || rt.author == null || rt.author.isEmpty() ? "Anonymous" : rt.author;
		final JLabel author = clampedLabel("by " + by, FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR, CARD_AUTHOR_MAX_WIDTH);
		votes.add(author);

		text.add(votes);

		final JLabel meta = new JLabel(remoteMeta(rt));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(meta);
		return text;
	}

	// "x / y items" (x = how many of the template's items you own) plus tab count, for a Browse card. Falls
	// back to "y items" until the bank has loaded.
	private String remoteMeta(RemoteTemplate rt)
	{
		final BankTemplate t = rt.toTemplate();
		final int total = t.itemCount();
		final int tabs = t.tabCount();
		final String items = ownedCanon != null
			? ownedOfTemplate(t, ownedCanon) + " / " + total + " items"
			: total + " items";
		return items + (tabs > 1 ? " · " + tabs + " tabs" : "");
	}

	// Ranking score for the "Items owned" sort. Not a plain percentage: it weights the absolute number of the
	// template's items you own by the fraction you own (owned^2 / total), so owning more items is preferred and
	// a near-complete small template doesn't outrank a big one you have a lot of - e.g. 487/936 beats 180/276.
	private double ownershipScore(RemoteTemplate rt)
	{
		if (ownedCanon == null)
		{
			return 0;
		}
		final BankTemplate t = rt.toTemplate();
		final int total = t.itemCount();
		if (total == 0)
		{
			return 0;
		}
		final int owned = ownedOfTemplate(t, ownedCanon);
		return (double) owned * owned / total;
	}

	private JLabel voteLabel(String html, Color color)
	{
		// The count keeps the panel's RuneScape pixel font (its cmap has the digits, so they render on every
		// platform). The caller wraps only the ▲/▼ arrow in an HTML logical-font span: the RuneScape font has
		// no ▲/▼ glyph, and a logical font gets composite glyph fallback everywhere (incl. macOS), so the
		// arrow renders while the count keeps the RuneScape look.
		final JLabel label = new JLabel(html);
		label.setForeground(color);
		return label;
	}

	private JPanel titleBlock(String nameText, String metaText, Color nameColor)
	{
		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		// Imported templates keep their remote name, so clamp it exactly like the Browse cards do.
		final JLabel name = clampedLabel(nameText, FontManager.getRunescapeFont(), nameColor, CARD_NAME_MAX_WIDTH);
		text.add(name);

		final JLabel meta = new JLabel(metaText);
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(meta);
		return text;
	}

	private JButton activeOrUse(boolean active, BankTemplate template)
	{
		final JButton button = styledButton(active ? "Disable" : "Enable");
		button.setToolTipText(active ? "Stop applying this template" : "Apply this template to your bank view");
		button.addActionListener(e -> select(active ? null : template));
		return button;
	}

	private JPanel buttonRow()
	{
		// Left-aligned; WrapLayout flows onto a second row if they don't all fit. Min width 0 so the card can
		// shrink to the panel width (forcing the wrap) instead of overflowing the right edge and clipping.
		final JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 2, 2));
		buttons.setOpaque(false);
		buttons.setMinimumSize(new Dimension(0, 0));
		return buttons;
	}

	private JPanel cardPanel(boolean active)
	{
		// Cap the card's max height to its own preferred height so the vertical BoxLayout it sits in can't
		// stretch it to fill leftover space (which left a tall gap in the card before the list filled out).
		// Width still stretches to the panel.
		final JPanel card = new JPanel(new BorderLayout(0, 2))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(active ? ACTIVE_BORDER : CARD_BORDER);
		card.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (!active)
		{
			card.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					card.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
			});
		}
		return card;
	}

	private JLabel sectionLabel(String text)
	{
		final JLabel label = new JLabel(text.toUpperCase());
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JLabel messageLabel(String text)
	{
		final JLabel label = new JLabel("<html><body style='width:180px'>" + escape(text) + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JButton styledButton(String text)
	{
		final JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setHorizontalAlignment(SwingConstants.CENTER);
		button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		button.setForeground(Color.WHITE);
		button.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		return button;
	}

	// A vertical BoxLayout list that always matches the scroll viewport's width (never wider), so a wide child
	// can't push the content out and shift full-width buttons' centred text off to the left.
	private static final class ListPanel extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return Math.max(16, visibleRect.height - 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private JButton iconButton(String text, String tooltip, Runnable action)
	{
		final JButton button = styledButton(text);
		button.setToolTipText(tooltip);
		button.addActionListener(e -> action.run());
		return button;
	}

	private String localMeta(BankTemplate template)
	{
		// Owned = you uploaded it; an imported template has a repo id but isn't owned; otherwise local-only.
		final String kind = template.isPreset() ? "Preset"
			: template.isOwned() ? "Shared by you"
			: template.getRepoId() != null ? "Imported"
			: "Custom";
		final int total = template.itemCount();
		final int tabs = template.tabCount();
		// "x / y items" when the bank is known (x = how many of the template's items you currently own),
		// otherwise just "y items" until the bank has loaded.
		final String items = ownedCanon != null
			? ownedOfTemplate(template, ownedCanon) + " / " + total + " items"
			: total + " items";
		return kind + " · " + items + (tabs > 1 ? " · " + tabs + " tabs" : "");
	}

	// Recompute the owned-items set on the client thread (reading client state isn't EDT-safe), then refresh
	// the cards if it changed. Triggered when the local view is built and whenever the bank container changes,
	// so the "x / y items" counts fill in (and update) live without leaving and re-entering My Templates.
	void refreshOwnedCanon()
	{
		clientThread.invoke(() ->
		{
			final long accountHash = client.getAccountHash();
			// Switched account (or logged out): the old set belonged to a different bank - drop it so the new
			// account's live bank or cache fills in instead.
			if (accountHash != ownedAccountHash)
			{
				ownedAccountHash = accountHash;
				ownedCanon = null;
				countsChanged();
			}

			final Set<Integer> live = ownedBankCanonical();
			if (live != null)
			{
				if (!Objects.equals(live, ownedCanon))
				{
					ownedCanon = live;
					countsChanged();
					// Persist this account's latest snapshot (off the client thread - file IO).
					if (accountHash != -1)
					{
						executor.execute(() -> ownedCache.put(accountHash, live));
					}
				}
			}
			else if (ownedCanon == null && accountHash != -1)
			{
				// Bank not loaded yet - show the last-known snapshot for this account until it recalculates.
				executor.execute(() ->
				{
					final Set<Integer> cached = ownedCache.get(accountHash);
					if (cached != null)
					{
						SwingUtilities.invokeLater(() ->
						{
							// Only if the live bank still hasn't taken over and we're still on this account.
							if (ownedCanon == null && accountHash == ownedAccountHash)
							{
								ownedCanon = cached;
								countsChanged();
							}
						});
					}
				});
			}
		});
	}

	// The "x / y items" counts are shown on My Templates and Browse cards, so rebuild on either (a Browse
	// rebuild reuses the cached results - no re-fetch). The Updates view just keeps the freshly-computed set.
	private void countsChanged()
	{
		if (LOCAL.equals(mode) || BROWSE.equals(mode))
		{
			rebuild();
		}
	}

	// Variant-collapsed ids the player owns (qty > 0). Bank placeholders (qty 0) and bank fillers (the 🚫
	// reserved-slot item) are excluded, so neither counts as owned. Returns null if the bank container hasn't
	// loaded yet. Client thread only.
	private Set<Integer> ownedBankCanonical()
	{
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return null;
		}
		final Set<Integer> owned = new HashSet<>();
		for (Item it : bank.getItems())
		{
			if (it.getId() > 0 && it.getId() != BankTemplate.FILLER && it.getQuantity() > 0)
			{
				owned.add(ItemVariationMapping.map(it.getId()));
			}
		}
		return owned;
	}

	// How many of the template's items the player currently has in their bank (variant-aware).
	private int ownedOfTemplate(BankTemplate template, Set<Integer> owned)
	{
		int n = 0;
		for (TabLayout t : template.getTabs())
		{
			for (Integer v : t.getLayout())
			{
				if (v != null && v > 0 && v != BankTemplate.FILLER
					&& owned.contains(ItemVariationMapping.map(v)))
				{
					n++;
				}
			}
		}
		return n;
	}

	// Duplex "My Templates" sync with the linked Exchange Insights account. The account's editable in-game
	// templates (imports and in-plugin creations, plus copies previously pulled from the website) are sent
	// up; the server reconciles them last-write-wins against the website set and returns the authoritative
	// list, which we mirror back. Net effect: create or edit on either side and it shows on both; delete on
	// the website and it's removed in-game. Requires an Exchange Insights account linked via the Exchange
	// Insights plugin - webSyncLinked tracks that so the panel can explain it.
	// Kick a sync now (called on login / account switch, and when the repository is first enabled). Starts
	// the self-rescheduling sync loop if it isn't already running.
	void syncWebTemplates()
	{
		scheduleSync(0);
	}

	// A local change happened: push it up soon (debounced so a burst of edits collapses into one sync).
	void requestSync()
	{
		changeSeq++;
		scheduleSync(SYNC_DEBOUNCE_MS);
	}

	// Stop the sync loop and any in-flight account link poll (plugin shutdown).
	synchronized void stopSync()
	{
		if (pendingSyncTask != null)
		{
			pendingSyncTask.cancel(false);
			pendingSyncTask = null;
		}
		if (linkPollTask != null)
		{
			linkPollTask.cancel(false);
			linkPollTask = null;
		}
		linking = false;
	}

	// (Re)arm the single pending sync task, replacing any already scheduled one so the soonest wins.
	private synchronized void scheduleSync(long delayMs)
	{
		if (pendingSyncTask != null)
		{
			pendingSyncTask.cancel(false);
		}
		pendingSyncTask = executor.schedule(
			() -> SwingUtilities.invokeLater(this::runSync), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
	}

	// One sync pass. Gathers the editable set (imports + in-plugin creations, minus public shares), giving
	// each a stable client key, and sends it up; the result handler mirrors the authoritative set back and
	// schedules the next pass. Runs on the EDT so it reads the template store safely.
	private void runSync()
	{
		if (!repositoryClient.isEnabled())
		{
			// Loop idles while the repository is off; enabling it (or logging in) kicks it again.
			return;
		}
		// Snapshot the change counter before gathering, so afterSync knows exactly which changes this pass
		// covers - a change that arrives mid-sync bumps the counter and is retried, never dropped.
		final long startSeq = changeSeq;
		final List<BankTemplate> local = new ArrayList<>();
		for (BankTemplate t : templateManager.getUserTemplates())
		{
			if (!t.isPreset() && !t.isOwned())
			{
				ensureSyncKey(t);
				local.add(t);
			}
		}
		repositoryClient.sync(local, result -> SwingUtilities.invokeLater(() -> applySyncResult(result, startSeq)));
	}

	private void applySyncResult(TemplateRepositoryClient.SyncResult result, long startSeq)
	{
		if (result != null && result.linked)
		{
			// Suppress change events for the whole reconcile so sync's own writes (renames, removals) don't
			// re-trigger a sync in an endless loop.
			templateManager.setSuppressChangeEvents(true);
			try
			{
				reconcile(result);
			}
			finally
			{
				templateManager.setSuppressChangeEvents(false);
			}
			webSyncLinked = true;
			rebuildOnEdt();
		}
		else if (result != null)
		{
			webSyncLinked = false; // linked=false: leave everything local, just refresh the note
			rebuildOnEdt();
		}
		// result == null: sync failed (network/unverified) - change nothing.
		afterSync(result, startSeq);
	}

	// Mirror the authoritative website set back into the local store (called with change events suppressed).
	private void reconcile(TemplateRepositoryClient.SyncResult result)
	{
		final List<TemplateRepositoryClient.WebTemplate> remote =
			result.templates != null ? result.templates : Collections.<TemplateRepositoryClient.WebTemplate>emptyList();

		// Index the current duplex set so each returned row updates its existing local copy in place.
		final Map<String, BankTemplate> byKey = new HashMap<>();
		final Map<Long, BankTemplate> byWebId = new HashMap<>();
		for (BankTemplate t : templateManager.getUserTemplates())
		{
			if (t.isPreset() || t.isOwned())
			{
				continue;
			}
			if (t.getClientKey() != null)
			{
				byKey.put(t.getClientKey(), t);
			}
			if (t.getWebId() != null)
			{
				byWebId.put(t.getWebId(), t);
			}
		}

		final BankTemplate active = templateManager.getActive();
		final String activeName = active != null ? active.getName() : null;

		final Set<Long> seenIds = new HashSet<>();
		final Set<String> seenKeys = new HashSet<>();
		for (TemplateRepositoryClient.WebTemplate wt : remote)
		{
			if (wt.id <= 0)
			{
				continue;
			}
			seenIds.add(wt.id);
			if (wt.clientKey != null)
			{
				seenKeys.add(wt.clientKey);
			}
			BankTemplate localCopy = wt.clientKey != null ? byKey.get(wt.clientKey) : null;
			if (localCopy == null)
			{
				localCopy = byWebId.get(wt.id);
			}
			if (localCopy != null)
			{
				applyRemote(localCopy, wt);
			}
			else
			{
				final BankTemplate t = wt.toTemplate();
				t.setOwned(false);
				t.setWebSynced(true);
				t.setWebId(wt.id);
				t.setClientKey(wt.clientKey);
				t.setUpdatedAt(wt.updatedAt);
				t.setName(uniqueName(capName(t.getName())));
				templateManager.saveSyncedTemplate(t);
			}
		}

		// Remove local copies that were web-backed (had a webId) but are gone from the authoritative set -
		// they were deleted on the website. A template that was never synced up (webId still null - e.g.
		// one the server declined at the private cap) is never touched, so nothing local is ever lost.
		for (BankTemplate t : new ArrayList<>(templateManager.getUserTemplates()))
		{
			if (t.isPreset() || t.isOwned() || t.getWebId() == null)
			{
				continue;
			}
			final boolean stillThere = seenIds.contains(t.getWebId())
				|| (t.getClientKey() != null && seenKeys.contains(t.getClientKey()));
			if (!stillThere)
			{
				templateManager.deleteUserTemplate(t);
			}
		}

		if (activeName != null && templateManager.getActive() == null)
		{
			final BankTemplate again = templateManager.findByName(activeName);
			if (again != null)
			{
				templateManager.setActive(again);
				onActiveChanged.run();
			}
		}
	}

	// Decide when the next sync runs: retry-with-backoff after a failure, a short retry when a change was
	// rate-limited and still needs pushing, otherwise the slow poll that pulls website-side changes down.
	private void afterSync(TemplateRepositoryClient.SyncResult result, long startSeq)
	{
		long next;
		if (result == null)
		{
			// Failure/offline: back off up to the poll interval, but keep trying so a queued change lands.
			syncBackoffMs = syncBackoffMs <= 0 ? SYNC_RETRY_MIN_MS : Math.min(syncBackoffMs * 2, SYNC_POLL_MS);
			next = syncBackoffMs;
		}
		else
		{
			syncBackoffMs = 0;
			if (result.applied)
			{
				// This pass pushed everything up to the snapshot; later changes (changeSeq > startSeq) remain.
				syncedSeq = Math.max(syncedSeq, startSeq);
			}
			if (!result.linked || !result.privateSync)
			{
				// Pushes can't (not linked) or won't (private sync off) apply - stop retrying them; the slow
				// poll still pulls website-side changes down.
				syncedSeq = Math.max(syncedSeq, changeSeq);
			}
			// A rate-limited push (linked + privateSync + !applied) advances neither, so it retries soon.
			final boolean pending = changeSeq > syncedSeq;
			next = pending ? SYNC_RETRY_MIN_MS : SYNC_POLL_MS;
		}
		scheduleSync(next);
	}

	// Give a template a stable client key (and nothing else) so the server can correlate it across syncs.
	// updatedAt is deliberately left untouched: only a genuine user edit (saveUserTemplate) stamps it, so a
	// pre-existing copy with no timestamp reads as "oldest" and pulls the website version down rather than
	// overwriting it.
	private void ensureSyncKey(BankTemplate t)
	{
		if (t.getClientKey() == null || t.getClientKey().isEmpty())
		{
			t.setClientKey(java.util.UUID.randomUUID().toString());
			templateManager.saveSyncedTemplate(t);
		}
	}

	// Overwrite a local copy with the authoritative website version (content the server returned already
	// won last-write-wins). Writes the server's updatedAt so it isn't mistaken for a fresh local edit and
	// pushed straight back. Re-keys via rename when the name changed so the manager's name-keyed store stays
	// consistent.
	private void applyRemote(BankTemplate localCopy, TemplateRepositoryClient.WebTemplate wt)
	{
		final BankTemplate fresh = wt.toTemplate();
		localCopy.setDescription(fresh.getDescription());
		localCopy.restoreTabsFrom(fresh); // copies tabs + columns
		localCopy.setWebId(wt.id);
		localCopy.setWebSynced(true);
		if (wt.clientKey != null)
		{
			localCopy.setClientKey(wt.clientKey);
		}
		localCopy.setUpdatedAt(wt.updatedAt);

		final String newName = capName(wt.name);
		if (!newName.isEmpty() && !newName.equals(localCopy.getName()) && templateManager.findByName(newName) == null)
		{
			templateManager.renameTemplate(localCopy, newName); // re-keys + persists
		}
		else
		{
			templateManager.saveSyncedTemplate(localCopy);
		}
	}

	private String capName(String s)
	{
		if (s == null)
		{
			return "";
		}
		final String t = s.trim();
		return t.length() > MAX_NAME_LENGTH ? t.substring(0, MAX_NAME_LENGTH).trim() : t;
	}

	private String uniqueName(String base)
	{
		String name = base;
		int n = 2;
		while (templateManager.findByName(name) != null)
		{
			name = base + " (" + n++ + ")";
		}
		return name;
	}

	private void select(BankTemplate template)
	{
		templateManager.setActive(template);
		rebuildOnEdt();
		onActiveChanged.run();
	}

	private void editTemplate(BankTemplate template)
	{
		if (layoutEditor.isEditing(template))
		{
			layoutEditor.finish();
		}
		else
		{
			TemplateEditor.open(this, itemManager, itemIndex, clientThread, layoutEditor, templateManager, template,
				() ->
				{
					rebuildOnEdt();
					maybePromptPushAfterEdit(template);
				});
		}
		rebuildOnEdt();
	}

	// After editing a template you've shared, offer to push the changes to your community copy. Imports and
	// local-only templates have nothing to push, so they're skipped.
	private void maybePromptPushAfterEdit(BankTemplate template)
	{
		if (!repositoryClient.isEnabled() || !template.isOwned() || template.getRepoId() == null)
		{
			return;
		}
		final int choice = JOptionPane.showConfirmDialog(this,
			"<html><body style='width:240px'>You edited \"" + escape(template.getName()) + "\", which you've shared."
				+ "<br>Push these changes to your shared copy now?<br><br>"
				+ "This won't change copies other players have already imported - only new imports get the update."
				+ "</body></html>",
			"Update shared copy?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (choice == JOptionPane.YES_OPTION)
		{
			pushUpdate(template);
		}
	}

	// Pushes the template's current name/description/layout to its shared copy, reusing the saved author and
	// anonymous preference (no need to re-prompt the full share dialog).
	private void pushUpdate(BankTemplate template)
	{
		if (template.getRepoId() == null || !requireLogin())
		{
			return;
		}
		final boolean anonymous = template.isSharedAnonymously();
		clientThread.invoke(() ->
		{
			final Player local = client.getLocalPlayer();
			final String author = local != null && local.getName() != null ? local.getName() : "";
			repositoryClient.update(template.getRepoId(), template, author, anonymous,
				() -> SwingUtilities.invokeLater(() ->
				{
					templateManager.saveUserTemplate(template);
					rebuildOnEdt();
					JOptionPane.showMessageDialog(this, "Updated your shared \"" + template.getName() + "\".", "Updated", JOptionPane.INFORMATION_MESSAGE);
				}),
				error -> SwingUtilities.invokeLater(() ->
					JOptionPane.showMessageDialog(this, error, "Update failed", JOptionPane.WARNING_MESSAGE)));
		});
	}

	private void createNewLayout()
	{
		final String input = JOptionPane.showInputDialog(this,
			"Name for the new layout (max " + MAX_NAME_LENGTH + " chars):", "New layout");
		if (input == null || input.trim().isEmpty())
		{
			return;
		}
		final BankTemplate t = new BankTemplate();
		t.setName(uniqueName(capName(input)));
		t.setColumns(BankTemplatesPlugin.ITEMS_PER_ROW);
		t.putTab(BankTemplate.MAIN_TAB, new ArrayList<>());
		if (templateManager.saveUserTemplate(t))
		{
			rebuildOnEdt();
			TemplateEditor.open(this, itemManager, itemIndex, clientThread, layoutEditor, templateManager, t,
				() ->
				{
					rebuildOnEdt();
					maybePromptPushAfterEdit(t);
				});
			// A brand-new layout auto-applies, so you can build it live over the bank straight away.
			select(t);
		}
		else
		{
			JOptionPane.showMessageDialog(this, "Could not create that layout.", "New layout failed", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void showPreview(BankTemplate template)
	{
		final JPanel content = new JPanel(new BorderLayout());
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		// The preview builds its own description (so the window is a consistent width either way).
		content.add(TemplatePreview.build(itemManager, clientThread, template, template.getDescription(),
			this::importTabInto), BorderLayout.CENTER);
		showSideDialog("Preview: " + template.getName(), content);
	}

	// Copies one tab from a previewed template into a template of the user's choice (an existing one, or a
	// brand-new one). A numbered tab is appended as the destination's next free numbered tab; the main
	// ("all items") view replaces the destination's main view. Lets you mix tabs from different templates
	// without rebuilding them by hand. Source identity doesn't matter - only the layout.
	private void importTabInto(TabLayout source)
	{
		if (source == null)
		{
			return;
		}
		final boolean isMain = source.getTab() == BankTemplate.MAIN_TAB;
		final List<Integer> layout = new ArrayList<>(source.getLayout());

		final List<BankTemplate> dests = templateManager.getUserTemplates();
		final String newOption = "+ New template...";
		final List<String> options = new ArrayList<>();
		for (BankTemplate t : dests)
		{
			options.add(t.getName());
		}
		options.add(newOption);

		final String choice = (String) JOptionPane.showInputDialog(this, "Import this tab into:", "Import tab",
			JOptionPane.PLAIN_MESSAGE, null, options.toArray(), options.get(0));
		if (choice == null)
		{
			return;
		}

		if (newOption.equals(choice))
		{
			final String input = JOptionPane.showInputDialog(this,
				"Name for the new template (max " + MAX_NAME_LENGTH + " chars):", "New template");
			if (input == null || input.trim().isEmpty())
			{
				return;
			}
			final BankTemplate t = new BankTemplate();
			t.setName(uniqueName(capName(input)));
			t.setColumns(BankTemplatesPlugin.ITEMS_PER_ROW);
			if (isMain)
			{
				// The main view becomes the new template's main view.
				t.putTab(BankTemplate.MAIN_TAB, layout);
			}
			else
			{
				t.putTab(BankTemplate.MAIN_TAB, new ArrayList<>());
				t.putTab(1, layout);
			}
			if (templateManager.saveUserTemplate(t))
			{
				rebuildOnEdt();
				JOptionPane.showMessageDialog(this, "Created \"" + t.getName() + "\" with the imported tab.",
					"Imported tab", JOptionPane.INFORMATION_MESSAGE);
			}
			else
			{
				JOptionPane.showMessageDialog(this, "Could not create that template.", "Import failed", JOptionPane.ERROR_MESSAGE);
			}
			return;
		}

		final BankTemplate dest = templateManager.findByName(choice);
		if (dest == null || dest.isPreset())
		{
			return;
		}

		if (isMain)
		{
			// The main view replaces the destination's main view. Confirm first when that would discard
			// existing main-view items, since the replace is destructive.
			if (hasRealItems(dest.tabLayout(BankTemplate.MAIN_TAB))
				&& JOptionPane.showConfirmDialog(this,
					"Replace the main view of \"" + dest.getName() + "\" with this one? "
						+ "Its current main-view items will be removed.",
					"Replace main view", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
					!= JOptionPane.OK_OPTION)
			{
				return;
			}
			dest.putTab(BankTemplate.MAIN_TAB, layout);
			// An item can only live in one bank tab, so release any item this view brings in from wherever
			// it already sat in the destination's numbered tabs - it "moves" to the main view.
			final int released = releaseDuplicates(dest, BankTemplate.MAIN_TAB, layout);
			templateManager.saveUserTemplate(dest);
			rebuildOnEdt();
			final String extra = released > 0
				? " Moved " + released + " item" + (released == 1 ? "" : "s") + " out of other tabs (they can't be in two tabs at once)."
				: "";
			JOptionPane.showMessageDialog(this, "Replaced the main view of \"" + dest.getName() + "\"." + extra,
				"Imported tab", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		final int free = nextFreeTab(dest.definedTabs());
		if (free < 0)
		{
			JOptionPane.showMessageDialog(this, "\"" + dest.getName() + "\" already has the maximum of 9 tabs.",
				"No free tab", JOptionPane.WARNING_MESSAGE);
			return;
		}
		dest.putTab(free, layout);
		// An item can only live in one bank tab, so release any item this tab brings in from wherever it
		// already sat in the destination - it "moves" to the imported tab.
		final int released = releaseDuplicates(dest, free, layout);
		templateManager.saveUserTemplate(dest);
		rebuildOnEdt();
		final String extra = released > 0
			? " Moved " + released + " item" + (released == 1 ? "" : "s") + " out of other tabs (they can't be in two tabs at once)."
			: "";
		JOptionPane.showMessageDialog(this, "Added the tab to \"" + dest.getName() + "\" as tab " + free + "." + extra,
			"Imported tab", JOptionPane.INFORMATION_MESSAGE);
	}

	// Releases (removes, shifting the rest up) any real item the newly imported tab contains from the
	// destination's other tabs, so each item lives in a single tab. Fillers and empty slots are left alone.
	// Returns how many slots were released.
	private static int releaseDuplicates(BankTemplate dest, int importedTab, List<Integer> importedLayout)
	{
		final java.util.Set<Integer> imported = new java.util.HashSet<>();
		for (Integer v : importedLayout)
		{
			if (v != null && v > 0 && v != BankTemplate.FILLER)
			{
				imported.add(v);
			}
		}
		if (imported.isEmpty())
		{
			return 0;
		}

		int released = 0;
		for (Integer t : dest.definedTabs())
		{
			if (t == importedTab)
			{
				continue;
			}
			final List<Integer> layout = dest.copyTab(t);
			final List<Integer> kept = new ArrayList<>(layout.size());
			for (Integer v : layout)
			{
				if (v != null && imported.contains(v))
				{
					released++;
					continue;
				}
				kept.add(v);
			}
			if (kept.size() != layout.size())
			{
				dest.putTab(t, kept);
			}
		}
		return released;
	}

	// Whether a tab layout holds at least one real item (not empty, not filler). Null layout -> false.
	private static boolean hasRealItems(int[] layout)
	{
		if (layout == null)
		{
			return false;
		}
		for (int v : layout)
		{
			if (v > 0 && v != BankTemplate.FILLER)
			{
				return true;
			}
		}
		return false;
	}

	// The first numbered tab (1-9) a template doesn't already define, or -1 when all nine are taken.
	private static int nextFreeTab(List<Integer> defined)
	{
		for (int t = 1; t <= 9; t++)
		{
			if (!defined.contains(t))
			{
				return t;
			}
		}
		return -1;
	}

	/**
	 * Shows {@code content} in a non-modal window placed beside the client, so the game stays clickable
	 * while it's open (the old modal dialog blocked the whole client). Falls back to screen-centre when
	 * the owner window can't be located.
	 */
	private void showSideDialog(String title, JComponent content)
	{
		final Window owner = SwingUtilities.getWindowAncestor(this);
		final JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		dialog.setContentPane(content);
		dialog.pack();

		if (owner != null)
		{
			final Rectangle screen = owner.getGraphicsConfiguration().getBounds();
			final Point loc = owner.getLocationOnScreen();
			// Prefer the left of the client; if there's no room there, the right.
			int x = loc.x - dialog.getWidth() - 8;
			if (x < screen.x)
			{
				x = loc.x + owner.getWidth() + 8;
			}
			// Keep the whole window on the client's screen. A maximised/fullscreen client leaves no room
			// beside it, which used to push the window off-screen so "View" looked like it did nothing.
			x = Math.max(screen.x, Math.min(x, screen.x + screen.width - dialog.getWidth()));
			final int y = Math.max(screen.y, Math.min(loc.y, screen.y + screen.height - dialog.getHeight()));
			dialog.setLocation(x, y);
		}
		else
		{
			dialog.setLocationRelativeTo(null);
		}
		dialog.setVisible(true);
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
