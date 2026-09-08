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
import java.awt.GradientPaint;
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
import java.awt.geom.RoundRectangle2D;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
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
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
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

	// Browse sorting, applied locally over the fetched index.
	private static final String[] SORT_LABELS = {"Most imported", "Newest", "Popular (30 days)"};
	private static final String[] SORT_KEYS = {"imported", "newest", "popular"};
	// My Templates sorting.
	private static final String[] LOCAL_SORT_LABELS = {"Recently updated", "Name (A-Z)", "Most items"};
	private static final String[] LOCAL_SORT_KEYS = {"updated", "name", "items"};

	private final TemplateManager templateManager;
	private final ItemManager itemManager;
	private final TemplateRepositoryClient repositoryClient;
	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final BankTemplatesConfig config;
	private final LayoutEditor layoutEditor;
	private final ItemIndex itemIndex;

	// Tracks the scroll viewport's width so it never grows wider than the panel (e.g. a long dropdown item or
	// label can't push it out). Without this, "full-width" buttons stretch past the visible area and their
	// centred text ends up sitting left of centre.
	private final JPanel listContainer = new ListPanel();
	private final SearchBar searchBar = new SearchBar();
	private final JPanel tabsPanel = new JPanel();
	private final JButton localTab = new JButton("My Templates");
	private final JButton browseTab = new JButton("Browse");
	private final JButton updatesTab = new JButton("Updates");
	// Pinned to the panel bottom (above the Updates button), OUTSIDE the scroll: the Reorganise card in
	// My Templates mode, empty otherwise. Populated by buildLocalView, cleared on every rebuild.
	private final JPanel reorgSlot = new JPanel(new BorderLayout());
	// Reorganise card: collapsed to its title row by default so it costs one line of the panel, expanded on
	// click. Session state - it isn't worth a config entry.
	private boolean reorgExpanded;
	// Pinned under the search box, OUTSIDE the scroll, so the sort control stays put and only
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
	// The catalogue as last fetched (metadata only, no layouts). Browse sorts, searches and pages this
	// list in memory; a layout is only fetched when a card is previewed or imported.
	private final List<RemoteTemplate> browseIndex = new ArrayList<>();
	private String browseStatus;
	private String browseSort = "imported";
	private String localSort = "updated";
	private int browseOffset = 0;

	// Ids this character owns on the server, so its own shares still read as "yours" after a reinstall
	// wiped the local owned flags. Asked for once per identity: mineFetchedFor is the clientId it was
	// fetched for, null when logged out.
	private final Set<Long> mineIds = new HashSet<>();
	private String mineFetchedFor;

	private Runnable onActiveChanged = () ->
	{
	};

	@Inject
	BankTemplatesPanel(TemplateManager templateManager, ItemManager itemManager, TemplateRepositoryClient repositoryClient,
		Client client, ClientThread clientThread, ConfigManager configManager, BankTemplatesConfig config,
		LayoutEditor layoutEditor, ItemIndex itemIndex, Gson gson)
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
		this.latestUpdate = Changelog.latest(gson);
		this.allUpdates = Changelog.all(gson);

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
			// The Updates button used to sit here, taking a full-width row at the bottom for something
			// people read once per release. The bell in the header opens the same tab.
		add(south, BorderLayout.SOUTH);
	}

	// Opening this plugin's config page needs the plugin (it posts the overlay menu event), and the
	// panel is injected, so it cannot hold one without a cycle. Same shape as onActiveChanged: the
	// plugin hands the panel a Runnable during startUp.
	private Runnable onOpenSettings = () ->
	{
	};

	void setOnOpenSettings(Runnable r)
	{
		this.onOpenSettings = r != null ? r : () ->
		{
		};
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

		// Three shortcuts, right-aligned above everything else. They are deliberately icon-only and
		// unpainted: the panel is narrow, and three more labelled buttons would crowd out the tabs that
		// people actually came for.
		final JPanel icons = new JPanel();
		icons.setLayout(new BoxLayout(icons, BoxLayout.X_AXIS));
		icons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		icons.setAlignmentX(Component.LEFT_ALIGNMENT);
		icons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		icons.add(Box.createHorizontalGlue()); // everything after this is pushed to the right edge
		// The bell is only worth a slot when there is a changelog behind it. Gated on the changelog
		// itself rather than on alertUpdates: that setting governs whether we nag about a new version,
		// while this is someone deliberately going to look.
		if (!allUpdates.isEmpty())
		{
			icons.add(headerIcon(BELL_ICON, "Updates",
				"What changed in the latest version of the plugin.", () -> switchMode(UPDATES)));
			icons.add(Box.createHorizontalStrut(6));
		}
		icons.add(headerIcon(COG_ICON, "Settings",
			"Opens this plugin's configuration page.", () -> onOpenSettings.run()));
		icons.add(Box.createHorizontalStrut(6));
		icons.add(headerIcon(SUPPORT_ICON, "Support",
			"Report a problem or ask for help.",
			() -> LinkBrowser.browse(SUPPORT_URL)));
		header.add(icons);
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
				setQuery(searchBar.getText());
			}
		});
		searchBar.addClearListener(() -> setQuery(""));
		// No search box on the Updates/changelog view - there's nothing to search there.
		searchBar.setVisible(!UPDATES.equals(mode));
		header.add(searchBar);

		// The sort dropdown lives here rather than in the scrolling list, so it stays put while the cards
		// scroll under it. Filled per mode by the view builders.
		controlsSlot.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controlsSlot.setAlignmentX(Component.LEFT_ALIGNMENT);
		controlsSlot.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		header.add(Box.createVerticalStrut(6));
		header.add(controlsSlot);

		return header;
	}

	// Filters whichever list is showing. Browse is filtered over the fetched index, so typing never sends
	// a request; its page resets so a narrower match doesn't strand the pager past the end.
	private void setQuery(String text)
	{
		final String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		if (!q.equals(query))
		{
			query = q;
			browseOffset = 0;
		}
		if (!UPDATES.equals(mode))
		{
			rebuildOnEdt();
		}
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
		// The search field is a plain text input: it never asks for focus on its own, so a background rebuild
		// (the index arriving, say) never pulls the cursor away from the game.
		reorgSlot.revalidate();
		reorgSlot.repaint();
		controlsSlot.revalidate();
		controlsSlot.repaint();
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
		// A template is applied by clicking its card (the applied one has a red glow); creating one is the "+"
		// card at the end of the list.
		addLocalSection("Presets", templateManager.getPresets());
		// Sort control, pinned in the header alongside Browse's (no section heading - the cards speak for
		// themselves).
		controlsSlot.add(buildLocalControlsRow(), BorderLayout.CENTER);
		addLocalMyTemplates(templateManager.getUserTemplates());

		listContainer.add(Box.createVerticalStrut(8));

		// Reorganise: the mode dropdown and a description of what the selected mode does, styled as the same
		// rounded card as the template cards. A live mode turns the title orange.
		final boolean reorgOn = config.showReorgHelper();
		final Card reorgCard = cardPanel();
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
			// Rebuild so the title picks up the new state (orange while a mode is live).
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

	// The My Templates sort dropdown.
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

		return controlsRow(sort);
	}

	// The sort dropdown on one line. Both My Templates and Browse build their row here, so the dropdown
	// is laid out identically (same width) in each.
	private JPanel controlsRow(JComboBox<String> sort)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(sort, BorderLayout.CENTER);
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

		// Logged out there's no live bank to read, so the capture option is dropped rather than shown broken.
		final boolean canCapture = repositoryClient.hasIdentity();

		final JLabel msg = new JLabel("<html><body style='width:230px'>Start the new template from "
			+ (canCapture ? "your current bank (all tabs, in order), or from " : "")
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
			content.add(dialogChoice("Capture current bank", ChoiceStyle.PRIMARY, dialog, this::captureCurrentBank));
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

		// An imported template you haven't edited still names the player who shared it; anything self-made
		// or edited into your own reads "by you".
		final BankTemplate.OwnerProfile owner = template.isOwned() ? null : template.getOwnerProfile();

		final Card card = cardPanel(active);
		card.setLayout(new BorderLayout(8, 4));
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		final JLabel name = clampedLabel(template.getName(), CARD_NAME_FONT,
			active ? ColorScheme.BRAND_ORANGE : Color.WHITE, CARD_NAME_MAX_WIDTH);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(name);

		final JLabel author = clampedLabel(localByline(template, owner), AUTHOR_FONT,
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
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				select(active ? null : template);
			}
		};
		card.setToolTipText(active ? "Applied - click to stop applying it" : "Click to apply this template to your bank");
		for (final JComponent c : new JComponent[]{card, text, name, author, meta})
		{
			c.addMouseListener(applyClick);
			c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}
		wireHover(card, text, name, author, meta);

		// Same spread-icon row as the Browse card, glue between each item.
		final JPanel buttons = iconRow();
		buttons.add(Box.createHorizontalGlue());
		// Share stats: how many imported it / reported it. An import you haven't edited shows the ORIGINAL
		// community template's numbers (it isn't yours); your own template shows its shared copy's, greyed
		// out until it has actually been shared (nothing to count before then).
		final boolean isImport = owner != null;
		if (!template.isPreset() && (isImport || template.isOwned()))
		{
			final int dCount, fCount;
			final boolean shared;
			final String dTip, fTip;
			if (isImport)
			{
				dCount = owner.downloads != null ? owner.downloads : 0;
				fCount = owner.reports != null ? owner.reports : 0;
				shared = true;
				dTip = "Imports of this template";
				fTip = "Reports of this template";
			}
			else
			{
				shared = template.getRepoId() != null;
				// Always show a number - a template with no imports/reports reads as 0, never as a bare icon.
				dCount = template.getShareDownloads() != null ? template.getShareDownloads() : 0;
				fCount = template.getShareReports() != null ? template.getShareReports() : 0;
				dTip = shared ? "Imports of your shared copy" : "Share this template to track imports";
				fTip = shared ? "Reports of your shared copy" : "Share this template to track reports";
			}
			final Color dCol = shared ? UPVOTE_COLOR : STAT_MUTED;
			final Color fCol = shared ? DOWNVOTE_COLOR : STAT_MUTED;
			buttons.add(statIcon(PanelIcons.download(dCol), dCount, dCol, dTip));
			buttons.add(Box.createHorizontalGlue());
			buttons.add(statIcon(PanelIcons.flag(fCol), fCount, fCol, fTip));
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
		if (!template.isPreset())
		{
			buttons.add(clickableIcon(PanelIcons.xMark(DOWNVOTE_COLOR), "Delete this template", () -> deleteLocal(template)));
			buttons.add(Box.createHorizontalGlue());
		}
		// Right-click anywhere on the card body: the icon row's actions again, as a labelled list. Same
		// conditions as the icons, so the menu never offers something the card wouldn't.
		final JPopupMenu menu = new JPopupMenu();
		if (template.isPreset())
		{
			menuItem(menu, "Preview", () -> showPreview(template));
		}
		else
		{
			menuItem(menu, layoutEditor.isEditing(template) ? "Finish editing" : "Edit layout", () -> editTemplate(template));
			if (repositoryClient.isEnabled())
			{
				final boolean shared = template.isOwned() && template.getRepoId() != null;
				menuItem(menu, shared ? "Update shared copy" : "Share to the community", () -> share(template));
			}
		}
		if (template.getRepoId() != null && !template.isOwned() && repositoryClient.isEnabled())
		{
			menuItem(menu, "Report shared version", () -> reportRepo(template.getRepoId()));
		}
		if (!template.isPreset())
		{
			menu.addSeparator();
			menuItem(menu, "Delete", () -> deleteLocal(template));
		}
		attachPopup(menu, card, text, name, author, meta);
		card.add(southStack(tabIconStrip(tabIconsOf(template.getTabs())), buttons), BorderLayout.SOUTH);
		return card;
	}

	// The card's bottom half: the tab-icon strip (when there is one) above the action-icon row. The strip
	// is what makes the card taller - a card for a template with no usable tab icons keeps its old height.
	private static void menuItem(JPopupMenu menu, String text, Runnable action)
	{
		final JMenuItem item = new JMenuItem(text);
		item.addActionListener(e -> action.run());
		menu.add(item);
	}

	// Opens the menu on the platform's popup gesture, which arrives on press (Linux, macOS) or release
	// (Windows), so both are checked. Attached to every part of the card body, like the click handlers.
	private static void attachPopup(JPopupMenu menu, JComponent... parts)
	{
		final MouseAdapter trigger = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					menu.show(e.getComponent(), e.getX(), e.getY());
					e.consume();
				}
			}
		};
		for (final JComponent c : parts)
		{
			c.addMouseListener(trigger);
		}
	}

	// The local, owned copy behind a Browse card, when this character shared it and still has it.
	private BankTemplate localOwnedCopy(long repoId)
	{
		for (BankTemplate t : templateManager.getUserTemplates())
		{
			if (t.isOwned() && t.getRepoId() != null && t.getRepoId() == repoId)
			{
				return t;
			}
		}
		return null;
	}

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
		maybeFetchMine();

		// The sort dropdown, pinned in the header (not the list).
		controlsSlot.add(buildBrowseControlsRow(), BorderLayout.CENTER);

		if (browseStatus != null)
		{
			listContainer.add(messageLabel(browseStatus));
			return;
		}

		final List<RemoteTemplate> shown = filteredBrowse();
		final int total = shown.size();
		if (total == 0)
		{
			listContainer.add(messageLabel(browseIndex.isEmpty()
				? "No templates have been shared yet."
				: "No templates match your search."));
			return;
		}
		// Clamp the offset so a narrower search doesn't strand you past the end.
		if (browseOffset >= total)
		{
			browseOffset = ((total - 1) / PAGE_SIZE) * PAGE_SIZE;
		}
		final int end = Math.min(browseOffset + PAGE_SIZE, total);

		// A pager at BOTH ends: a full page is longer than the panel, so paging from the bottom used to
		// mean scrolling back up to see what you landed on, and paging from the top meant scrolling down
		// to find the control. Each call builds its own row - Swing components have a single parent.
		listContainer.add(buildPaginationRow(total, end < total));
		listContainer.add(Box.createVerticalStrut(6));
		for (int i = browseOffset; i < end; i++)
		{
			listContainer.add(buildRemoteCard(shown.get(i)));
			listContainer.add(Box.createVerticalStrut(6));
		}
		listContainer.add(Box.createVerticalGlue()); // bottom pager sits at the bottom of the panel
		listContainer.add(buildPaginationRow(total, end < total));
	}

	// The index narrowed to the search text and put in the chosen order. Redone on every rebuild rather
	// than cached: the whole catalogue is a few hundred entries of metadata.
	private List<RemoteTemplate> filteredBrowse()
	{
		final List<RemoteTemplate> out = new ArrayList<>();
		for (RemoteTemplate rt : browseIndex)
		{
			if (matchesQuery(rt))
			{
				out.add(rt);
			}
		}
		final Comparator<RemoteTemplate> order;
		if ("newest".equals(browseSort))
		{
			order = Comparator.comparingLong((RemoteTemplate rt) -> rt.created).reversed();
		}
		else if ("popular".equals(browseSort))
		{
			order = Comparator.comparingInt((RemoteTemplate rt) -> rt.recent).reversed();
		}
		else
		{
			order = Comparator.comparingInt((RemoteTemplate rt) -> rt.downloads).reversed();
		}
		// Ties fall back to newest first, so a page holds the same cards from one rebuild to the next.
		out.sort(order.thenComparing(Comparator.comparingLong((RemoteTemplate rt) -> rt.id).reversed()));
		return out;
	}

	// Name, author and description, case-insensitively.
	private boolean matchesQuery(RemoteTemplate rt)
	{
		return query.isEmpty() || containsQuery(rt.name) || containsQuery(rt.author) || containsQuery(rt.description);
	}

	private boolean containsQuery(String s)
	{
		return s != null && s.toLowerCase(Locale.ROOT).contains(query);
	}

	// One line: the sort dropdown. Changing it re-orders the fetched index in place; nothing is requested.
	private JPanel buildBrowseControlsRow()
	{
		final JComboBox<String> sort = new JComboBox<>(SORT_LABELS);
		styleCombo(sort);
		sort.setSelectedIndex(sortIndex());
		sort.addActionListener(e ->
		{
			browseSort = SORT_KEYS[sort.getSelectedIndex()];
			browseOffset = 0;
			rebuildOnEdt();
		});
		return controlsRow(sort);
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

	private JPanel buildPaginationRow(int total, boolean hasMore)
	{
		return paginationRow(browseOffset, total, hasMore, off ->
		{
			browseOffset = off;
			rebuildOnEdt();
		});
	}

	// Single line: «  <  1-10 of 229  >  ». The range label carries the count, so there's no separate
	// "Page X of N" row and no separate Count line. Shared by Browse and My Templates, both paged locally.
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

	// A Browse card built from the index entry alone: name, author, size and counts. The layout behind it
	// is only fetched when the card is previewed or imported.
	private JPanel buildRemoteCard(RemoteTemplate rt)
	{
		final Card card = cardPanel();
		card.setLayout(new BorderLayout(8, 4));
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

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
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				previewRemote(rt);
			}
		};
		card.setToolTipText("Click to preview this template");
		for (final JComponent c : new JComponent[]{card, text, name, author, meta})
		{
			c.addMouseListener(viewClick);
			c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}
		wireHover(card, text, name, author, meta);

		final JPanel actions = iconRow();
		// The count icons ARE the buttons: download imports, flag reports. Glue around each spreads
		// them across the card while each keeps its full width (the count is never clipped).
		actions.add(Box.createHorizontalGlue());
		actions.add(countIcon(PanelIcons.download(UPVOTE_COLOR), rt.downloads, UPVOTE_COLOR, "Import a copy to My Templates", () -> importRemote(rt)));
		actions.add(Box.createHorizontalGlue());
		actions.add(actionIcon("&#9873;", rt.reports, DOWNVOTE_COLOR, "Report this template", () -> reportRepo(rt.id)));
		actions.add(Box.createHorizontalGlue());
		if (ownsRemote(rt.id))
		{
			actions.add(clickableIcon(PanelIcons.xMark(DOWNVOTE_COLOR), "Delete your shared template", () -> deleteRemote(rt.id)));
			actions.add(Box.createHorizontalGlue());
		}
		// Right-click menu, mirroring the icon row. Edit appears only when this is one of your shares and
		// its local copy is still here - editing happens on that copy, then Update pushes it.
		final JPopupMenu menu = new JPopupMenu();
		menuItem(menu, "Preview", () -> previewRemote(rt));
		menuItem(menu, ownsRemote(rt.id) ? "Restore to My Templates" : "Import to My Templates", () -> importRemote(rt));
		final BankTemplate ownedCopy = localOwnedCopy(rt.id);
		if (ownedCopy != null)
		{
			menuItem(menu, "Edit layout", () -> editTemplate(ownedCopy));
			menuItem(menu, "Update shared copy", () -> share(ownedCopy));
		}
		if (!ownsRemote(rt.id))
		{
			menuItem(menu, "Report", () -> reportRepo(rt.id));
		}
		else
		{
			menu.addSeparator();
			menuItem(menu, "Delete shared template", () -> deleteRemote(rt.id));
		}
		attachPopup(menu, card, text, name, author, meta);
		card.add(southStack(tabIconStrip(tabIconsOf(rt)), actions), BorderLayout.SOUTH);
		return card;
	}

	// Fetch a card's layout, then hand it on. Nothing is shown while it loads: the file is small, and a
	// repeat view of the same revision comes straight from the disk cache. A failure gets a dialog.
	private void withLayout(RemoteTemplate rt, Consumer<TemplateRepositoryClient.LayoutFile> then)
	{
		repositoryClient.fetchLayout(rt.id, rt.rev,
			layout -> SwingUtilities.invokeLater(() -> then.accept(layout)),
			error -> SwingUtilities.invokeLater(() ->
				JOptionPane.showMessageDialog(this, error, "Couldn't load template", JOptionPane.WARNING_MESSAGE)));
	}

	private void previewRemote(RemoteTemplate rt)
	{
		withLayout(rt, layout -> showPreview(rt.toTemplate(layout)));
	}

	// Snapshot who shared a template at import time, so the imported card can still name them (and show
	// how the original is doing) once it lives in My Templates.
	private BankTemplate.OwnerProfile capturedOwner(RemoteTemplate rt)
	{
		final BankTemplate.OwnerProfile op = new BankTemplate.OwnerProfile();
		op.name = authorOf(rt);
		op.downloads = rt.downloads;
		op.reports = rt.reports;
		return op;
	}

	// The name a card shows for an uploader: "Anonymous" when they asked for that (the server blanks the
	// author too, but the flag is the promise) or when there's no name to show.
	private static String authorOf(RemoteTemplate rt)
	{
		return rt.anonymous || rt.author == null || rt.author.trim().isEmpty() ? "Anonymous" : rt.author.trim();
	}

	// The "by ..." line for a My Templates card: a preset's source, an imported template's original
	// uploader, or "by you".
	private String localByline(BankTemplate t, BankTemplate.OwnerProfile owner)
	{
		if (t.isPreset())
		{
			return "Built-in preset";
		}
		if (owner != null)
		{
			return "by " + (owner.name != null && !owner.name.isEmpty() ? owner.name : "Anonymous");
		}
		return "by you";
	}

	private String byLine(RemoteTemplate rt)
	{
		return "by " + authorOf(rt);
	}

	// A card panel. Height is capped to its preferred height so the vertical list doesn't stretch it.
	private Card cardPanel()
	{
		return cardPanel(false);
	}

	// active = the currently applied template, drawn with a red highlight glow around its edge.
	private Card cardPanel(final boolean active)
	{
		final Card card = new Card(active);
		card.setOpaque(false);
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	// A template card: a dark gradient with a bronze rim, a red glow when it's the applied template, and a
	// lift on hover - the whole card is the click target in both lists, so it needs to say so.
	private static final class Card extends JPanel
	{
		private static final Color TOP = new Color(0x22, 0x22, 0x22);
		private static final Color BOTTOM = new Color(0x16, 0x16, 0x16);
		private static final Color RIM = new Color(205, 127, 50, 130);

		private final boolean active;
		private boolean hover;

		Card(boolean active)
		{
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
			final RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 10, 10);
			// clip() INTERSECTS the clip we were handed (the scroll viewport's, among others); setClip()
			// would replace it and let a part-scrolled card paint over the pinned bottom bar.
			final Shape oldClip = g2.getClip();
			g2.clip(shape);
			g2.setPaint(new GradientPaint(0, 0, TOP, w, h, BOTTOM));
			g2.fillRect(0, 0, w, h);
			g2.setClip(oldClip);
			g2.setColor(RIM);
			g2.draw(shape);
			if (hover)
			{
				g2.setColor(new Color(255, 255, 255, 20));
				g2.fill(shape);
				g2.setStroke(new BasicStroke(1f));
				g2.setColor(new Color(255, 255, 255, 70));
				g2.draw(shape);
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
	// between the text and icon buttons doesn't flicker.
	private static void wireHover(Card card, JComponent... parts)
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

	// The icons a local template's tabs would show down the side of the bank: each tab's chosen icon, or
	// its first item when it has none (exactly how the bank picks a tab's button). Empty tabs contribute
	// nothing.
	private static List<Integer> tabIconsOf(List<TabLayout> tabs)
	{
		final List<Integer> ids = new ArrayList<>();
		if (tabs == null)
		{
			return ids;
		}
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
		return ids;
	}

	// The same for an index entry: the server already applied that rule, so this only drops the tabs it
	// marked empty.
	private static List<Integer> tabIconsOf(RemoteTemplate rt)
	{
		final List<Integer> ids = new ArrayList<>();
		if (rt.tabs == null)
		{
			return ids;
		}
		for (RemoteTemplate.TabRef t : rt.tabs)
		{
			if (t != null && t.icon > 0)
			{
				ids.add(t.icon);
			}
		}
		return ids;
	}

	// A strip of the template's tab icons, so a card says what's actually IN the template without opening
	// it. Returns null when there's nothing to draw, so a single-tab template keeps a compact card.
	private JComponent tabIconStrip(List<Integer> ids)
	{
		if (ids == null || ids.isEmpty())
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

	// Yours if a local owned copy points at it, or if the server says this character shared it (which
	// survives a reinstall that wiped the local flag).
	private boolean ownsRemote(long repoId)
	{
		if (mineIds.contains(repoId))
		{
			return true;
		}
		for (BankTemplate t : templateManager.getUserTemplates())
		{
			if (t.isOwned() && t.getRepoId() != null && t.getRepoId() == repoId)
			{
				return true;
			}
		}
		return false;
	}

	// Entering Browse: back to the first page, with the index loaded.
	private void newSearch()
	{
		browseOffset = 0;
		loadBrowse(false);
	}

	// Loads the catalogue index. Within 60 seconds of the last fetch the client hands the cached list
	// straight back, so switching tabs costs nothing; force asks the origin again regardless.
	private void loadBrowse(boolean force)
	{
		if (!repositoryClient.isEnabled())
		{
			rebuildOnEdt(); // the enable prompt
			return;
		}
		// Only announce the wait when there's nothing to show meanwhile; a refresh keeps the old list up.
		browseStatus = browseIndex.isEmpty() ? "Loading…" : null;
		rebuildOnEdt();
		repositoryClient.fetchIndex(force,
			list -> SwingUtilities.invokeLater(() ->
			{
				browseIndex.clear();
				browseIndex.addAll(list);
				browseStatus = null;
				applyIndexStats(list);
				rebuildOnEdt();
			}),
			error -> SwingUtilities.invokeLater(() ->
			{
				// A list we already have beats an error message; the failure only matters with nothing to show.
				if (browseIndex.isEmpty())
				{
					browseStatus = error;
				}
				rebuildOnEdt();
			}));
	}

	private void importRemote(RemoteTemplate rt)
	{
		withLayout(rt, layout ->
		{
			final BankTemplate t = rt.toTemplate(layout);
			t.setRepoId(rt.id);
			final boolean mine = mineIds.contains(rt.id);
			if (mine)
			{
				// One of this character's own shares (a reinstall dropped the local copy): re-link it as
				// owned so it can be updated and deleted from here again.
				t.setOwned(true);
				t.setSharedAnonymously(rt.anonymous);
				t.setShareDownloads(rt.downloads);
				t.setShareReports(rt.reports);
			}
			else
			{
				t.setOwned(false);
				t.setOwnerProfile(capturedOwner(rt));   // remember who shared it, for the card
			}
			t.setName(uniqueName(capName(t.getName())));
			if (!templateManager.saveUserTemplate(t))
			{
				JOptionPane.showMessageDialog(this, "Could not import that template.", "Import failed", JOptionPane.ERROR_MESSAGE);
				return;
			}
			if (!mine)
			{
				// Best effort and deduped server-side. The card's count catches up when the index is next
				// rebuilt, so there's nothing to refresh here.
				repositoryClient.recordImport(rt.id, null);
			}
			JOptionPane.showMessageDialog(this,
				(mine ? "Restored your shared \"" : "Imported \"") + t.getName() + "\" to My Templates.",
				"Imported", JOptionPane.INFORMATION_MESSAGE);
		});
	}

	// Sharing, reporting and deleting are keyed on the logged-in character (its account hash derives the
	// clientId the server treats as the owner), so logged out there is nothing to attribute them to.
	private boolean requireLogin()
	{
		if (repositoryClient.hasIdentity())
		{
			return true;
		}
		JOptionPane.showMessageDialog(this,
			"Log in to your RuneScape account first. Sharing, reporting and deleting are tied to the character you're logged in as.",
			"Not logged in", JOptionPane.WARNING_MESSAGE);
		return false;
	}

	private void reportRepo(long repoId)
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
				JOptionPane.showMessageDialog(this, "Reported. Thanks.", "Reported", JOptionPane.INFORMATION_MESSAGE)),
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
				mineIds.remove(repoId);
				loadBrowse(false); // the client dropped its 60 second floor, so this asks the origin
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
							mineIds.add(newId);
							// Sharing an imported template makes it YOURS. It uploaded under your name and account
							// (create never sends the original owner), so drop the original owner snapshot too:
							// the card now shows you, and the new shared copy's counts start at zero instead of
							// inheriting theirs. The index fills in the real numbers as players import it. This
							// is what stops someone re-publishing another player's template under that player's name.
							template.setOwnerProfile(null);
							template.setShareDownloads(0);
							template.setShareReports(0);
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
		final String msg = shared
			? "Delete \"" + template.getName() + "\" locally AND remove it from the community repository?"
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

		if (!shared)
		{
			removeLocally.run();
			return;
		}
		if (!requireLogin())
		{
			return;
		}
		final long repoId = template.getRepoId();
		repositoryClient.delete(repoId,
			() -> SwingUtilities.invokeLater(() ->
			{
				mineIds.remove(repoId);
				removeLocally.run();
			}),
			error -> SwingUtilities.invokeLater(() ->
			{
				final int alsoLocal = JOptionPane.showConfirmDialog(this,
					error + "\n\nDelete the local copy anyway?", "Delete failed",
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
				if (alsoLocal == JOptionPane.YES_OPTION)
				{
					removeLocally.run();
				}
			}));
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
		});
		panel.add(enable);
		return panel;
	}

	// ---- Shared UI helpers ------------------------------------------------------------------

	private static final Color UPVOTE_COLOR = new Color(110, 190, 110);
	private static final Color DOWNVOTE_COLOR = new Color(220, 110, 110);
	private static final Color ICON_GOLD = new Color(0xE8, 0xC0, 0x50); // gold - the view / web actions
	private static final Color STAT_MUTED = new Color(0x70, 0x70, 0x70); // greyed share stats (not shared yet)

	// Header icons. Drawn small and light so they read on the dark panel without competing with the
	// tabs underneath them. A missing resource yields null, and every use is null-guarded, so a bad
	// build loses a button rather than the whole panel.
	private static final ImageIcon BELL_ICON = loadPanelIcon("bell.png");
	private static final ImageIcon COG_ICON = loadPanelIcon("cog.png");
	private static final ImageIcon SUPPORT_ICON = loadPanelIcon("support.png");

	// The plugin's issue tracker: bug reports and questions both go there.
	private static final String SUPPORT_URL = "https://github.com/TheSpryt/bank-templates/issues/new";

	/** A flat, icon-only header button. Falls back to a short text label when the icon is missing. */
	private JButton headerIcon(ImageIcon icon, String alt, String tooltip, Runnable action)
	{
		final JButton b = icon != null ? new JButton(icon) : new JButton(alt);
		b.setToolTipText(tooltip);
		b.getAccessibleContext().setAccessibleName(alt);
		b.setFocusPainted(false);
		b.setBorderPainted(false);
		b.setContentAreaFilled(false);
		b.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		b.setBackground(ColorScheme.DARK_GRAY_COLOR);
		b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
		b.addActionListener(e -> action.run());
		return b;
	}

	private static ImageIcon loadPanelIcon(String file)
	{
		try
		{
			final java.awt.image.BufferedImage img =
				ImageUtil.loadImageResource(BankTemplatesPlugin.class, "/com/banktemplates/" + file);
			return img == null ? null : new ImageIcon(img);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	// The narrow side panel can't fit long names, so clamp them with an ellipsis and show the full text
	// on hover.
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

	// "y items · n tabs" for a Browse card, straight from the index entry (no layout needed).
	private static String remoteMeta(RemoteTemplate rt)
	{
		final int tabs = rt.tabCount();
		return rt.items + " items" + (tabs > 1 ? " · " + tabs + " tabs" : "");
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
		// Ownership is already stated by the card's "by ..." line, so it isn't repeated here.
		return total + " items" + (tabs > 1 ? " · " + tabs + " tabs" : "");
	}

	// Fold the index's live import and report counts into the local templates that mirror a shared one:
	// your own shares (matched by repoId) and unedited imports (through their uploader snapshot). Nothing
	// else reports these numbers now, so this is what stops My Templates' counts freezing at the moment
	// of the share or import. Persisted without stamping updatedAt: it isn't an edit.
	private void applyIndexStats(List<RemoteTemplate> index)
	{
		final Map<Long, RemoteTemplate> byId = new HashMap<>();
		for (RemoteTemplate rt : index)
		{
			byId.put(rt.id, rt);
		}
		for (BankTemplate t : templateManager.getUserTemplates())
		{
			if (t.isPreset() || t.getRepoId() == null)
			{
				continue;
			}
			final RemoteTemplate rt = byId.get(t.getRepoId());
			if (rt == null)
			{
				continue;
			}
			if (t.isOwned())
			{
				if (!Integer.valueOf(rt.downloads).equals(t.getShareDownloads())
					|| !Integer.valueOf(rt.reports).equals(t.getShareReports()))
				{
					t.setShareDownloads(rt.downloads);
					t.setShareReports(rt.reports);
					templateManager.saveUnchanged(t);
				}
			}
			else if (t.getOwnerProfile() != null)
			{
				final BankTemplate.OwnerProfile op = t.getOwnerProfile();
				if (!Integer.valueOf(rt.downloads).equals(op.downloads) || !Integer.valueOf(rt.reports).equals(op.reports))
				{
					op.downloads = rt.downloads;
					op.reports = rt.reports;
					templateManager.saveUnchanged(t);
				}
			}
		}
	}

	// Ask the server which ids this character owns, once per identity (the first Browse render after a
	// login). Cleared on logout so another character's shares never read as yours.
	private void maybeFetchMine()
	{
		if (!repositoryClient.hasIdentity())
		{
			mineIds.clear();
			mineFetchedFor = null;
			return;
		}
		final String id = repositoryClient.clientId();
		if (id.equals(mineFetchedFor))
		{
			return;
		}
		mineFetchedFor = id;
		repositoryClient.fetchMine(ids -> SwingUtilities.invokeLater(() ->
		{
			if (!id.equals(repositoryClient.clientId()))
			{
				return; // logged out or switched character before the answer came
			}
			mineIds.clear();
			mineIds.addAll(ids);
			if (!ids.isEmpty() && BROWSE.equals(mode))
			{
				rebuildOnEdt();
			}
		}));
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
	// "by you", it drops the original uploader, and it detaches from the community source
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
