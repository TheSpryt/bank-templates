# Bank Templates

Organise your bank with saved, shareable **layout templates**. A template arranges your items into a
chosen order - per tab - and the plugin renders it **virtually** over the bank. Your real bank is
never moved, and nothing is ever sent to the game server, so it's safe under Jagex's third-party
client rules.

You can capture your current bank as a template, apply templates (yours or the community's), and
share/browse templates through an optional community repository.

![Bank Templates demo](docs/images/demo.gif)

---

## Features

- **Per-tab templates** - a template stores a layout for the main view and each numbered bank tab.
  The plugin applies the layout for whichever tab you're viewing.
- **Capture your bank** - one click snapshots your whole bank (every tab, in order) into a template.
- **Placeholders** - items a template wants but you don't own yet show as faded icons in place.
- **Filler slots (🚫)** - reserve slots for items you still need to acquire.
- **Template-defined width** - layouts render at the column count they were designed for (default 8),
  so they look the same regardless of your bank window size.
- **Plays nicely with Bank Tags** - when a Bank Tags "tag tab" or a search is open, the plugin steps
  aside and lets that view render normally.
- **Community repository (optional, opt-in)** - browse, search, preview, import, and share templates;
  sort by most-imported / newest / popular; up/down "votes" (imports vs reports) on each.

## How it stays within the rules

The plugin only **reads** your bank and **repositions item widgets client-side** for display - the
same technique RuneLite's built-in Bank Tag Layouts uses. It never moves items, injects input, or
adds menu entries that send actions to the server, all of which are forbidden by Jagex's third-party
guidelines. Reorganising your real bank is always done by you, manually.

---

## Using the plugin

Open the **Bank Templates** side panel from the RuneLite toolbar.

### Capture & apply

1. Arrange your bank how you like, then click **Capture current bank** → give it a name. It appears
   under **My templates** as "N items · M tabs".
2. Click **Use** on a template to apply it. Open the bank and switch between tabs - each tab shows its
   own layout. Items you don't own appear faded; 🚫 marks reserved slots.
3. Click **View** to preview a template - an interactive mini-bank with tab buttons.
4. Click **Remove Template** (top of the panel) to go back to your normal bank.
5. **Del** removes a template (for ones you've shared, it offers to remove it from the repository too).

### Reorganise helper

If you'd rather rearrange your *real* bank to match a template: select the template, then tick
**Reorganise helper** at the bottom of *My templates*. The plugin shows your real bank (instead of the
virtual layout) and outlines the slots that don't match, with an arrow and ghost icon showing where
the next item goes - you drag the items yourself; it never moves anything. Untick it to go back to the
virtual layout.

### Community repository

Sharing/browsing is **off by default** and **opt-in** (it contacts a third-party server). Click the
**Browse** tab and then **Enable community repository** (you'll see the IP-address notice), or toggle
it in settings.

- **Browse / search** - search matches template names *and* RuneScape names. Sort by **Most imported**,
  **Newest**, or **Popular (30 days)**. Pages with Prev/Next.
- **Import** - saves a copy to *My templates*.
- **Share** - uploads your template, credited to your RuneScape name. Re-sharing one you own
  **updates it in place** (the button reads **Update**). Editing an *imported* template and sharing it
  creates a **new** entry - it never overwrites the original author's.
- **Report** - flags a template for moderation (one per account per template).
- Limits: you must be logged in to share/report/delete; max 10 shared templates per account; uploads
  are rate-limited.

> Templates are community-sourced - there are no bundled presets. (Maintainers may later embed
> popular community templates as built-ins.)

---

## Settings

| Setting | Default | What it does |
|---|---|---|
| Apply template to bank | on | Render the active template over the bank |
| Show placeholders for unowned items | on | Faded icons for items you don't own yet |
| Hide items not in the template | off | Hide leftover items instead of listing them below |
| Target highlight | cyan | Reorganise-helper highlight colour |

The **Reorganise helper** toggle lives in the side panel (under *My templates*), not in settings.
| Enable community repository | off | Opt-in; browse/share (sends your IP to the repo server) |

## Privacy

The community repository feature is opt-in. When enabled, browsing/sharing sends your IP address to
the configured server (the required RuneLite notice is shown). Sharing/reporting also sends a **salted
hash of your account id** (not the raw account hash, not your username) so the server can attribute
ownership and enforce limits. Nothing is sent while the feature is disabled.

## Backend

The community repository is served by a small Cloudflare Worker + D1 backend, maintained in a
separate private repository (not part of this repo).

## Screenshots

| My templates | Browse the community repository | Template preview |
|---|---|---|
| ![My templates](docs/images/my-templates.png) | ![Browse](docs/images/browse.png) | ![Preview](docs/images/preview.png) |

## License

BSD 2-Clause. See [LICENSE](LICENSE).
