package com.banktemplates;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Window;
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
import net.runelite.client.ui.components.IconTextField;

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
	private static final int PAGE_SIZE = 20;
	private static final int MAX_NAME_LENGTH = 25;

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

	private final JPanel listContainer = new JPanel();
	private final IconTextField searchBar = new IconTextField();
	private final JButton localTab = new JButton("My Templates");
	private final JButton browseTab = new JButton("Browse");

	private String mode = LOCAL;
	private String query = "";

	private final List<RemoteTemplate> browseResults = new ArrayList<>();
	private String browseStatus;
	private String browseSort = "imported";
	private int browseOffset = 0;
	private boolean browseHasMore = false;

	private Runnable onActiveChanged = () ->
	{
	};

	@Inject
	BankTemplatesPanel(TemplateManager templateManager, ItemManager itemManager, TemplateRepositoryClient repositoryClient,
		Client client, ClientThread clientThread, ConfigManager configManager, BankTemplatesConfig config,
		LayoutEditor layoutEditor)
	{
		this.templateManager = templateManager;
		this.itemManager = itemManager;
		this.repositoryClient = repositoryClient;
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.config = config;
		this.layoutEditor = layoutEditor;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);

		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
		listContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(listContainer, BorderLayout.CENTER);

		// PluginPanel wraps us in a JScrollPane but leaves its default border (a thin light outline) and
		// the chunky default scrollbar. Clear the border and apply our thin scrollbar.
		final Component ancestor = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
		if (ancestor instanceof JScrollPane)
		{
			final JScrollPane wrapper = (JScrollPane) ancestor;
			wrapper.setBorder(BorderFactory.createEmptyBorder());
			ThinScrollBarUI.style(wrapper);
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

		final JLabel title = new JLabel("Bank Templates");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(title);

		header.add(Box.createVerticalStrut(8));

		final JPanel tabs = new JPanel(new GridLayout(1, 2, 4, 0));
		tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
		localTab.setFocusPainted(false);
		browseTab.setFocusPainted(false);
		localTab.addActionListener(e -> switchMode(LOCAL));
		browseTab.addActionListener(e -> switchMode(BROWSE));
		tabs.add(localTab);
		tabs.add(browseTab);
		header.add(tabs);

		header.add(Box.createVerticalStrut(8));

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setPreferredSize(new Dimension(100, 36));
		searchBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
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
		else
		{
			buildLocalView();
		}
		listContainer.revalidate();
		listContainer.repaint();
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
		buttons.add(iconButton("View", "Preview this template", () -> showPreview(template)));
		if (!template.isPreset())
		{
			final boolean editingThis = layoutEditor.isEditing(template);
			buttons.add(iconButton(editingThis ? "Done" : "Edit",
				editingThis ? "Finish editing this layout" : "Add, move and arrange items (no need to own them)",
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
		final JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JButton prev = styledButton("‹ Prev");
		prev.setEnabled(browseOffset > 0);
		prev.addActionListener(e ->
		{
			browseOffset = Math.max(0, browseOffset - PAGE_SIZE);
			loadBrowse();
		});
		row.add(prev);

		final JLabel page = new JLabel("Page " + (browseOffset / PAGE_SIZE + 1));
		page.setFont(FontManager.getRunescapeSmallFont());
		page.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(page);

		final JButton next = styledButton("Next ›");
		next.setEnabled(browseHasMore);
		next.addActionListener(e ->
		{
			browseOffset += PAGE_SIZE;
			loadBrowse();
		});
		row.add(next);
		return row;
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

		final javax.swing.JCheckBox anon = new javax.swing.JCheckBox(
			"Share anonymously (show me as \"Anonymous\")", template.isSharedAnonymously());
		anon.setToolTipText("Other players won't see your RuneScape name. The repository still records it "
			+ "privately for moderation.");
		message.add(anon, BorderLayout.CENTER);

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
		votes.add(voteLabel("▲ " + rt.downloads, UPVOTE_COLOR));
		votes.add(voteLabel("▼ " + rt.reports, DOWNVOTE_COLOR));

		final String by = rt.anonymous || rt.author == null || rt.author.isEmpty() ? "Anonymous" : rt.author;
		final JLabel author = new JLabel("by " + by);
		author.setFont(FontManager.getRunescapeSmallFont());
		author.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		votes.add(author);

		text.add(votes);
		return text;
	}

	private JLabel voteLabel(String s, Color color)
	{
		final JLabel label = new JLabel(s);
		label.setForeground(color);
		// Default font (not the bitmap RuneScape font) so the ▲/▼ glyphs render.
		label.setFont(label.getFont().deriveFont(11f));
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
			TemplateEditor.open(this, itemManager, clientThread, layoutEditor, template);
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
			TemplateEditor.open(this, itemManager, clientThread, layoutEditor, t);
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
