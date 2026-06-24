package com.banktemplates;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Slf4j
@Singleton
public class BankTemplatesPanel extends PluginPanel
{
	private static final Border CARD_BORDER = BorderFactory.createEmptyBorder(6, 6, 6, 6);
	private static final Border ACTIVE_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(0, 3, 0, 0, ColorScheme.BRAND_ORANGE),
		BorderFactory.createEmptyBorder(6, 3, 6, 6));

	private static final String LOCAL = "local";
	private static final String BROWSE = "browse";
	private static final String UPDATES = "updates";
	private static final int PAGE_SIZE = 20;
	private static final int MAX_NAME_LENGTH = 25;
	private static final int MAX_DESCRIPTION_LENGTH = 500;

	private static final String[] SORT_LABELS = {"Most imported", "Newest", "Popular (30 days)"};
	private static final String[] SORT_KEYS = {"imported", "newest", "popular"};

	private final TemplateManager templateManager;
	private final ItemManager itemManager;
	private final TemplateRepositoryClient repositoryClient;
	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final BankTemplatesConfig config;
	private final LayoutEditor layoutEditor;
	private final ItemIndex itemIndex;

	private final JPanel listContainer = new JPanel();
	private final SearchBar searchBar = new SearchBar();
	private final JPanel tabsPanel = new JPanel();
	private final JButton localTab = new JButton("My Templates");
	private final JButton browseTab = new JButton("Browse");
	private final JButton updatesTab = new JButton("Updates");

	// The newest bundled patch notes (this build's version + notes), or null if none.
	private final Changelog.Entry latestUpdate;
	private final java.util.List<Changelog.Entry> allUpdates;

	private String mode = LOCAL;
	private String query = "";

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
		if (query.isEmpty())
		{
			final boolean hasActive = templateManager.getActive() != null;
			final JButton remove = styledButton(hasActive ? "Remove Template" : "No template applied");
			remove.setEnabled(hasActive);
			remove.setAlignmentX(Component.LEFT_ALIGNMENT);
			remove.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
			remove.setToolTipText("Stop applying a template and show your normal bank");
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

		listContainer.add(Box.createVerticalStrut(6));
		final JButton captureButton = styledButton("Capture current bank");
		captureButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		captureButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		captureButton.setToolTipText("Save your current bank (all tabs, in order) as a new template");
		captureButton.addActionListener(e -> captureCurrentBank());
		listContainer.add(captureButton);

		listContainer.add(Box.createVerticalStrut(4));
		final JButton newButton = styledButton("New empty layout");
		newButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		newButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		newButton.setToolTipText("Build a layout from scratch - add items you don't own as placeholders");
		newButton.addActionListener(e -> createNewLayout());
		listContainer.add(newButton);

		listContainer.add(Box.createVerticalStrut(8));
		final JPanel reorgRow = new JPanel(new BorderLayout(6, 0));
		reorgRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		reorgRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		reorgRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		final JLabel reorgLabel = new JLabel("Reorganise");
		reorgLabel.setForeground(Color.WHITE);
		reorgLabel.setToolTipText("Guide for rearranging your real bank to match the active template");
		reorgRow.add(reorgLabel, BorderLayout.WEST);

		final String off = "Off";
		final JComboBox<String> reorgMode = new JComboBox<>();
		reorgMode.addItem(off);
		for (BankTemplatesConfig.ReorgDisplay d : BankTemplatesConfig.ReorgDisplay.values())
		{
			reorgMode.addItem(d.toString());
		}
		reorgMode.setSelectedItem(config.showReorgHelper() ? config.reorgDisplay().toString() : off);
		reorgMode.setFocusable(false);
		reorgMode.addActionListener(e ->
		{
			final String sel = (String) reorgMode.getSelectedItem();
			if (off.equals(sel))
			{
				configManager.setConfiguration(BankTemplatesConfig.GROUP, "showReorgHelper", false);
			}
			else
			{
				for (BankTemplatesConfig.ReorgDisplay d : BankTemplatesConfig.ReorgDisplay.values())
				{
					if (d.toString().equals(sel))
					{
						configManager.setConfiguration(BankTemplatesConfig.GROUP, "reorgDisplay", d);
						break;
					}
				}
				configManager.setConfiguration(BankTemplatesConfig.GROUP, "showReorgHelper", true);
			}
			onActiveChanged.run();
		});
		reorgRow.add(reorgMode, BorderLayout.CENTER);
		listContainer.add(reorgRow);
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
		card.add(titleBlock(template.getName(), localMeta(template),
			active ? ColorScheme.BRAND_ORANGE : Color.WHITE), BorderLayout.CENTER);

		final JPanel buttons = buttonRow();
		buttons.add(activeOrUse(active, () -> select(template)));
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

		for (RemoteTemplate rt : browseResults)
		{
			listContainer.add(buildRemoteCard(rt));
			listContainer.add(Box.createVerticalStrut(6));
		}

		listContainer.add(buildPaginationRow());
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
		repositoryClient.search(searchBar.getText(), browseSort, browseOffset,
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
		final String msg = shared
			? "Delete \"" + template.getName() + "\" locally AND remove it from the community repository?"
			: "Delete template \"" + template.getName() + "\"?";
		final int choice = JOptionPane.showConfirmDialog(this, msg, "Delete template", JOptionPane.YES_NO_OPTION);
		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}

		if (shared)
		{
			if (!requireLogin())
			{
				return;
			}
			repositoryClient.delete(template.getRepoId(),
				() -> SwingUtilities.invokeLater(() ->
				{
					templateManager.deleteUserTemplate(template);
					rebuildOnEdt();
					onActiveChanged.run();
				}),
				error -> SwingUtilities.invokeLater(() ->
				{
					final int alsoLocal = JOptionPane.showConfirmDialog(this,
						error + "\n\nDelete the local copy anyway?", "Delete failed",
						JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
					if (alsoLocal == JOptionPane.YES_OPTION)
					{
						templateManager.deleteUserTemplate(template);
						rebuildOnEdt();
						onActiveChanged.run();
					}
				}));
		}
		else
		{
			// Deleting an imported copy: tell the repo so its import count drops back.
			if (!template.isOwned() && template.getRepoId() != null && repositoryClient.isEnabled())
			{
				repositoryClient.unimport(template.getRepoId());
			}
			templateManager.deleteUserTemplate(template);
			rebuildOnEdt();
			onActiveChanged.run();
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
		});
		panel.add(enable);
		return panel;
	}

	// ---- Shared UI helpers ------------------------------------------------------------------

	private static final Color UPVOTE_COLOR = new Color(110, 190, 110);
	private static final Color DOWNVOTE_COLOR = new Color(220, 110, 110);

	// Browse card title: name + imports (▲, green) and reports (▼, red) shown as up/down votes.
	private JPanel remoteTitleBlock(RemoteTemplate rt)
	{
		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		final JLabel name = new JLabel(rt.name);
		name.setFont(FontManager.getRunescapeFont());
		name.setForeground(Color.WHITE);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(name);

		final JPanel votes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		votes.setOpaque(false);
		votes.setAlignmentX(Component.LEFT_ALIGNMENT);
		votes.add(voteLabel("<html><span style='font-family:Dialog'>&#9650;</span>&nbsp;" + rt.downloads + "</html>", UPVOTE_COLOR));
		votes.add(voteLabel("<html><span style='font-family:Dialog'>&#9660;</span>&nbsp;" + rt.reports + "</html>", DOWNVOTE_COLOR));

		final String by = rt.anonymous || rt.author == null || rt.author.isEmpty() ? "Anonymous" : rt.author;
		final JLabel author = new JLabel("by " + by);
		author.setFont(FontManager.getRunescapeSmallFont());
		author.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		votes.add(author);

		text.add(votes);
		return text;
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

		final JLabel name = new JLabel(nameText);
		name.setFont(FontManager.getRunescapeFont());
		name.setForeground(nameColor);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(name);

		final JLabel meta = new JLabel(metaText);
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(meta);
		return text;
	}

	private JButton activeOrUse(boolean active, Runnable onUse)
	{
		final JButton button = styledButton(active ? "Active" : "Use");
		button.setEnabled(!active);
		button.addActionListener(e -> onUse.run());
		return button;
	}

	private JPanel buttonRow()
	{
		// Tight gaps so all the actions fit on one row; WrapLayout is just a safety net if they don't.
		final JPanel buttons = new JPanel(new WrapLayout(FlowLayout.RIGHT, 2, 2));
		buttons.setOpaque(false);
		return buttons;
	}

	private JPanel cardPanel(boolean active)
	{
		final JPanel card = new JPanel(new BorderLayout(0, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(active ? ACTIVE_BORDER : CARD_BORDER);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
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
		button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		button.setForeground(Color.WHITE);
		button.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		return button;
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
		final String kind = template.isPreset() ? "Preset" : template.isOwned() ? "Shared by you" : "Custom";
		final int tabs = template.tabCount();
		return kind + " · " + template.itemCount() + " items" + (tabs > 1 ? " · " + tabs + " tabs" : "");
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
			TemplateEditor.open(this, itemManager, itemIndex, clientThread, layoutEditor, template);
		}
		rebuildOnEdt();
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
			TemplateEditor.open(this, itemManager, itemIndex, clientThread, layoutEditor, t);
		}
		else
		{
			JOptionPane.showMessageDialog(this, "Could not create that layout.", "New layout failed", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void showPreview(BankTemplate template)
	{
		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		if (template.getDescription() != null && !template.getDescription().isEmpty())
		{
			final JLabel desc = new JLabel("<html><body style='width:320px'>" + escape(template.getDescription()) + "</body></html>");
			desc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			desc.setAlignmentX(Component.LEFT_ALIGNMENT);
			desc.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
			content.add(desc);
		}
		content.add(TemplatePreview.build(itemManager, clientThread, template));

		showSideDialog("Preview: " + template.getName(), content);
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
			final Point loc = owner.getLocationOnScreen();
			// Prefer the left of the client; if there's no room, drop it to the right edge instead.
			int x = loc.x - dialog.getWidth() - 8;
			if (x < 0)
			{
				x = loc.x + owner.getWidth() + 8;
			}
			dialog.setLocation(Math.max(0, x), Math.max(0, loc.y));
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
