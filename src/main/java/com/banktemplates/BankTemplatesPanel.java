package com.banktemplates;

import com.google.gson.Gson;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
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
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
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
import java.awt.image.BufferedImage;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
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
	// My Templates sorting (local only - these never hit the server).
	private static final String[] LOCAL_SORT_LABELS = {"Recently updated", "Name (A-Z)", "Most items"};
	private static final String[] LOCAL_SORT_KEYS = {"updated", "name", "items"};
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
	// Pinned to the panel bottom (above the Updates button), OUTSIDE the scroll: the Reorganise card in
	// My Templates mode, empty otherwise. Populated by buildLocalView, cleared on every rebuild.
	private final JPanel reorgSlot = new JPanel(new BorderLayout());
	// Reorganise card: collapsed to its title row by default so it costs one line of the panel, expanded on
	// click. Session state - it isn't worth a config entry.
	private boolean reorgExpanded;
	// Pinned under the search box, OUTSIDE the scroll, so the sort/open-on-web controls stay put and only
	// the cards scroll. Filled per mode on every rebuild (empty in Updates).
	private final JPanel controlsSlot = new JPanel(new BorderLayout());
	// Local (client-side) paging offset for My Templates.
	private int localOffset = 0;
	// Name of a just-created template to page to and scroll into view on the next rebuild, then forget.
	private String revealName;

	// The newest bundled patch notes (this build's version + notes), or null if none.
	private final Changelog.Entry latestUpdate;
	private final java.util.List<Changelog.Entry> allUpdates;

	private String mode = LOCAL;
	private String query = "";
	// Variant-collapsed ids the player owns in their bank (qty > 0), for the "x / y items" card meta. Computed
	// on the client thread (reading client state isn't EDT-safe), or loaded from the per-account cache until the
	// live bank has loaded; null when neither is available yet.
	private volatile Set<Integer> ownedCanon;
	// Owned-items set built from the last stored bank snapshot, used when the live bank isn't readable
	// (logged out, or before the bank has been opened this session). Null until /me answers with items.
	private volatile Set<Integer> ownedSnapshot;

	// The set to count "x / y items" against: the live bank first, else the stored snapshot. Null when we
	// have neither - no linked account, no opt-in, or no snapshot yet - and the cards then just show "y items".
	private Set<Integer> ownedForCounts()
	{
		return ownedCanon != null ? ownedCanon : ownedSnapshot;
	}
	// The account ownedCanon belongs to, so it's discarded (and reloaded) when the player switches accounts.
	private volatile long ownedAccountHash = -1;

	// Whether the last duplex sync found this character linked to an Exchange Insights account. null until
	// the first sync has answered, so the "link your account" note only shows once we actually know.
	// Volatile: written on the EDT (sync results), read from the client thread as the bank-snapshot
	// privacy gate (isWebLinked).
	private volatile Boolean webSyncLinked;

	// One-click device-link state. `linking` is true from the moment the browser is opened until the poll
	// loop resolves (approved/denied/expired/timeout); linkedHandle is the Exchange Insights handle shown in
	// the status row once known (from a token ping). linkPollTask is the self-rescheduling poll, cancelled on
	// shutdown. All touched only on the EDT except linking (read from the poll thread).
	private volatile boolean linking;
	private String linkedHandle;
	// The linked Exchange Insights account's own public profile, supplied by the duplex sync. Drives the
	// styling of YOUR OWN My Templates cards (display name, avatar, card theme). Null until a sync answers,
	// or whenever the character isn't linked - linking is opt-in, so the cards fall back to a fully
	// defaulted look rather than requiring an account.
	private RemoteTemplate.Profile selfProfile;
	// The last bank snapshot Exchange Insights holds for this account (null when there is none). Lets the
	// "New template" dialog still offer a capture while the player is logged out, sourced from that bank.
	private TemplateRepositoryClient.Me.Snapshot selfSnapshot;
	private long lastMeAttempt;
	private ScheduledFuture<?> linkPollTask;
	// Total time we'll wait for the browser approval before giving up (the server code lives ~10 minutes).
	private static final long LINK_WINDOW_MS = 10 * 60 * 1_000L;

	// Duplex sync scheduling. A single self-rescheduling task drives sync: it runs on login, ~1.5s after any
	// local change (debounced), and every SYNC_POLL_MS as a backstop to pull website-side changes down. On a
	// failed or rate-limited push it retries with backoff so a change is never lost to a brief outage.
	private static final long SYNC_DEBOUNCE_MS = 1_500;
	// While the side panel is open, poll fast so website-side imports and edits show up in My
	// Templates within seconds; opening the panel also kicks an immediate sync (lightly throttled).
	private static final long SYNC_POLL_ACTIVE_MS = 20_000;
	private static final long ACTIVATE_SYNC_MIN_MS = 10_000;
	private static final long SYNC_RETRY_MIN_MS = 4_000;
	private static final long SYNC_POLL_MS = 90_000;
	private ScheduledFuture<?> pendingSyncTask;
	private volatile long changeSeq; // bumped on each local change
	private long syncedSeq; // highest changeSeq confirmed pushed to the website
	private long syncBackoffMs;

	private final List<RemoteTemplate> browseResults = new ArrayList<>();
	private String browseStatus;
	private String browseSort = "imported";
	private String localSort = "updated";
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
		// Vertical bar ALWAYS shown: the list is the panel's main surface, so a bar that appears and
		// disappears shifts the card widths as you move between tabs.
		final JScrollPane scroll = new JScrollPane(listContainer,
			JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		ThinScrollBarUI.style(scroll);
		add(scroll, BorderLayout.CENTER);

		// The panel bottom, shared by every tab and OUTSIDE the scroll: the Reorganise card (My Templates
		// mode only, filled by buildLocalView) pinned above the Updates button (shown only when there's one).
		final JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		reorgSlot.setOpaque(false);
		reorgSlot.setAlignmentX(Component.LEFT_ALIGNMENT);
		south.add(reorgSlot);
		if (updatesTabShown())
		{
			updatesTab.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			updatesTab.setAlignmentX(Component.LEFT_ALIGNMENT);
			south.add(Box.createVerticalStrut(8));
			south.add(updatesTab);
		}
		add(south, BorderLayout.SOUTH);

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

		// Account link/unlink status pinned at the very top, above the tabs. Populated by
		// refreshAccountRow().
		accountRow.setLayout(new BoxLayout(accountRow, BoxLayout.Y_AXIS));
		accountRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		accountRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(accountRow);
		refreshAccountRow();
		header.add(Box.createVerticalStrut(8));

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

		// Sort + open-on-web live here rather than in the scrolling list, so they stay put while the cards
		// scroll under them. Filled per mode by the view builders.
		controlsSlot.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controlsSlot.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlsSlot.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		header.add(Box.createVerticalStrut(6));
		header.add(controlsSlot);

		return header;
	}

	// True if a linked account should be shown: either the last sync confirmed the link, or (before any sync)
	// a token is set so we optimistically treat it as linked - the next sync corrects this if it's stale.
	// A character the user explicitly unlinked never shows the optimistic state.
	private boolean isLinkedForDisplay()
	{
		if (Boolean.TRUE.equals(webSyncLinked))
		{
			return true;
		}
		if (webSyncLinked != null || !hasEiToken())
		{
			return false;
		}
		final Long hash = repositoryClient.currentAccountHash();
		return hash == null || !repositoryClient.isUnlinkOptedOut(hash);
	}

	private boolean hasEiToken()
	{
		// Own token or one borrowed live from the Exchange Insights plugin on this client.
		return repositoryClient.effectiveToken() != null;
	}

	// (Re)draw the link/unlink button below the search box for the current state. One button, colored
	// by status - green when linked (click unlinks), red when not (click links) - with the bank value
	// line underneath when linked. Only shown when the community repository is enabled (all third-party
	// server contact, including linking, is behind that opt-in).
	private void refreshAccountRow()
	{
		accountRow.removeAll();
		if (repositoryClient.isEnabled())
		{
			accountRow.add(Box.createVerticalStrut(8));
			final JButton btn;
			if (linking)
			{
				// The browser hint only applies to the device flow; with a token the link is direct.
				btn = accountButton(repositoryClient.effectiveToken() != null ? "Linking…" : "Linking… approve it in your browser",
					new Color(96, 74, 30));
				btn.setEnabled(false);
			}
			else if (isLinkedForDisplay())
			{
				final String who = linkedHandle != null && !linkedHandle.isEmpty() ? " as " + linkedHandle : "";
				btn = accountButton("✓ Account linked · Unlink", new Color(35, 78, 42));
				btn.setToolTipText("Linked" + who + ". Click to unlink this character from your Exchange Insights account.");
				btn.addActionListener(e -> startUnlink());
			}
			else
			{
				btn = accountButton("Link Exchange Insights account", new Color(94, 44, 44));
				btn.setToolTipText(repositoryClient.effectiveToken() != null
					? "Links this character to your Exchange Insights account using your existing token."
					: "One-click: opens exchange-insights.gg to approve linking this character - no token to copy.");
				btn.addActionListener(e -> startOneClickLink());
			}
			btn.setAlignmentX(Component.LEFT_ALIGNMENT);
			btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			accountRow.add(btn);
		}
		accountRow.revalidate();
		accountRow.repaint();
	}

	// The account link/unlink control: a filled status button carrying the panel's rounded corners and bold
	// font, so it reads as part of the same family as the cards and inputs rather than a plain Swing block.
	private static JButton accountButton(String text, Color bg)
	{
		final JButton b = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				RoundedBorder.fill(g, this, getBackground());
				super.paintComponent(g);
			}
		};
		b.setFont(FontManager.getRunescapeBoldFont());
		b.setHorizontalAlignment(SwingConstants.CENTER);
		b.setForeground(Color.WHITE);
		b.setBackground(bg);
		b.setContentAreaFilled(false);
		b.setOpaque(false);
		b.setFocusPainted(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setBorder(new RoundedBorder(bg.brighter(), new Insets(5, 10, 5, 10)));
		return b;
	}

	// Confirm, then unlink the logged-in character from the Exchange Insights account server-side and
	// remember the choice locally so auto-linking (own or borrowed token) doesn't silently relink it.
	private void startUnlink()
	{
		final int choice = JOptionPane.showConfirmDialog(this,
			"Unlink this character from your Exchange Insights account?\n\n"
				+ "New bank snapshots and template sync stop for this character. Anything\n"
				+ "already stored stays until you delete your Exchange Insights account,\n"
				+ "and your account token keeps working for everything else.",
			"Unlink account", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION)
		{
			return;
		}
		final String token = repositoryClient.effectiveToken();
		clientThread.invokeLater(() ->
		{
			final long hash = client.getAccountHash();
			SwingUtilities.invokeLater(() -> doUnlink(token, hash));
		});
	}

	private void doUnlink(String token, long accountHash)
	{
		if (accountHash == -1)
		{
			JOptionPane.showMessageDialog(this,
				"Log into OSRS first (so the plugin knows which character to unlink), then try again.",
				"Not logged in", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		// Opt out FIRST so auto-linking can't race the server call and immediately relink.
		repositoryClient.setUnlinkOptOut(accountHash, true);
		if (token == null)
		{
			// Nothing to tell the server without a token (stale optimistic display) - clear locally.
			webSyncLinked = false;
			linkedHandle = null;
			bankValue = -1;
			refreshAccountRow();
			return;
		}
		repositoryClient.unlinkEiAccount(token, accountHash,
			() -> SwingUtilities.invokeLater(() ->
			{
				webSyncLinked = false;
				linkedHandle = null;
				bankValue = -1;
				refreshAccountRow();
				rebuildOnEdt(); // linked-only affordances refresh
			}),
			error -> SwingUtilities.invokeLater(() ->
			{
				repositoryClient.setUnlinkOptOut(accountHash, false); // nothing was unlinked - don't block auto-link
				JOptionPane.showMessageDialog(this, error, "Couldn't unlink", JOptionPane.WARNING_MESSAGE);
			}));
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
		// Claim the flag NOW (on the EDT), not in beginLink after the client-thread round-trip - two quick
		// clicks (or button + config toggle) would otherwise both pass the check and open two browser tabs.
		linking = true;
		refreshAccountRow();
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
			linking = false; // release the flag claimed in startOneClickLink
			refreshAccountRow();
			JOptionPane.showMessageDialog(this,
				"Log into OSRS first (so the plugin knows which character to link), then try again.",
				"Not logged in", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		// The user explicitly asked to link: lift any earlier unlink opt-out for this character.
		repositoryClient.setUnlinkOptOut(accountHash, false);
		// A token already exists (our own, or borrowed from the Exchange Insights plugin): link
		// directly, no browser round-trip needed.
		final String token = repositoryClient.effectiveToken();
		if (token != null)
		{
			repositoryClient.linkEiAccount(token, accountHash, rsn, true,
				() -> SwingUtilities.invokeLater(() -> finishLink(null)),
				error -> SwingUtilities.invokeLater(() ->
				{
					linking = false;
					refreshAccountRow();
					JOptionPane.showMessageDialog(this, error, "Couldn't link", JOptionPane.WARNING_MESSAGE);
				}));
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

	// Whether the last duplex sync confirmed this character is linked to an Exchange Insights account.
	// Gates the bank snapshot: contents must never leave the client for unlinked (or not-yet-confirmed)
	// characters, exactly as the setting's description promises.
	boolean isWebLinked()
	{
		return Boolean.TRUE.equals(webSyncLinked);
	}

	// Login / account switch: the new character's link state (and bank value) are unknown until its
	// first sync answers - clearing immediately keeps the snapshot gate closed for the new character
	// rather than trusting the previous one's state. Safe from any thread.
	void resetLinkState()
	{
		webSyncLinked = null; // volatile - closes the snapshot gate at once
		SwingUtilities.invokeLater(() ->
		{
			bankValue = -1;
			refreshAccountRow();
		});
	}

	// Latest bank value reported by the snapshot sync (gp at live GE mid prices); -1 until the first sync.
	private long bankValue = -1;

	// Called (on the EDT) when a bank snapshot lands and the server reports the bank's live value.
	void setBankValue(long value)
	{
		bankValue = value;
		refreshAccountRow();
	}

	// Fetch the linked Exchange Insights handle for the status row, when a token is set. Best-effort: a
	// failure (offline, revoked) just leaves the row without a name.
	// Fetch the account's OWN profile and last stored bank (token only, so it works logged out). Retried
	// rather than fired once at construction: a single attempt is lost to any transient failure - a cold
	// start before the network is up, or a server that hasn't answered yet - and the cards would then sit on
	// the defaulted look for the whole session. Backs off to at most one attempt per 30s, and stops asking
	// once it has an answer.
	private void maybeFetchMe()
	{
		final long now = System.currentTimeMillis();
		if (!repositoryClient.isEnabled() || !hasEiToken() || selfProfile != null
			|| now - lastMeAttempt < 30_000L)
		{
			return;
		}
		lastMeAttempt = now;
		repositoryClient.fetchMe(false, me -> SwingUtilities.invokeLater(() ->
		{
			if (me == null)
			{
				return;
			}
			if (me.profile != null)
			{
				selfProfile = me.profile;
			}
			selfSnapshot = me.snapshot;
			rebuildOnEdt();
			// Pull the snapshot's item ids too, so "x / y items" still means something with no live bank.
			if (me.snapshot != null && ownedSnapshot == null)
			{
				repositoryClient.fetchMe(true, full -> SwingUtilities.invokeLater(() ->
				{
					final List<int[]> items = full != null && full.snapshot != null ? full.snapshot.items : null;
					if (items == null || items.isEmpty())
					{
						return;
					}
					final Set<Integer> owned = new HashSet<>();
					for (int[] it : items)
					{
						if (it != null && it.length >= 1 && it[0] > 0)
						{
							owned.add(it[0]);
						}
					}
					ownedSnapshot = owned;
					rebuildOnEdt();
				}));
			}
		}));
	}

	void refreshLinkStatus()
	{
		// All third-party server contact stays behind the community-repository opt-in - including this
		// token ping (it sends the stored bearer token).
		if (!repositoryClient.isEnabled() || !hasEiToken())
		{
			return;
		}
		maybeFetchMe();
		repositoryClient.pingLink(repositoryClient.effectiveToken(),
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
		styleTab(localTab, LOCAL.equals(mode));
		styleTab(browseTab, BROWSE.equals(mode));
		// Updates stays an ordinary pinned button at the panel bottom, not part of the tab strip.
		updatesTab.setBackground(UPDATES.equals(mode) ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
		updatesTab.setForeground(UPDATES.equals(mode) ? Color.BLACK : Color.WHITE);
	}

	// A flat tab control rather than a filled button: the selected tab is white over an orange underline,
	// the rest are grey over a faint rule, so the two share one continuous baseline.
	private static void styleTab(JButton tab, boolean active)
	{
		tab.setContentAreaFilled(false);
		tab.setOpaque(false);
		tab.setFocusPainted(false);
		tab.setFocusable(false); // keep the caret in the search box when switching tabs
		tab.setForeground(active ? ICON_GOLD : ColorScheme.LIGHT_GRAY_COLOR);
		tab.setFont(active ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		tab.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 2, 0, active ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));
	}

	// The tabs sit side by side on one strip. Updates lives at the bottom of the panel (see the
	// constructor), not in this top row.
	private void layoutTabs()
	{
		tabsPanel.removeAll();
		tabsPanel.setLayout(new GridLayout(1, 2, 0, 0));
		tabsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		tabsPanel.add(localTab);
		tabsPanel.add(browseTab);
		tabsPanel.revalidate();
		tabsPanel.repaint();
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

	// True when this panel's own window is the active one, so a modal editor/dialog elsewhere keeps focus.
	private boolean ownWindowActive()
	{
		final Window w = SwingUtilities.getWindowAncestor(this);
		return w != null && w.isActive();
	}

	private void restoreSearchFocus()
	{
		if (UPDATES.equals(mode) || !searchBar.isVisible() || !ownWindowActive())
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			if (searchBar.isVisible() && ownWindowActive())
			{
				searchBar.requestFocusInWindow();
			}
		});
	}

	private void rebuildOnEdt()
	{
		listContainer.removeAll();
		reorgSlot.removeAll();   // refilled by buildLocalView only in My Templates mode
		controlsSlot.removeAll();
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
		// Put the caret back in the search box after a rebuild, so clicking a card, an icon or a tab doesn't
		// silently stop your typing. Skipped whenever another WINDOW holds focus - the template editor and
		// the dialogs need their own fields to keep it.
		restoreSearchFocus();
		reorgSlot.revalidate();
		reorgSlot.repaint();
		controlsSlot.revalidate();
		controlsSlot.repaint();
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

		// Full update history, newest first, each version a collapsible block (the latest expanded).
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
			renderUpdateVersion(entry, first);
			first = false;
		}
	}

	// Caret + version, rendered so the triangle uses a font that has it.
	private static String versionHeaderText(String version, boolean expanded)
	{
		return "<html><span style='font-family:Dialog'>" + (expanded ? "▾" : "▸") + "</span> Version " + version + "</html>";
	}

	private void renderUpdateVersion(Changelog.Entry entry, boolean expanded)
	{
		// The version's notes live in a content panel the header shows/hides; no "Changelog" label -
		// under a collapsible version, the notes ARE the changelog.
		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		content.setVisible(expanded);

		// Known issues first, then the changelog notes.
		if (entry.knownIssues != null && !entry.knownIssues.isEmpty())
		{
			final JLabel kiHeading = new JLabel("Known issues");
			kiHeading.setFont(FontManager.getRunescapeBoldFont());
			kiHeading.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			kiHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
			kiHeading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			content.add(kiHeading);
			for (String issue : entry.knownIssues)
			{
				content.add(bulletLabel(issue, ColorScheme.LIGHT_GRAY_COLOR));
			}
			content.add(Box.createVerticalStrut(8));
		}
		if (entry.notes != null && !entry.notes.isEmpty())
		{
			for (String note : entry.notes)
			{
				content.add(bulletLabel(note, Color.WHITE));
			}
		}

		// Clickable version header: toggles its content, latest expanded by default.
		final JLabel version = new JLabel(versionHeaderText(entry.version, expanded));
		version.setFont(FontManager.getRunescapeBoldFont());
		version.setForeground(ColorScheme.BRAND_ORANGE);
		version.setAlignmentX(Component.LEFT_ALIGNMENT);
		version.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		version.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		version.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				content.setVisible(!content.isVisible());
				version.setText(versionHeaderText(entry.version, content.isVisible()));
				listContainer.revalidate();
				listContainer.repaint();
			}
		});

		listContainer.add(version);
		listContainer.add(content);
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
		// Self-heal the profile/bank fetch if the first attempt (at construction) failed.
		maybeFetchMe();
		// A template is applied by clicking its card (the applied one has a red glow); creating one is the "+"
		// card at the end of the list.
		addLocalSection("Presets", templateManager.getPresets());
		// Sort + open-on-web controls, pinned in the header alongside Browse's (no section heading - the
		// cards speak for themselves).
		controlsSlot.add(buildLocalControlsRow(), BorderLayout.CENTER);
		addLocalMyTemplates(templateManager.getUserTemplates());

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

		// Reorganise: the mode dropdown and a description of what the selected mode does, styled as the same
		// rounded card as the template cards. A live mode gives it the gilded (gold) theme; off is neutral.
		final boolean reorgOn = config.showReorgHelper();
		final ProfileCard reorgCard = profileCardPanel(reorgOn ? "gilded" : null);
		reorgCard.setLayout(new BoxLayout(reorgCard, BoxLayout.Y_AXIS));
		reorgCard.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		// Header: the title, and a chevron marking which way the body will move. The card is pinned to the
		// bottom of the panel, so it opens UPWARDS - collapsed points up ("this opens up"), expanded points
		// down ("this closes back down").
		final JPanel reorgHeader = new JPanel(new BorderLayout());
		reorgHeader.setOpaque(false);
		reorgHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

		final JLabel reorgLabel = new JLabel("Reorganise");
		reorgLabel.setFont(CARD_NAME_FONT);
		reorgLabel.setForeground(reorgOn ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		reorgHeader.add(reorgLabel, BorderLayout.WEST);

		// Deliberately a plain JLabel, not one of the clickable icons: the whole card is the button, so a
		// chevron with its own hover plate would wrongly suggest it's the only hit target.
		final JLabel reorgChevron = new JLabel(PanelIcons.chevron(ColorScheme.BRAND_ORANGE, !reorgExpanded));
		reorgHeader.add(reorgChevron, BorderLayout.EAST);
		reorgCard.add(reorgHeader);

		final String reorgTip = reorgExpanded
			? "Hide the reorganise options"
			: "Show the reorganise options - a guide for rearranging your real bank to match the active template";
		reorgCard.setToolTipText(reorgTip);
		reorgLabel.setToolTipText(reorgTip);
		reorgChevron.setToolTipText(reorgTip);

		// The card itself is the button (everything except the dropdown, which keeps its own behaviour).
		final MouseAdapter reorgToggle = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				reorgExpanded = !reorgExpanded;
				rebuildOnEdt();
			}
		};
		for (final JComponent c : new JComponent[]{reorgCard, reorgHeader, reorgLabel, reorgChevron})
		{
			c.addMouseListener(reorgToggle);
			c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}

		if (!reorgExpanded)
		{
			// Collapsed: header only. Pinned at the bottom as always, just a single row tall.
			wireHover(reorgCard, reorgHeader, reorgLabel, reorgChevron);
			reorgSlot.add(reorgCard, BorderLayout.CENTER);
			return;
		}
		reorgCard.add(Box.createVerticalStrut(6));

		final String off = "Off";
		final JComboBox<String> reorgMode = new JComboBox<>();
		reorgMode.addItem(off);
		for (BankTemplatesConfig.ReorgDisplay d : BankTemplatesConfig.ReorgDisplay.values())
		{
			reorgMode.addItem(d.toString());
		}
		final String initialSel = config.showReorgHelper() ? config.reorgDisplay().toString() : off;
		reorgMode.setSelectedItem(initialSel);
		styleCombo(reorgMode);
		reorgMode.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, reorgMode.getPreferredSize().height));
		reorgCard.add(reorgMode);
		reorgCard.add(Box.createVerticalStrut(6));

		final JLabel reorgDesc = new JLabel(reorgDescription(initialSel, off));
		reorgDesc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reorgDesc.setFont(FontManager.getRunescapeSmallFont());
		reorgDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgDesc.addMouseListener(reorgToggle);   // body text is part of the button, the dropdown is not
		reorgDesc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// One call with every part, so the card's own hover listener is registered exactly once.
		wireHover(reorgCard, reorgHeader, reorgLabel, reorgChevron, reorgDesc);
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
			// Rebuild so the card re-renders in the right theme (gilded when a mode is live, neutral when off).
			rebuildOnEdt();
			onActiveChanged.run();
		});

		// The template list is top-aligned; the Reorganise card is pinned to the panel bottom (in reorgSlot,
		// outside the scroll, above the Updates button) rather than trailing the list. No glue here - the
		// list already has one before its bottom pager, and a second would halve the slack between them.
		reorgSlot.add(reorgCard, BorderLayout.CENTER);
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

	// Your own templates in the chosen sort order, always ending with the "+" create card (so an empty list
	// still offers a way to make the first one). The "+" card is hidden while searching - it isn't a result.
	private void addLocalMyTemplates(List<BankTemplate> templates)
	{
		final List<BankTemplate> shown = new ArrayList<>();
		for (BankTemplate t : sortedLocal(templates))
		{
			if (matchesQuery(t))
			{
				shown.add(t);
			}
		}
		// Paged client-side, same page size and pager as Browse. Clamp the offset so deleting the last
		// template on a page doesn't strand you past the end.
		final int total = shown.size();
		// A just-created template may sort onto any page, so jump to the one it actually landed on before
		// deciding what to draw - otherwise "scroll to it" would have nothing to scroll to.
		if (revealName != null)
		{
			for (int i = 0; i < total; i++)
			{
				if (revealName.equals(shown.get(i).getName()))
				{
					localOffset = (i / PAGE_SIZE) * PAGE_SIZE;
					break;
				}
			}
		}
		if (localOffset >= total)
		{
			localOffset = Math.max(0, ((total - 1) / PAGE_SIZE) * PAGE_SIZE);
		}

		final int end = Math.min(localOffset + PAGE_SIZE, total);
		// Same pager at both ends as Browse (see buildBrowseView). It sits ABOVE the create card, so the
		// pager is the first thing in the list on every page and doesn't shift down when the create card
		// appears (it's hidden while a search is active).
		listContainer.add(paginationRow(localOffset, total, end < total, off ->
		{
			localOffset = off;
			rebuildOnEdt();
		}));
		listContainer.add(Box.createVerticalStrut(6));
		// The create card leads the templates themselves, so making a template never needs a scroll.
		if (query.isEmpty())
		{
			listContainer.add(newTemplateCard());
			listContainer.add(Box.createVerticalStrut(6));
		}
		JComponent reveal = null;
		for (int i = localOffset; i < end; i++)
		{
			final BankTemplate t = shown.get(i);
			final JPanel card = buildLocalCard(t);
			if (revealName != null && revealName.equals(t.getName()))
			{
				reveal = card;
			}
			listContainer.add(card);
			listContainer.add(Box.createVerticalStrut(6));
		}
		// Push the bottom pager to the bottom of the visible panel when the page doesn't fill it, so it
		// stays where it was on the previous page instead of riding up under the last card. Absorbs
		// nothing once the content is taller than the viewport (see ListPanel).
		listContainer.add(Box.createVerticalGlue());
		listContainer.add(paginationRow(localOffset, total, end < total, off ->
		{
			localOffset = off;
			rebuildOnEdt();
		}));

		// Scroll the new card into view. The card has no bounds yet at this point, and revalidate() only
		// QUEUES a layout, so read its position after forcing one - otherwise this scrolls to an empty rect
		// and appears to do nothing. Scroll via the parent, whose coordinate space getBounds() is already in.
		// Cleared either way, so it only fires for the rebuild that follows a creation.
		final JComponent target = reveal;
		revealName = null;
		if (target != null)
		{
			SwingUtilities.invokeLater(() ->
			{
				listContainer.validate();
				listContainer.scrollRectToVisible(target.getBounds());
			});
		}
	}

	// My Templates ordering, applied client-side (these never leave the plugin).
	private List<BankTemplate> sortedLocal(List<BankTemplate> in)
	{
		final List<BankTemplate> list = new ArrayList<>(in);
		if ("name".equals(localSort))
		{
			list.sort(Comparator.comparing(t -> t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT)));
		}
		else if ("items".equals(localSort))
		{
			list.sort(Comparator.comparingInt(BankTemplate::itemCount).reversed());
		}
		else
		{
			list.sort(Comparator.comparingLong(BankTemplate::getUpdatedAt).reversed());
		}
		return list;
	}

	// Sort dropdown + the Exchange Insights logo that opens YOUR My Templates on the site.
	private JPanel buildLocalControlsRow()
	{
		final JComboBox<String> sort = new JComboBox<>(LOCAL_SORT_LABELS);
		styleCombo(sort);
		sort.setSelectedIndex(localSortIndex());
		sort.addActionListener(e ->
		{
			localSort = LOCAL_SORT_KEYS[sort.getSelectedIndex()];
			rebuildOnEdt();
		});

		return controlsRow(sort, "Open your My Templates on exchange-insights.gg in your browser",
			() -> LinkBrowser.browse("https://exchange-insights.gg/tools/osrs-bank-templates?view=mine"));
	}

	// The sort dropdown + the clickable Exchange Insights logo on one line. Both My Templates and Browse
	// build their row here, so the dropdown is laid out identically (same width) in each.
	private JPanel controlsRow(JComboBox<String> sort, String webTooltip, Runnable onWeb)
	{
		final IconLabel web = hoverPlate(new IconLabel(null));
		web.setIcon(EI_ICON);
		web.setToolTipText(webTooltip);
		web.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));
		web.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onWeb.run();
			}
		});

		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(sort, BorderLayout.CENTER);
		row.add(web, BorderLayout.EAST);
		return row;
	}

	private int localSortIndex()
	{
		for (int i = 0; i < LOCAL_SORT_KEYS.length; i++)
		{
			if (LOCAL_SORT_KEYS[i].equals(localSort))
			{
				return i;
			}
		}
		return 0;
	}

	// A placeholder card with a big green "+": a single entry point for creating a template. Clicking it asks
	// whether to start from the current bank or from an empty layout (the old Capture / New buttons).
	private JComponent newTemplateCard()
	{
		// Hover state for the whole card. A one-element array because the panel below is anonymous and its
		// listeners are installed from out here - matching the wash the template cards get from wireHover,
		// rather than only brightening the glyph.
		final boolean[] hot = {false};
		final JPanel card = new JPanel(new GridBagLayout())
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				final int w = getWidth(), h = getHeight();
				g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
				if (hot[0])
				{
					// Same white wash the profile cards use, plus a solid green ring so the dashed
					// "empty slot" outline reads as a live button under the cursor.
					g2.setColor(new Color(255, 255, 255, 20));
					g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
				}
				final int ringAlpha = hot[0] ? 220 : 120;
				g2.setColor(new Color(UPVOTE_COLOR.getRed(), UPVOTE_COLOR.getGreen(), UPVOTE_COLOR.getBlue(), ringAlpha));
				g2.setStroke(hot[0]
					? new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
					: new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{5f, 4f}, 0));
				g2.drawRoundRect(1, 1, w - 3, h - 3, 10, 10);
				g2.dispose();
			}

			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		card.setOpaque(false);
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.setToolTipText("Create a new template");

		final JLabel plus = new JLabel("+");
		plus.setForeground(UPVOTE_COLOR);
		plus.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
		card.add(plus);

		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				promptNewTemplate();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				plus.setForeground(UPVOTE_COLOR.brighter());
				hot[0] = true;
				card.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				plus.setForeground(UPVOTE_COLOR);
				// The glyph sits inside the card, so crossing onto it fires an exit on the card - only
				// drop the wash once the pointer has genuinely left the card's bounds.
				hot[0] = card.getMousePosition(true) != null;
				card.repaint();
			}
		});
		// The "+" fills most of the card, and a listener on the card alone never sees the pointer once it
		// is over the glyph.
		plus.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				promptNewTemplate();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				plus.setForeground(UPVOTE_COLOR.brighter());
				hot[0] = true;
				card.repaint();
			}
		});
		return card;
	}

	// Ask how to seed the new template: from the current bank, or from scratch. A small custom dialog so the
	// choices stack VERTICALLY (JOptionPane lays its option buttons out in a row).
	private void promptNewTemplate()
	{
		final Window owner = SwingUtilities.getWindowAncestor(this);
		final JDialog dialog = new JDialog(owner, "New template");
		dialog.setModal(true);

		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

		// Logged out there's no live bank to read. Exchange Insights may still hold a snapshot of it, so fall
		// back to capturing from that; with neither, the capture option is dropped rather than shown broken.
		final boolean liveBank = repositoryClient.hasIdentity();
		final TemplateRepositoryClient.Me.Snapshot snap = selfSnapshot;
		final boolean canCapture = liveBank || snap != null;

		final String how = liveBank
			? "your current bank (all tabs, in order)"
			: "your last saved bank on Exchange Insights (" + (snap != null ? snap.itemCount : 0) + " items, "
				+ (snap != null ? relativeTime(snap.updatedAt) : "") + ")";
		final JLabel msg = new JLabel("<html><body style='width:230px'>Start the new template from "
			+ (canCapture ? how + ", or from " : "")
			+ "an empty layout you build up yourself (add items you don't own as placeholders)."
			+ (canCapture ? "" : "<br><br>Log in to capture your current bank.")
			+ "</body></html>");
		msg.setFont(FontManager.getRunescapeFont());
		msg.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		msg.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(msg);
		content.add(Box.createVerticalStrut(12));

		if (canCapture)
		{
			content.add(dialogChoice(liveBank ? "Capture current bank" : "Capture last saved bank",
				ChoiceStyle.PRIMARY, dialog, liveBank ? this::captureCurrentBank : this::captureFromSnapshot));
			content.add(Box.createVerticalStrut(6));
		}
		content.add(dialogChoice("New empty template", canCapture ? ChoiceStyle.NEUTRAL : ChoiceStyle.PRIMARY,
			dialog, this::createNewLayout));
		content.add(Box.createVerticalStrut(6));
		content.add(dialogChoice("Cancel", ChoiceStyle.NEGATIVE, dialog, null));

		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);
	}

	/** How a dialog choice reads: the default action, an ordinary alternative, or a negative one (Cancel,
	 *  and anything destructive) which is always RED. */
	private enum ChoiceStyle
	{
		PRIMARY, NEUTRAL, NEGATIVE
	}

	// One full-width, stacked choice button in a panel-styled dialog: same rounded corners, fonts and
	// colours as the side panel rather than the platform look-and-feel's grey blocks. PRIMARY is a filled
	// orange default; NEUTRAL and NEGATIVE are outlined, NEGATIVE in red. Disposes the dialog, then runs the
	// action (null = just close).
	private JButton dialogChoice(String text, ChoiceStyle style, JDialog dialog, Runnable action)
	{
		final Color accent = style == ChoiceStyle.PRIMARY ? ColorScheme.BRAND_ORANGE
			: style == ChoiceStyle.NEGATIVE ? DOWNVOTE_COLOR
			: ColorScheme.LIGHT_GRAY_COLOR;
		final Color fill = style == ChoiceStyle.PRIMARY ? ColorScheme.BRAND_ORANGE : null;

		final JButton b = new JButton(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				if (fill != null)
				{
					RoundedBorder.fill(g, this, fill);
				}
				super.paintComponent(g);
			}
		};
		b.setFont(FontManager.getRunescapeFont());
		b.setHorizontalAlignment(SwingConstants.CENTER);
		b.setContentAreaFilled(false);
		b.setOpaque(false);
		b.setFocusPainted(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setForeground(style == ChoiceStyle.PRIMARY ? Color.BLACK : accent);
		b.setBorder(new RoundedBorder(accent, new Insets(6, 10, 6, 10)));
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		b.addActionListener(e ->
		{
			dialog.dispose();
			if (action != null)
			{
				action.run();
			}
		});
		return b;
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

		// A profile card matching the Browse cards. An imported-and-unedited template shows the ORIGINAL
		// owner's captured profile; a self-made or edited (now-owned) template shows YOUR linked account's
		// profile when the sync has supplied one, and otherwise a fully DEFAULTED card - linking an Exchange
		// Insights account is opt-in, so nothing here requires one.
		final BankTemplate.OwnerProfile owner = template.isOwned() ? null : template.getOwnerProfile();
		final RemoteTemplate.Profile self = owner == null && !template.isPreset() ? selfProfile : null;
		final String bgKey = owner != null ? owner.bg : self != null ? self.profileBg : null;
		final Integer avatarItemId = owner != null ? owner.avatarItemId : self != null ? self.avatarItemId : null;

		final ProfileCard card = profileCardPanel(bgKey, active);
		card.setLayout(new BorderLayout(8, 4));
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JComponent avatar = avatarComponent(avatarItemId, bgKey);
		// Resolved here rather than at the icon row below, because it also decides whether the avatar
		// becomes the profile button or stays part of the clickable card body.
		final String cardHandle = owner != null ? owner.handle : self != null ? self.handle : null;
		final boolean avatarIsProfile = wireAvatarProfile(avatar, cardHandle);
		card.add(avatar, BorderLayout.WEST);

		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		final JLabel name = clampedLabel(template.getName(), CARD_NAME_FONT,
			active ? ColorScheme.BRAND_ORANGE : Color.WHITE, CARD_NAME_MAX_WIDTH);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(name);

		final JLabel author = clampedLabel(localByline(template, owner, self), AUTHOR_FONT,
			ColorScheme.LIGHT_GRAY_COLOR, CARD_AUTHOR_MAX_WIDTH);
		author.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(author);

		final JLabel meta = new JLabel(localMeta(template));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(meta);
		card.add(text, BorderLayout.CENTER);

		// Apply (or disable) the template by clicking anywhere on the card body - the icon buttons in the SOUTH
		// row keep their own actions, so a click there doesn't also toggle the template.
		final MouseAdapter applyClick = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				select(active ? null : template);
			}
		};
		card.setToolTipText(active ? "Applied - click to stop applying it" : "Click to apply this template to your bank");
		final JComponent[] body = avatarIsProfile
			? new JComponent[]{card, text, name, author, meta}
			: new JComponent[]{card, avatar, text, name, author, meta};
		for (final JComponent c : body)
		{
			c.addMouseListener(applyClick);
			c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}
		// The avatar still keeps the card's wash while hovered - it's part of the card, just not part of
		// its click target.
		wireHover(card, avatar, text, name, author, meta);

		// Same spread-icon row as the Browse card, glue between each item.
		final JPanel buttons = iconRow();
		buttons.add(Box.createHorizontalGlue());
		// Share stats for your OWN templates: how many imported it / reported it. Greyed out until the template
		// has actually been shared to the community (nothing to count before then).
		if (!template.isPreset() && template.isOwned())
		{
			final boolean shared = template.getRepoId() != null;
			final Color dCol = shared ? UPVOTE_COLOR : STAT_MUTED;
			final Color fCol = shared ? DOWNVOTE_COLOR : STAT_MUTED;
			// Always show a number - a template with no imports/reports reads as 0, never as a bare icon.
			final int dCount = template.getShareDownloads() != null ? template.getShareDownloads() : 0;
			final int fCount = template.getShareReports() != null ? template.getShareReports() : 0;
			buttons.add(statIcon(PanelIcons.download(dCol), dCount, dCol,
				shared ? "Imports of your shared copy" : "Share this template to track imports"));
			buttons.add(Box.createHorizontalGlue());
			buttons.add(statIcon(PanelIcons.flag(fCol), fCount, fCol,
				shared ? "Reports of your shared copy" : "Share this template to track reports"));
			buttons.add(Box.createHorizontalGlue());
		}
		// A preset is read-only, so it gets the magnifier (preview). Your own templates open the editor, so
		// they get the PENCIL - orange while the editor is open, to double as the finish toggle.
		if (template.isPreset())
		{
			buttons.add(clickableIcon(PanelIcons.magnifier(ICON_GOLD), "Preview this template", () -> showPreview(template)));
		}
		else
		{
			final boolean editingThis = layoutEditor.isEditing(template);
			buttons.add(clickableIcon(PanelIcons.pencil(editingThis ? ColorScheme.BRAND_ORANGE : ICON_GOLD),
				editingThis ? "Finish editing this layout" : "View and edit this layout (add, move and arrange items - no need to own them)",
				() -> editTemplate(template)));
		}
		buttons.add(Box.createHorizontalGlue());
		// First-time Share only (upload icon); updating an already-shared copy is done from the edit screen.
		if (!template.isPreset() && repositoryClient.isEnabled() && !(template.isOwned() && template.getRepoId() != null))
		{
			buttons.add(clickableIcon(PanelIcons.upload(ICON_GOLD), "Share to the community repository", () -> share(template)));
			buttons.add(Box.createHorizontalGlue());
		}
		// Report only makes sense for templates you imported from someone else, not your own.
		if (template.getRepoId() != null && !template.isOwned() && repositoryClient.isEnabled())
		{
			buttons.add(actionIcon("&#9873;", -1, DOWNVOTE_COLOR, "Report the shared version of this template", () -> reportRepo(template.getRepoId())));
			buttons.add(Box.createHorizontalGlue());
		}
		// A shared, imported or web-synced template has a page on the site; link straight to it. Prefer your
		// own website copy (webId) over the community source it was imported from.
		final Long webLinkId = template.getWebId() != null ? template.getWebId() : template.getRepoId();
		if (webLinkId != null)
		{
			buttons.add(clickableIcon(PanelIcons.globe(ICON_GOLD), "Open this template on exchange-insights.gg", () -> openOnWeb(webLinkId)));
			buttons.add(Box.createHorizontalGlue());
		}
		if (!template.isPreset())
		{
			buttons.add(clickableIcon(PanelIcons.xMark(DOWNVOTE_COLOR), "Delete this template", () -> deleteLocal(template)));
			buttons.add(Box.createHorizontalGlue());
		}
		card.add(southStack(tabIconStrip(template.getTabs()), buttons), BorderLayout.SOUTH);
		return card;
	}

	// The card's bottom half: the tab-icon strip (when there is one) above the action-icon row. The strip
	// is what makes the card taller - a card for a template with no usable tab icons keeps its old height.
	private static JComponent southStack(JComponent strip, JComponent buttons)
	{
		if (strip == null)
		{
			return buttons;
		}
		final JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setOpaque(false);
		strip.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		south.add(strip);
		south.add(Box.createVerticalStrut(4));
		south.add(buttons);
		return south;
	}

	// ---- Browse repository ------------------------------------------------------------------

	private void buildBrowseView()
	{
		if (!repositoryClient.isEnabled())
		{
			listContainer.add(buildEnablePrompt());
			return;
		}

		// Sort dropdown + the Browse-on-web button share one line, pinned in the header (not the list).
		controlsSlot.add(buildBrowseControlsRow(), BorderLayout.CENTER);

		if (browseStatus != null)
		{
			listContainer.add(messageLabel(browseStatus));
			return;
		}

		// Re-sort the fetched page by how much of each template you own (most-owned first). Done here so it
		// always reflects the latest bank contents, even on a plain rebuild.
		if (CLOSEST_SORT.equals(browseSort) && ownedForCounts() != null)
		{
			browseResults.sort((a, b) -> Double.compare(ownershipScore(b), ownershipScore(a)));
		}

		// A pager at BOTH ends: a full page is longer than the panel, so paging from the bottom used to
		// mean scrolling back up to see what you landed on, and paging from the top meant scrolling down
		// to find the control. Each call builds its own row - Swing components have a single parent.
		listContainer.add(buildPaginationRow());
		listContainer.add(Box.createVerticalStrut(6));
		for (RemoteTemplate rt : browseResults)
		{
			listContainer.add(buildRemoteCard(rt));
			listContainer.add(Box.createVerticalStrut(6));
		}
		listContainer.add(Box.createVerticalGlue()); // bottom pager sits at the bottom of the panel
		listContainer.add(buildPaginationRow());
	}

	// One line: the sort dropdown (fills) + the Browse-on-web button. No "Sort" label.
	private JPanel buildBrowseControlsRow()
	{
		final JComboBox<String> sort = new JComboBox<>(SORT_LABELS);
		styleCombo(sort);
		sort.setSelectedIndex(sortIndex());
		sort.addActionListener(e ->
		{
			browseSort = SORT_KEYS[sort.getSelectedIndex()];
			newSearch();
		});

		// The Exchange Insights logo, clickable, opens the web browse page (carrying the panel's sort;
		// 'Items owned' is ranked locally so it sends the server's base order, which the site treats as
		// Most imported).
		return controlsRow(sort,
			"Open the community bank templates on exchange-insights.gg in your browser, sorted like this list",
			() -> LinkBrowser.browse("https://exchange-insights.gg/tools/osrs-bank-templates?sort="
				+ (CLOSEST_SORT.equals(browseSort) ? "imported" : browseSort)));
	}

	// The avatar IS the profile button - and the only one. The icon row used to carry a second, redundant
	// profile icon; two controls for one action, on a row where space is tight.
	//
	// The avatar IS the profile button. It's a picture of a person sitting on a profile-styled card, so it
	// reads as one - but it used to carry the card's own action (apply / preview) and the card's wash, so
	// clicking it did something else entirely and hovering it gave no sign it was its own control. Now it
	// opens the profile, on its own hover ring. Returns false when the owner has no known handle (anonymous
	// or unlinked), in which case the caller leaves the avatar as part of the card body.
	private boolean wireAvatarProfile(JComponent avatar, String handle)
	{
		if (handle == null || handle.isEmpty())
		{
			return false;
		}
		avatar.setToolTipText("View @" + handle + " on the Exchange Insights leaderboard");
		avatar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		final Border idle = BorderFactory.createEmptyBorder(2, 2, 2, 2);
		// Same insets either way, so gaining the ring can't nudge the card's layout.
		final Border ring = BorderFactory.createCompoundBorder(new RoundedBorder(ICON_GOLD, new Insets(1, 1, 1, 1)), BorderFactory.createEmptyBorder(1, 1, 1, 1));
		avatar.setBorder(idle);
		avatar.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				openProfile(handle);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				avatar.setBorder(ring);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				avatar.setBorder(idle);
			}
		});
		return true;
	}

	// Open an uploader's Exchange Insights profile on the leaderboard. Only offered when we actually know
	// their handle - an anonymous or unlinked upload has no profile to open.
	private void openProfile(String handle)
	{
		// Percent-encode: the site matches the handle against a strict character class, so a raw space or
		// any other unexpected character in it would fail the match and drop the visitor on the dashboard
		// instead of the profile. Spaces encode as "+" by default, which is wrong inside a fragment.
		String encoded;
		try
		{
			encoded = java.net.URLEncoder.encode(handle, java.nio.charset.StandardCharsets.UTF_8.name()).replace("+", "%20");
		}
		catch (java.io.UnsupportedEncodingException e)
		{
			encoded = handle; // UTF-8 is always available; fall back rather than swallow the click
		}
		LinkBrowser.browse("https://exchange-insights.gg/#community?u=" + encoded);
	}

	// Open a single template's page on the site (?t=<repoId> deep-links straight to it).
	private void openOnWeb(long repoId)
	{
		LinkBrowser.browse("https://exchange-insights.gg/tools/osrs-bank-templates?t=" + repoId);
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
		return paginationRow(browseOffset, browseTotal, browseHasMore, off ->
		{
			browseOffset = off;
			loadBrowse();
		});
	}

	// Single line: «  <  1-10 of 229  >  ». The range label carries the count, so there's no separate
	// "Page X of N" row and no separate Count line. Shared by Browse (server-paged, so it's told whether
	// more exist) and My Templates (paged locally, where the total settles it).
	private JPanel paginationRow(int offset, int total, boolean hasMore, java.util.function.IntConsumer goTo)
	{
		final int currentPage = offset / PAGE_SIZE + 1;
		final int totalPages = Math.max(currentPage, (total + PAGE_SIZE - 1) / PAGE_SIZE);
		final boolean hasPrev = offset > 0;

		final JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
		nav.setBackground(ColorScheme.DARK_GRAY_COLOR);
		nav.setAlignmentX(Component.LEFT_ALIGNMENT);
		nav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		nav.add(pagerButton("«", "First page", hasPrev, () -> goTo.accept(0)));
		nav.add(pagerButton("<", "Previous page", hasPrev, () -> goTo.accept(Math.max(0, offset - PAGE_SIZE))));

		final int start = total > 0 ? offset + 1 : 0;
		final int end = Math.min(offset + PAGE_SIZE, total);
		final JLabel range = new JLabel(total > 0 ? start + "-" + end + " of " + total : "0 of 0");
		range.setFont(FontManager.getRunescapeSmallFont());
		range.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		range.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		nav.add(range);

		nav.add(pagerButton(">", "Next page", hasMore, () -> goTo.accept(offset + PAGE_SIZE)));
		nav.add(pagerButton("»", "Last page", total > 0 && currentPage < totalPages,
			() -> goTo.accept((totalPages - 1) * PAGE_SIZE)));

		return nav;
	}

	// A compact pager button (First/Prev/Next/Last), kept narrow so the row fits the fixed panel width.
	// A bare pager arrow (no button background): white and clickable when enabled, greyed out when not.
	private JLabel pagerButton(String text, String tooltip, boolean enabled, Runnable action)
	{
		final JLabel b = new JLabel(text);
		b.setForeground(enabled ? Color.WHITE : new Color(0x66, 0x66, 0x66));
		b.setHorizontalAlignment(SwingConstants.CENTER);
		b.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
		if (enabled)
		{
			b.setToolTipText(tooltip);
			b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			b.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					action.run();
				}
			});
		}
		return b;
	}

	// A Browse card styled after the uploader's Exchange Insights profile: their themed background and
	// avatar, with the import/report counts as clickable icons. The whole thing is rebuilt from the freshly
	// fetched profile on each redraw, so a change to the uploader's avatar/background shows next refresh.
	private JPanel buildRemoteCard(RemoteTemplate rt)
	{
		final RemoteTemplate.Profile p = rt.profile;
		final String bg = p != null ? p.profileBg : null;

		final ProfileCard card = profileCardPanel(bg);
		card.setLayout(new BorderLayout(8, 4));
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JComponent avatar = avatarComponent(rt);
		card.add(avatar, BorderLayout.WEST);

		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		final JLabel name = clampedLabel(rt.name, CARD_NAME_FONT, Color.WHITE, CARD_NAME_MAX_WIDTH);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(name);

		final JLabel author = clampedLabel(byLine(rt), AUTHOR_FONT, ColorScheme.LIGHT_GRAY_COLOR, CARD_AUTHOR_MAX_WIDTH);
		author.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(author);

		final JLabel meta = new JLabel(remoteMeta(rt));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(meta);
		card.add(text, BorderLayout.CENTER);

		// Click anywhere on the card body to preview it - that replaces the magnifier icon, leaving the
		// remaining icons more room. The icon buttons in the SOUTH row keep their own actions.
		final MouseAdapter viewClick = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				showPreview(rt.toTemplate());
			}
		};
		card.setToolTipText("Click to preview this template");
		final String rtHandle = p != null && p.handle != null && !p.handle.isEmpty() ? p.handle : null;
		final boolean avatarIsProfile = wireAvatarProfile(avatar, rtHandle);
		final JComponent[] body = avatarIsProfile
			? new JComponent[]{card, text, name, author, meta}
			: new JComponent[]{card, avatar, text, name, author, meta};
		for (final JComponent c : body)
		{
			c.addMouseListener(viewClick);
			c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}
		wireHover(card, avatar, text, name, author, meta);

		final JPanel actions = iconRow();
		// The count icons ARE the buttons: download imports, flag reports. Glue around each spreads
		// them across the card while each keeps its full width (the count is never clipped).
		actions.add(Box.createHorizontalGlue());
		actions.add(countIcon(PanelIcons.download(UPVOTE_COLOR), rt.downloads, UPVOTE_COLOR, "Import a copy to My Templates", () -> importRemote(rt)));
		actions.add(Box.createHorizontalGlue());
		actions.add(actionIcon("&#9873;", rt.reports, DOWNVOTE_COLOR, "Report this template", () -> reportRepo(rt.id, this::loadBrowse)));
		actions.add(Box.createHorizontalGlue());
		actions.add(clickableIcon(PanelIcons.globe(ICON_GOLD), "Open this template on exchange-insights.gg", () -> openOnWeb(rt.id)));
		actions.add(Box.createHorizontalGlue());
		if (ownsRemote(rt.id))
		{
			actions.add(clickableIcon(PanelIcons.xMark(DOWNVOTE_COLOR), "Delete your shared template", () -> deleteRemote(rt.id)));
			actions.add(Box.createHorizontalGlue());
		}
		card.add(southStack(tabIconStrip(rt.tabs), actions), BorderLayout.SOUTH);
		return card;
	}

	// The uploader line: prefer their EI display name / @handle, else the template's author, else Anonymous.
	// Snapshot the uploader's public profile at import time, so an imported card can show the original
	// owner's name/avatar/theme. Anonymous or unlinked uploads capture just the "Anonymous"/author name,
	// which still renders as a fully defaulted (neutral) card.
	private BankTemplate.OwnerProfile capturedOwner(RemoteTemplate rt)
	{
		final BankTemplate.OwnerProfile op = new BankTemplate.OwnerProfile();
		final RemoteTemplate.Profile p = rt.anonymous ? null : rt.profile;
		if (p != null && p.displayName != null && !p.displayName.isEmpty())
		{
			op.name = p.displayName;
		}
		else if (p != null && p.handle != null && !p.handle.isEmpty())
		{
			op.name = "@" + p.handle;
		}
		else
		{
			op.name = rt.anonymous || rt.author == null || rt.author.isEmpty() ? "Anonymous" : rt.author;
		}
		op.handle = p != null ? p.handle : null;
		op.bg = p != null ? p.profileBg : null;
		op.avatarItemId = p != null ? p.avatarItemId : null;
		return op;
	}

	// The "by …" line for a My Templates card: a preset's source, an imported template's original owner,
	// your linked account's own name, or a plain "by you" when there's no linked profile (an EI account is
	// opt-in, so this always has to read sensibly without one).
	private String localByline(BankTemplate t, BankTemplate.OwnerProfile owner, RemoteTemplate.Profile self)
	{
		if (t.isPreset())
		{
			return "Built-in preset";
		}
		if (owner != null)
		{
			return "by " + (owner.name != null && !owner.name.isEmpty() ? owner.name : "Anonymous");
		}
		if (self != null && self.displayName != null && !self.displayName.isEmpty())
		{
			return "by " + self.displayName;
		}
		if (self != null && self.handle != null && !self.handle.isEmpty())
		{
			return "by @" + self.handle;
		}
		return "by you";
	}

	private String byLine(RemoteTemplate rt)
	{
		if (rt.profile != null)
		{
			if (rt.profile.displayName != null && !rt.profile.displayName.isEmpty())
			{
				return "by " + rt.profile.displayName;
			}
			if (rt.profile.handle != null && !rt.profile.handle.isEmpty())
			{
				return "by @" + rt.profile.handle;
			}
		}
		final String by = rt.anonymous || rt.author == null || rt.author.isEmpty() ? "Anonymous" : rt.author;
		return "by " + by;
	}

	// A card panel that paints the uploader's themed profile background (falls back to the neutral default).
	// Height is capped to its preferred height, like cardPanel, so the vertical list doesn't stretch it.
	private ProfileCard profileCardPanel(final String bgKey)
	{
		return profileCardPanel(bgKey, false);
	}

	// active = the currently applied template, drawn with a red highlight glow around its edge.
	private ProfileCard profileCardPanel(final String bgKey, final boolean active)
	{
		final ProfileCard card = new ProfileCard(bgKey, active);
		card.setOpaque(false);
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	// A template card: the uploader's themed background, a red glow when it's the applied template, and a
	// lift on hover - the whole card is the click target in both lists, so it needs to say so.
	private static final class ProfileCard extends JPanel
	{
		private final String bgKey;
		private final boolean active;
		private boolean hover;

		ProfileCard(String bgKey, boolean active)
		{
			this.bgKey = bgKey;
			this.active = active;
		}

		void setHover(boolean h)
		{
			if (hover != h)
			{
				hover = h;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			final Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			final int w = getWidth(), h = getHeight();
			ProfileCardStyle.paint(g2, w, h, 10, bgKey);
			if (hover)
			{
				// A wash plus a brighter rim, rather than a colour change - the background is the uploader's
				// theme, so the highlight has to work over any of them.
				g2.setColor(new Color(255, 255, 255, 20));
				g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 10, 10));
				g2.setStroke(new BasicStroke(1f));
				g2.setColor(new Color(255, 255, 255, 70));
				g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 10, 10));
			}
			if (active)
			{
				// A few concentric red strokes fading inward read as a soft red glow marking the applied one.
				for (int i = 0; i < 4; i++)
				{
					g2.setStroke(new BasicStroke(2f));
					g2.setColor(new Color(224, 58, 58, 170 - i * 44));
					final float o = 1.5f + i * 1.6f;
					g2.draw(new RoundRectangle2D.Float(o, o, w - 1f - o * 2f, h - 1f - o * 2f, 10, 10));
				}
			}
			g2.dispose();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	// Highlight the card while the pointer is anywhere inside it. The listener goes on the card AND its
	// children, because moving onto a child fires mouseExited on the parent; on exit the highlight only
	// drops once the pointer has genuinely left the card (getMousePosition covers children), so crossing
	// between the avatar, text and icon buttons doesn't flicker.
	private static void wireHover(ProfileCard card, JComponent... parts)
	{
		final MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setHover(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setHover(card.getMousePosition(true) != null);
			}
		};
		card.addMouseListener(hover);
		for (JComponent c : parts)
		{
			c.addMouseListener(hover);
		}
	}

	// A strip of the template's tab icons, so a card says what's actually IN the template without opening
	// it - the same buttons you'd see down the side of the bank. Each tab shows its chosen icon, or its
	// first item when it has none (exactly how the bank picks a tab's button). Empty tabs contribute
	// nothing. Returns null when there's nothing to draw, so a single-tab template keeps a compact card.
	private JComponent tabIconStrip(List<TabLayout> tabs)
	{
		if (tabs == null || tabs.isEmpty())
		{
			return null;
		}
		final List<Integer> ids = new ArrayList<>();
		for (TabLayout t : tabs)
		{
			int id = t.getCustomIconId();
			if (id <= 0)
			{
				for (Integer slot : t.getLayout())
				{
					if (slot != null && slot > 0)
					{
						id = slot;
						break;
					}
				}
			}
			if (id > 0)
			{
				ids.add(id);
			}
		}
		if (ids.isEmpty())
		{
			return null;
		}

		final int cell = 20;
		final int padX = 4;
		final JComponent strip = new JComponent()
		{
			private final Image[] imgs = new Image[ids.size()];

			{
				setPreferredSize(new Dimension(0, cell + 4));
				setMaximumSize(new Dimension(Integer.MAX_VALUE, cell + 4));
				for (int i = 0; i < ids.size(); i++)
				{
					final AsyncBufferedImage a = itemManager.getImage(ids.get(i));
					imgs[i] = a;
					a.onLoaded(this::repaint);
				}
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				final int w = getWidth(), h = getHeight();
				// A recessed well behind the icons, so the row reads as one strip rather than as loose art
				// floating on the card background.
				g2.setColor(new Color(0, 0, 0, 70));
				g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);

				// Shrink the cells rather than overflowing when a template uses all ten tabs.
				final int span = Math.max(1, w - padX * 2);
				final int cw = Math.min(cell, span / imgs.length);
				int x = padX;
				for (final Image img : imgs)
				{
					final int iw = img != null ? img.getWidth(null) : -1;
					final int ih = img != null ? img.getHeight(null) : -1;
					if (iw > 0 && ih > 0)
					{
						final double sc = Math.min((double) cw / iw, (double) (h - 4) / ih);
						final int dw = (int) Math.round(iw * sc);
						final int dh = (int) Math.round(ih * sc);
						g2.drawImage(img, x + (cw - dw) / 2, (h - dh) / 2, dw, dh, null);
					}
					x += cw;
				}
				g2.dispose();
			}
		};
		strip.setToolTipText(ids.size() + (ids.size() == 1 ? " tab in this template" : " tabs in this template"));
		return strip;
	}

	// Circular avatar rendered from the uploader's chosen item (via the local item-icon cache, so it stays
	// current), ringed in the profile's accent colour. Falls back to a silhouette when there's none.
	private JComponent avatarComponent(RemoteTemplate rt)
	{
		final RemoteTemplate.Profile p = rt.profile;
		return avatarComponent(p != null ? p.avatarItemId : null, p != null ? p.profileBg : null);
	}

	// Circular avatar for a profile card, driven by explicit fields so both Browse (uploader) and My
	// Templates (owner / defaulted self) cards can share it. itemId null → the default silhouette; bgKey
	// null → the default bronze ring.
	private JComponent avatarComponent(Integer itemId, String bgKey)
	{
		final int size = 44;
		final Color ring = ProfileCardStyle.border(bgKey);

		final JComponent avatar = new JComponent()
		{
			private Image img;

			{
				setPreferredSize(new Dimension(size, size));
				setMaximumSize(new Dimension(size, size));
				if (itemId != null && itemId > 0)
				{
					final AsyncBufferedImage a = itemManager.getImage(itemId);
					img = a;
					a.onLoaded(this::repaint);
				}
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(new Color(0, 0, 0, 90));
				g2.fillOval(1, 1, size - 3, size - 3);

				final int iw = img != null ? img.getWidth(null) : -1;
				final int ih = img != null ? img.getHeight(null) : -1;
				if (iw > 0 && ih > 0)
				{
					// clip() INTERSECTS; setClip() would REPLACE the clip we were handed - including the
					// scroll viewport's - letting a part-scrolled avatar paint over the pinned bottom bar.
					final Shape old = g2.getClip();
					g2.clip(new Ellipse2D.Float(2, 2, size - 5, size - 5));
					final int max = size - 12;
					final double sc = Math.min((double) max / iw, (double) max / ih);
					final int dw = (int) Math.round(iw * sc);
					final int dh = (int) Math.round(ih * sc);
					g2.drawImage(img, (size - dw) / 2, (size - dh) / 2, dw, dh, null);
					g2.setClip(old);
				}
				else
				{
					// Default avatar: a person silhouette, matching the website's, rather than an initial.
					g2.setColor(ring);
					final int cx = size / 2;
					final int headR = size / 7;
					final int headCy = size / 2 - headR + 1;
					g2.fillOval(cx - headR, headCy - headR, headR * 2, headR * 2);
					// Shoulders: the top half of a wide ellipse, clipped to the avatar circle so it reads as
					// a bust rather than a blob touching the ring.
					final int bodyW = (int) (size * 0.56);
					final int bodyH = (int) (size * 0.46);
					final Shape old = g2.getClip();
					g2.clip(new Ellipse2D.Float(2, 2, size - 5, size - 5));
					g2.fillArc(cx - bodyW / 2, headCy + headR + 2, bodyW, bodyH * 2, 0, 180);
					g2.setClip(old);
				}

				g2.setColor(ring);
				g2.setStroke(new BasicStroke(1.5f));
				g2.drawOval(1, 1, size - 4, size - 4);
				g2.dispose();
			}
		};
		return avatar;
	}

	// An import/report count shown as its own clickable icon+number (the icon is the action button).
	// A negative count renders the glyph alone (no number), for actions that have no count to show.
	private JLabel actionIcon(String glyphEntity, int count, Color color, String tooltip, Runnable action)
	{
		final String num = count < 0 ? "" : "&nbsp;" + count;
		final IconLabel label = hoverPlate(new IconLabel("<html><span style='font-family:Dialog'>" + glyphEntity + "</span>" + num + "</html>"));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setForeground(color);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(color.brighter());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(color);
			}
		});
		label.setMaximumSize(label.getPreferredSize());   // don't stretch in the X-axis icon row
		return label;
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
		t.setOwnerProfile(capturedOwner(rt));   // remember the original owner's profile for the card
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

	// Sharing, reporting and deleting have to be attributable. A logged-in character supplies that directly
	// (its account hash derives the clientId these are keyed on); logged out, a linked Exchange Insights
	// token identifies the account instead and the server attributes to it. Only a caller with NEITHER -
	// logged out AND no linked account - has nothing to attribute to, and is asked to log in.
	private boolean requireLogin()
	{
		if (repositoryClient.hasIdentity() || hasEiToken())
		{
			return true;
		}
		JOptionPane.showMessageDialog(this,
			"Log in to your RuneScape account first, or link an Exchange Insights account - sharing, "
				+ "reporting and deleting have to be tied to one of them.",
			"Not logged in", JOptionPane.WARNING_MESSAGE);
		return false;
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

	// "3 days ago" style age for the stored bank snapshot, so it's obvious how stale the fallback is.
	private static String relativeTime(long epochSeconds)
	{
		final long secs = Math.max(0, System.currentTimeMillis() / 1000L - epochSeconds);
		if (secs < 3600)
		{
			return Math.max(1, secs / 60) + "m ago";
		}
		if (secs < 86400)
		{
			return secs / 3600 + "h ago";
		}
		return secs / 86400 + "d ago";
	}

	// Builds a template from the last bank snapshot Exchange Insights holds for this account - the same
	// [id, qty, tab] triples the plugin uploads - so capturing still works while logged out of the game.
	// The triples carry their own tab, so they're grouped by it rather than re-sliced by varbit counts.
	private void captureFromSnapshot()
	{
		final String input = JOptionPane.showInputDialog(this,
			"Name for the captured template (max " + MAX_NAME_LENGTH + " chars):", "My bank");
		if (input == null || input.trim().isEmpty())
		{
			return;
		}
		final String name = uniqueName(capName(input));

		repositoryClient.fetchMe(true, me -> SwingUtilities.invokeLater(() ->
		{
			final List<int[]> items = me != null && me.snapshot != null ? me.snapshot.items : null;
			if (items == null || items.isEmpty())
			{
				JOptionPane.showMessageDialog(this, "Couldn't load your saved bank from Exchange Insights.",
					"Capture failed", JOptionPane.WARNING_MESSAGE);
				return;
			}
			final BankTemplate captured = new BankTemplate();
			captured.setName(name);
			captured.setColumns(BankLayoutRenderer.ITEMS_PER_ROW);
			for (int tab = 1; tab <= 9; tab++)
			{
				final List<Integer> layout = snapshotTab(items, tab);
				if (!layout.isEmpty())
				{
					captured.putTab(tab, layout);
				}
			}
			final List<Integer> main = snapshotTab(items, BankTemplate.MAIN_TAB);
			if (!main.isEmpty())
			{
				captured.putTab(BankTemplate.MAIN_TAB, main);
			}
			if (captured.tabCount() == 0 || !templateManager.saveUserTemplate(captured))
			{
				JOptionPane.showMessageDialog(this, "Couldn't capture that bank.", "Capture failed", JOptionPane.WARNING_MESSAGE);
				return;
			}
			// No success dialog: the new card scrolled into view is the confirmation.
			revealName = captured.getName();
			rebuildOnEdt();
		}));
	}

	private static List<Integer> snapshotTab(List<int[]> items, int tab)
	{
		final List<Integer> out = new ArrayList<>();
		for (int[] it : items)
		{
			if (it != null && it.length >= 3 && it[2] == tab)
			{
				out.add(it[0] > 0 ? it[0] : BankTemplate.EMPTY);
			}
		}
		return out;
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
					// No success dialog: the new card scrolled into view is the confirmation. Failures below
					// still speak up, since those leave nothing visible to explain themselves.
					revealName = captured.getName();
					rebuildOnEdt();
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
	private static final Color ICON_GOLD = new Color(0xE8, 0xC0, 0x50); // gold - the view / web actions
	private static final Color STAT_MUTED = new Color(0x70, 0x70, 0x70); // greyed share stats (not shared yet)

	// The Exchange Insights logo, scaled for the Browse control (clickable to open the web browse page).
	private static final ImageIcon EI_ICON = loadEiIcon();

	private static ImageIcon loadEiIcon()
	{
		try
		{
			final BufferedImage img = ImageUtil.loadImageResource(BankTemplatesPlugin.class, "/com/banktemplates/logo.png");
			final int h = 22;
			final int w = Math.max(1, img.getWidth() * h / img.getHeight());
			return new ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH));
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}
	// The narrow side panel can't fit long names (e.g. auto-generated Exchange Insights display names),
	// so clamp them with an ellipsis and show the full text on hover.
	// The card's title and its "by <uploader>" line, both bold so the template name and its attribution
	// carry the card. Passed to clampedLabel so its ellipsis measurement uses the font it renders in.
	// RuneScape's are BITMAP fonts, so deriveFont(BOLD) synthesises the weight by smearing the glyphs and
	// renders blurry. Use the real bold face instead - it's a designed weight, so it stays pixel-crisp.
	private static final Font CARD_NAME_FONT = FontManager.getRunescapeBoldFont();
	private static final Font AUTHOR_FONT = FontManager.getRunescapeBoldFont();

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
	// "x / y items" (x = how many of the template's items you own) plus tab count, for a Browse card. Falls
	// back to "y items" until the bank has loaded.
	private String remoteMeta(RemoteTemplate rt)
	{
		final BankTemplate t = rt.toTemplate();
		final int total = t.itemCount();
		final int tabs = t.tabCount();
		final Set<Integer> ownedSet = ownedForCounts();
		final String items = ownedSet != null
			? ownedOfTemplate(t, ownedSet) + " / " + total + " items"
			: total + " items";
		return items + (tabs > 1 ? " · " + tabs + " tabs" : "");
	}

	// Ranking score for the "Items owned" sort. Not a plain percentage: it weights the absolute number of the
	// template's items you own by the fraction you own (owned^2 / total), so owning more items is preferred and
	// a near-complete small template doesn't outrank a big one you have a lot of - e.g. 487/936 beats 180/276.
	private double ownershipScore(RemoteTemplate rt)
	{
		final Set<Integer> ownedSet = ownedForCounts();
		if (ownedSet == null)
		{
			return 0;
		}
		final BankTemplate t = rt.toTemplate();
		final int total = t.itemCount();
		if (total == 0)
		{
			return 0;
		}
		final int owned = ownedOfTemplate(t, ownedSet);
		return (double) owned * owned / total;
	}


	// A non-interactive stat chip (drawn icon + count) for a local card's share stats. count < 0 hides the
	// number (used until the server reports real counts), leaving just the coloured/greyed icon.
	private JLabel statIcon(ImageIcon icon, int count, Color color, String tooltip)
	{
		final JLabel label = new JLabel(count < 0 ? "" : String.valueOf(count));
		label.setIcon(icon);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setForeground(color);
		label.setToolTipText(tooltip);
		label.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 3));
		label.setMaximumSize(label.getPreferredSize());
		return label;
	}

	// Icons spread across the card's full width via horizontal glue between them, so each keeps its
	// FULL size (counts never get clipped) while the extra space is shared out evenly. Callers add a
	// glue before the first icon and after each one.
	// Dresses a dropdown to match the panel: a flat dark field with a thin border and a drawn chevron
	// instead of the platform look-and-feel's bevelled arrow button, plus padded popup rows that highlight
	// on hover.
	private static void styleCombo(JComboBox<String> combo)
	{
		combo.setUI(new BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				final JButton arrow = new JButton()
				{
					@Override
					protected void paintComponent(Graphics g)
					{
						final Graphics2D g2 = (Graphics2D) g.create();
						g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
						g2.setColor(ICON_GOLD);
						g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
						final int cx = getWidth() / 2, cy = getHeight() / 2;
						g2.drawLine(cx - 4, cy - 2, cx, cy + 2);
						g2.drawLine(cx + 4, cy - 2, cx, cy + 2);
						g2.dispose();
					}
				};
				arrow.setBorder(BorderFactory.createEmptyBorder());
				arrow.setContentAreaFilled(false);
				arrow.setFocusable(false);
				return arrow;
			}

			// The combo stays non-opaque so its body can be painted with the cards' rounded corners
			// instead of the square fill the look-and-feel would draw.
			@Override
			public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus)
			{
				RoundedBorder.fill(g, comboBox, comboBox.getBackground());
			}
		});
		combo.setOpaque(false);
		combo.setFocusable(false);
		combo.setFont(FontManager.getRunescapeFont());
		combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		combo.setForeground(Color.WHITE);
		combo.setBorder(new RoundedBorder(ColorScheme.MEDIUM_GRAY_COLOR, new Insets(3, 8, 3, 3)));
		combo.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused)
			{
				final JLabel row = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
				row.setFont(FontManager.getRunescapeFont());
				row.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
				row.setBackground(selected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
				row.setForeground(selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
				return row;
			}
		});
	}

	private JPanel iconRow()
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
		row.setMinimumSize(new Dimension(0, 0));
		return row;
	}

	/** Cap a text button's max size to its preferred size so it can't stretch to fill the glue in an
	 *  X-axis BoxLayout (icon row) - text buttons default to an unbounded max width. */
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
			// Stretch to the viewport when the content is SHORTER than it, so a vertical glue can push the
			// bottom pager down to the bottom of the visible panel instead of leaving it floating under the
			// last card. Content taller than the viewport keeps its own height and scrolls as before.
			final java.awt.Container parent = getParent();
			return parent instanceof JViewport && parent.getHeight() > getPreferredSize().height;
		}
	}

	// A bare clickable icon (no button background) - matches the count icons' look, for actions with
	// no count (View / Web). The glyph is already drawn in its colour.
	// A card icon that says it - not the card - is under the pointer: a rounded plate lights up behind it.
	// The card's own hover wash covers the whole card, so without this an icon looked identical whether you
	// were on it or merely near it, and you couldn't tell what a click would hit.
	private static final class IconLabel extends JLabel
	{
		private boolean hot;

		IconLabel(String text)
		{
			super(text);
		}

		void setHot(boolean h)
		{
			if (hot != h)
			{
				hot = h;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (hot)
			{
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(new Color(255, 255, 255, 34));
				g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 7, 7));
				g2.setColor(new Color(255, 255, 255, 90));
				g2.setStroke(new BasicStroke(1f));
				g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 7, 7));
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}

	// Installs the plate + hand cursor on a card icon. Kept separate from the click wiring so the count and
	// glyph icons get exactly the same treatment as the plain ones.
	private static IconLabel hoverPlate(IconLabel label)
	{
		label.setFocusable(false); // clicking an icon must not pull the caret out of the search box
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setHot(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setHot(false);
			}
		});
		return label;
	}

	private JLabel clickableIcon(ImageIcon icon, String tooltip, Runnable action)
	{
		final IconLabel label = hoverPlate(new IconLabel(null));
		label.setIcon(icon);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setToolTipText(tooltip);
		label.setBorder(BorderFactory.createEmptyBorder(1, 3, 1, 3));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}
		});
		label.setMaximumSize(label.getPreferredSize());   // don't stretch in the X-axis icon row
		return label;
	}

	// A clickable drawn icon + its count, coloured to match; hover brightens (like the glyph counts).
	private JLabel countIcon(ImageIcon icon, int count, Color color, String tooltip, Runnable action)
	{
		final IconLabel label = hoverPlate(new IconLabel(String.valueOf(count)));
		label.setIcon(icon);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setForeground(color);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 3));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(color.brighter());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(color);
			}
		});
		label.setMaximumSize(label.getPreferredSize());   // don't stretch in the X-axis icon row
		return label;
	}

	private String localMeta(BankTemplate template)
	{
		final int total = template.itemCount();
		final int tabs = template.tabCount();
		// "x / y items" when the bank is known (x = how many of the template's items you currently own),
		// otherwise just "y items" until the bank has loaded. Ownership is already stated by the card's
		// "by …" line, so it isn't repeated here.
		final Set<Integer> ownedSet2 = ownedForCounts();
		final String items = ownedSet2 != null
			? ownedOfTemplate(template, ownedSet2) + " / " + total + " items"
			: total + " items";
		return items + (tabs > 1 ? " · " + tabs + " tabs" : "");
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
		syncStopped = false; // an explicit kick (login, repo enabled) restarts a stopped loop
		scheduleSync(0);
	}

	// Panel visibility drives the poll cadence: an open panel syncs immediately (throttled) and then
	// polls fast, so a template imported or edited on the website appears in My Templates in seconds.
	private volatile boolean panelActive;
	private volatile long lastSyncStartedAt;

	@Override
	public void onActivate()
	{
		panelActive = true;
		if (repositoryClient.isEnabled() && !syncStopped
			&& System.currentTimeMillis() - lastSyncStartedAt > ACTIVATE_SYNC_MIN_MS)
		{
			scheduleSync(0);
		}
	}

	@Override
	public void onDeactivate()
	{
		panelActive = false;
	}

	// A local change happened: push it up soon (debounced so a burst of edits collapses into one sync).
	void requestSync()
	{
		syncStopped = false;
		changeSeq++;
		scheduleSync(SYNC_DEBOUNCE_MS);
	}

	// True after stopSync: cancel(false) can't stop an already-executing pass, and afterSync re-arms the
	// next poll - this flag stops a disabled plugin's in-flight pass from resurrecting the loop.
	private volatile boolean syncStopped;

	// Stop the sync loop and any in-flight account link poll (plugin shutdown).
	synchronized void stopSync()
	{
		syncStopped = true;
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
		if (syncStopped)
		{
			return;
		}
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
		lastSyncStartedAt = System.currentTimeMillis(); // throttles the open-panel kick in onActivate
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
			// The linked account's own profile, so YOUR cards can carry your avatar/theme/name instead of
			// the defaulted placeholder. Kept from the last sync that supplied one.
			if (result.profile != null)
			{
				selfProfile = result.profile;
			}
			if (result.bankValue != null)
			{
				setBankValue(result.bankValue); // fresh heartbeat value - no bank change needed
			}
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

		// Copies the server refused because the website deleted them. These generally have NO webId (they
		// were never created up there), so the web-backed pass below can't see them - and the server will
		// keep refusing them, so they would otherwise sit here forever, absent from the website and
		// inflating the local count against it. Only keys the server explicitly named are removed.
		if (result.deleted != null && !result.deleted.isEmpty())
		{
			final Set<String> refused = new HashSet<>(result.deleted);
			for (BankTemplate t : new ArrayList<>(templateManager.getUserTemplates()))
			{
				if (!t.isPreset() && !t.isOwned() && t.getClientKey() != null && refused.contains(t.getClientKey()))
				{
					templateManager.deleteUserTemplate(t);
				}
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
			next = pending ? SYNC_RETRY_MIN_MS : (panelActive ? SYNC_POLL_ACTIVE_MS : SYNC_POLL_MS);
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
			final long editedFrom = template.getUpdatedAt();
			TemplateEditor.open(this, itemManager, itemIndex, clientThread, layoutEditor, templateManager, template,
				() ->
				{
					claimOwnershipIfEdited(template, editedFrom);
					rebuildOnEdt();
					maybePromptPushAfterEdit(template);
				});
		}
		rebuildOnEdt();
	}

	// An imported template the user actually changes becomes their OWN new template: its card switches to
	// the user's (defaulted) profile, it drops the original owner, and it detaches from the community source
	// so nothing is pushed back there and it can be shared fresh. Only fires when an edit really happened
	// (updatedAt moved), so merely opening the editor to look doesn't claim someone else's template.
	private void claimOwnershipIfEdited(BankTemplate template, long editedFrom)
	{
		if (!template.isOwned() && template.getOwnerProfile() != null && template.getUpdatedAt() != editedFrom)
		{
			template.setOwned(true);
			template.setOwnerProfile(null);
			template.setRepoId(null);
			templateManager.saveUserTemplate(template);
		}
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
			revealName = t.getName();
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
