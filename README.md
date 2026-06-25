# Bank Templates

Organise your bank with saved, shareable **layout templates**. A template arranges your items into a
chosen order, per tab, and the plugin renders it **virtually** over the bank. Your real bank is never
moved, and nothing is ever sent to the game server, so it's safe under Jagex's third-party client rules.

You can capture your current bank as a template, apply templates (yours or the community's), and
share/browse templates through an optional community repository.

![Bank Templates demo](docs/images/demo.gif)

---

## Features

- **Per-tab templates** - a template stores a layout for the main view and each numbered bank tab. The
  plugin applies the layout for whichever tab you're viewing. The all-items view shows your whole
  template grouped tab-by-tab, with thin dividers between tabs and before any items not in the template.
- **Capture your bank** - one click snapshots your whole bank (every tab, in order) into a template.
- **Layout editor** - build or tweak a layout *without owning the items*. Edit in the side panel or
  live over the bank: a **+** button lets you search for any item and drop it in as a faded
  placeholder, then drag to rearrange, swap or insert, and mark filler/empty slots.
- **Placeholders** - items a template wants but you don't own yet show as faded icons in place.
- **Filler slots (🚫)** - reserve slots for items you still need to acquire.
- **Bigger-than-your-bank templates** - if a template has more slots than your bank currently holds,
  the extra slots are still drawn, and the bank's slot counter shows the template's size (turning red
  if it won't fit).
- **Template-defined width** - layouts render at the column count they were designed for (default 8),
  so they look the same regardless of your bank window size.
- **Reorganise helper** - guided help to rearrange your *real* bank to match a template, shown as
  on-item labels, step-by-step prompts in the bank title bar, or both.
- **Works with other bank plugins** - items are positioned by identity (each keeps its own bank slot),
  so plugins like Inventory Setups that highlight or withdraw items by slot still target the right ones.
- **Plays nicely with Bank Tags** - when a Bank Tags "tag tab" or a search is open, the plugin steps
  aside and lets that view render normally.
- **Community repository (optional, opt-in)** - browse, search, preview, import, and share templates
  (with an optional description); sort by most-imported / newest / popular; up/down "votes" (imports vs
  reports) on each.
- **Updates tab** - the side panel shows the latest patch notes whenever the plugin updates.

## How it stays within the rules

The plugin only **reads** your bank and **repositions item widgets client-side** for display, the same
technique RuneLite's built-in Bank Tag Layouts uses. It never moves items, injects input, or adds menu
entries that send actions to the server, all of which are forbidden by Jagex's third-party guidelines.
Reorganising your real bank is always done by you, manually.

---

## Using the plugin

Open the **Bank Templates** side panel from the RuneLite toolbar.

### Capture & apply

1. Arrange your bank how you like, then click **Capture current bank** and give it a name. It appears
   under **My templates** as "N items · M tabs".
2. Click **Use** on a template to apply it. Open the bank and switch between tabs, and each tab shows
   its own layout. Items you don't own appear faded; 🚫 marks reserved slots.
3. Click **View** to preview a template: an interactive mini-bank with tab buttons. The preview opens
   in a window beside the client (so the game stays clickable), and hovering an item shows its name.
4. Click **Remove Template** (top of the panel) to go back to your normal bank.
5. **Del** removes a template (for ones you've shared, it offers to remove it from the repository too).

### Build & edit layouts

You don't need to own an item to put it in a layout. Capture your bank (or click **New empty layout**),
then click **Edit** on the template:

- An editor window opens beside the client, and the template renders **editably over your bank** with a
  green **+** button after the last slot.
- **Add items** - click **+** (in the bank or the editor) and search by name; the item drops in as a
  faded placeholder.
- **Arrange** - drag items to rearrange them (over the bank or in the editor grid). Dragging over the
  bank uses the game's own bank drag, so it behaves just like moving items normally. In the editor you
  can also click a slot then click where it should go; toggle **Swap mode** to swap two slots instead of
  shifting them. Right-click a slot in the editor for *filler / empty / insert / remove*.
- **New tab** - drag an item onto the bank's **+** (new tab) button to start a new template tab (up to
  the game's nine-tab maximum).
- Changes save automatically. **Revert** undoes the whole session; **Done** (or closing the editor)
  finishes. Your real bank is never touched.

### Reorganise helper

If you'd rather rearrange your *real* bank to match a template: select the template, then tick
**Reorganise helper** at the bottom of *My templates*. The plugin shows your real bank (instead of the
virtual layout) and guides you through the moves. **You** drag the items yourself; it never moves
anything. Untick it to go back to the virtual layout.

You can choose how it guides you (from the side panel): **Labels**, **Step-by-step**, **Drunken Sailor**,
or both Labels and Step-by-step together.

- **Labels** tag each out-of-place item with where it needs to go: first its destination tab, then its
  row and column within that tab.
- **Step-by-step** walks you through one move at a time, with the instruction in the bank's title bar
  and the destination slot highlighted. It also helps you set up: when a template needs slots for items
  you don't own yet, it guides you to reserve them with the game's **Bank Fillers** (telling you the
  exact number to enter, or to use **All** when they'd fill your bank, with a **Skip** option); it
  points you to open the tab an item is in before you drag it; and it helps you create extra tabs (drag
  to the **+**) when a template has more tabs than you currently do.
- **Drunken Sailor** is colour-only, no text to read: from the stowaways tab (∞), drag each item to the
  same-coloured numbered tab; then within each tab, drag red items into place until they turn green.

Item kits and alternate versions of an item (charged/uncharged, degraded, recoloured, and so on) count
as the same item, so the helper won't flag a slot just because you hold a different variant. If a Bank
Tags tag tab is open, it points you back to the main bank first.

### Community repository

Sharing/browsing is **off by default** and **opt-in** (it contacts a third-party server). Click the
**Browse** tab and then **Enable community repository** (you'll see the IP-address notice), or toggle
it in settings.

- **Browse / search** - search matches template names *and* RuneScape names. Sort by **Most imported**,
  **Newest**, or **Popular (30 days)**. Pages with Prev/Next.
- **Import** - saves a copy to *My templates*.
- **Share** - uploads your template, credited to your RuneScape name, with an optional **description**
  (up to 500 characters) shown to others in Browse and the preview. Re-sharing one you own **updates it
  in place** (the button reads **Update**). Editing an *imported* template and sharing it creates a
  **new** entry; it never overwrites the original author's. Tick **Share anonymously** in the dialog to
  be shown to other players as **"Anonymous"** (the repository still records your name privately for
  moderation; the choice is remembered for updates).
- **Report** - flags a template for moderation (one per account per template).
- Limits: you must be logged in to share/report/delete; max 10 shared templates per account; uploads
  are rate-limited.

> Templates are community-sourced, with no bundled presets. (Maintainers may later embed popular
> community templates as built-ins.)

---

## Settings

| Setting | Default | What it does |
|---|---|---|
| Notify me about updates | on | Show an Updates tab in the side panel with the latest patch notes |
| Apply template to bank | on | Render the active template over the bank |
| Show placeholders for unowned items | on | Faded icons for items you don't own yet |
| Hide items not in the template | off | Hide leftover items instead of showing them below |
| Target highlight | cyan | Reorganise-helper destination-slot highlight colour |
| Enable community repository | off | Opt-in; browse/share (sends your IP to the repo server) |

The **Reorganise helper** toggle and its display style (Labels / Step-by-step / both) live in the side
panel (under *My templates*), not in settings.

## Privacy

The community repository feature is opt-in. When enabled, browsing/sharing sends your IP address to the
configured server (the required RuneLite notice is shown). Sharing/reporting also sends a **salted hash
of your account id** (not the raw account hash, not your username) so the server can attribute ownership
and enforce limits. Sharing sends your RuneScape name so it can credit the template; choose **Share
anonymously** and other players see **"Anonymous"** instead (your name is still stored privately on the
server for moderation). Nothing is sent while the feature is disabled.

## Backend

The community repository is served by a small Cloudflare Worker + D1 backend.

## Screenshots

| My templates | Browse the community repository | Template preview |
|---|---|---|
| ![My templates](docs/images/my-templates.png) | ![Browse](docs/images/browse.png) | ![Preview](docs/images/preview.png) |

## License

BSD 2-Clause. See [LICENSE](LICENSE).
