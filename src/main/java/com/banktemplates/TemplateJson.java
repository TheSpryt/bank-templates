package com.banktemplates;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Copies a template to and from a plain JSON text, for the "Copy as JSON" and "Import from clipboard"
 * actions (issue #42). Holds no game state: item names come in through a function, so this can be tested
 * on its own.
 * <p>
 * Each slot is written as {@code "4151: Abyssal whip"}, {@code "empty"} or {@code "filler"}, one per line.
 * The name is there so a person, or an AI they paste the text into, can read the layout; on import the id
 * wins and the name is only used when the id is missing. Import also accepts the plugin's own saved-file
 * format ({@code "layout": [ids]}), so files people have already edited by hand still work.
 */
final class TemplateJson
{
	static final String FORMAT = "bank-templates/1";

	// The same limits the community repository enforces, so anything imported can also be shared.
	static final int MAX_NAME = 25;
	static final int MAX_DESCRIPTION = 500;
	static final int MAX_TABS = 10;
	static final int MAX_SLOTS = 4096;
	static final int MAX_ITEM_ID = 2_000_000;
	static final int MAX_COLUMNS = 32;
	private static final int DEFAULT_COLUMNS = 8;
	private static final String DEFAULT_NAME = "Imported template";
	private static final int MAX_LISTED_UNKNOWN = 5;

	private static final String NOTES = "Each slot is \"<item id>: <item name>\", \"empty\" or \"filler\". "
		+ "Reorder slots, or move them between tabs. Tab 0 is the main tab, 1 to 9 are the numbered tabs. "
		+ "When you import, the item id is used; a slot with only a name is looked up by its exact in-game name.";

	private TemplateJson()
	{
	}

	/** The outcome of an import: a template, or a message to show the player. */
	static final class Result
	{
		final BankTemplate template;
		final String error;

		private Result(BankTemplate template, String error)
		{
			this.template = template;
			this.error = error;
		}

		static Result ok(BankTemplate t)
		{
			return new Result(t, null);
		}

		static Result fail(String message)
		{
			return new Result(null, message);
		}
	}

	/**
	 * Writes a template as JSON text. {@code nameOf} returns an item's name, or null when it isn't known,
	 * in which case the slot carries just the id.
	 */
	static String export(BankTemplate template, IntFunction<String> nameOf, Gson gson)
	{
		final String nl = "\n";
		final StringBuilder sb = new StringBuilder();
		sb.append('{').append(nl);
		sb.append("  \"format\": ").append(gson.toJson(FORMAT)).append(',').append(nl);
		sb.append("  \"notes\": ").append(gson.toJson(NOTES)).append(',').append(nl);
		sb.append("  \"name\": ").append(gson.toJson(nullToEmpty(template.getName()))).append(',').append(nl);
		sb.append("  \"description\": ").append(gson.toJson(nullToEmpty(template.getDescription()))).append(',').append(nl);
		sb.append("  \"columns\": ").append(template.getColumns() > 0 ? template.getColumns() : DEFAULT_COLUMNS).append(',').append(nl);
		sb.append("  \"tabs\": [");

		final List<TabLayout> tabs = new ArrayList<>(template.getTabs());
		tabs.sort((a, b) -> Integer.compare(a.getTab(), b.getTab()));
		for (int t = 0; t < tabs.size(); t++)
		{
			final TabLayout tab = tabs.get(t);
			sb.append(t == 0 ? nl : "," + nl);
			sb.append("    {").append(nl);
			sb.append("      \"tab\": ").append(tab.getTab()).append(',').append(nl);
			if (tab.getCustomIconId() > 0)
			{
				sb.append("      \"icon\": ").append(tab.getCustomIconId()).append(',').append(nl);
			}
			sb.append("      \"slots\": [");
			final List<Integer> layout = tab.getLayout();
			for (int i = 0; i < layout.size(); i++)
			{
				sb.append(i == 0 ? nl : "," + nl).append("        ").append(gson.toJson(slotText(layout.get(i), nameOf)));
			}
			sb.append(layout.isEmpty() ? "]" : nl + "      ]").append(nl);
			sb.append("    }");
		}
		sb.append(tabs.isEmpty() ? "]" : nl + "  ]").append(nl);
		sb.append('}').append(nl);
		return sb.toString();
	}

	private static String slotText(Integer id, IntFunction<String> nameOf)
	{
		if (id == null || id == BankTemplate.EMPTY)
		{
			return "empty";
		}
		if (id == BankTemplate.FILLER)
		{
			return "filler";
		}
		final String name = nameOf.apply(id);
		return name == null || name.isEmpty() ? Integer.toString(id) : id + ": " + name;
	}

	/**
	 * Reads JSON text into a new template. {@code idForName} returns the id of an item with exactly that
	 * in-game name, or null. The template it returns has no repository id and isn't marked owned: whatever
	 * the text says, an import always becomes a fresh template of your own.
	 */
	static Result parse(String text, Function<String, Integer> idForName, Gson gson)
	{
		if (text == null || text.trim().isEmpty())
		{
			return Result.fail("The clipboard is empty. Copy a template's JSON first.");
		}
		final JsonElement rootEl;
		try
		{
			rootEl = gson.fromJson(text, JsonElement.class);
		}
		catch (JsonParseException | IllegalStateException e)
		{
			return Result.fail("The clipboard doesn't contain valid JSON.");
		}
		if (rootEl == null || !rootEl.isJsonObject())
		{
			return Result.fail("The clipboard doesn't contain a template.");
		}
		final JsonObject root = rootEl.getAsJsonObject();

		final JsonArray tabsArr = array(root, "tabs");
		if (tabsArr == null || tabsArr.size() == 0)
		{
			return Result.fail("That template has no tabs.");
		}
		if (tabsArr.size() > MAX_TABS)
		{
			return Result.fail("That template has " + tabsArr.size() + " tabs. The most a bank can have is " + MAX_TABS + ".");
		}

		final BankTemplate template = new BankTemplate();
		String name = clean(string(root, "name"), MAX_NAME);
		template.setName(name.isEmpty() ? DEFAULT_NAME : name);
		final String description = clean(string(root, "description"), MAX_DESCRIPTION);
		template.setDescription(description.isEmpty() ? null : description);
		final Integer columns = integer(root, "columns");
		template.setColumns(columns != null && columns >= 1 && columns <= MAX_COLUMNS ? columns : DEFAULT_COLUMNS);

		final Set<Integer> seenTabs = new LinkedHashSet<>();
		final Set<String> unknown = new LinkedHashSet<>();
		int items = 0;
		for (JsonElement tabEl : tabsArr)
		{
			if (tabEl == null || !tabEl.isJsonObject())
			{
				return Result.fail("Each tab must be an object with a \"tab\" number and its \"slots\".");
			}
			final JsonObject tabObj = tabEl.getAsJsonObject();
			final Integer tab = integer(tabObj, "tab");
			if (tab == null || tab < 0 || tab > 9)
			{
				return Result.fail("A tab number must be from 0 (the main tab) to 9.");
			}
			if (!seenTabs.add(tab))
			{
				return Result.fail("Tab " + tab + " appears more than once.");
			}
			JsonArray slotsArr = array(tabObj, "slots");
			if (slotsArr == null)
			{
				slotsArr = array(tabObj, "layout");
			}
			if (slotsArr == null)
			{
				return Result.fail("Tab " + tab + " has no \"slots\" list.");
			}
			if (slotsArr.size() > MAX_SLOTS)
			{
				return Result.fail("Tab " + tab + " has " + slotsArr.size() + " slots. The most a tab can hold is " + MAX_SLOTS + ".");
			}

			final List<Integer> layout = new ArrayList<>(slotsArr.size());
			for (JsonElement slotEl : slotsArr)
			{
				final int id;
				try
				{
					id = slotId(slotEl, idForName);
				}
				catch (UnknownItem u)
				{
					unknown.add(u.name);
					layout.add(BankTemplate.EMPTY);
					continue;
				}
				catch (IllegalArgumentException bad)
				{
					return Result.fail("Tab " + tab + ": " + bad.getMessage());
				}
				if (id > 0)
				{
					items++;
				}
				layout.add(id);
			}
			template.putTab(tab, layout);

			final Integer icon = integer(tabObj, "icon") != null ? integer(tabObj, "icon") : integer(tabObj, "customIconId");
			if (icon != null && icon > 0 && icon <= MAX_ITEM_ID)
			{
				template.setTabIcon(tab, icon);
			}
		}

		if (!unknown.isEmpty())
		{
			final List<String> list = new ArrayList<>(unknown);
			final StringBuilder msg = new StringBuilder("No item has exactly this name: ");
			for (int i = 0; i < Math.min(list.size(), MAX_LISTED_UNKNOWN); i++)
			{
				msg.append(i == 0 ? "" : ", ").append('"').append(list.get(i)).append('"');
			}
			if (list.size() > MAX_LISTED_UNKNOWN)
			{
				msg.append(" and ").append(list.size() - MAX_LISTED_UNKNOWN).append(" more");
			}
			msg.append(". Add the item id to those slots, like \"4151: Abyssal whip\", and try again.");
			return Result.fail(msg.toString());
		}
		if (items == 0)
		{
			return Result.fail("That template has no items in it.");
		}
		return Result.ok(template);
	}

	/** Thrown when a slot names an item that no item matches exactly. */
	private static final class UnknownItem extends RuntimeException
	{
		final String name;

		UnknownItem(String name)
		{
			super(null, null, false, false);
			this.name = name;
		}
	}

	// One slot: a number, "empty", "filler", "<id>: <name>", "<name>", or {"id": .., "name": ..}.
	private static int slotId(JsonElement el, Function<String, Integer> idForName)
	{
		if (el == null || el.isJsonNull())
		{
			return BankTemplate.EMPTY;
		}
		if (el.isJsonObject())
		{
			final JsonObject o = el.getAsJsonObject();
			final Integer id = integer(o, "id");
			if (id != null)
			{
				return checkId(id);
			}
			final String name = string(o, "name");
			if (name != null && !name.trim().isEmpty())
			{
				return byName(name.trim(), idForName);
			}
			throw new IllegalArgumentException("a slot object needs an \"id\" or a \"name\".");
		}
		if (!el.isJsonPrimitive())
		{
			throw new IllegalArgumentException("a slot must be an item, \"empty\" or \"filler\".");
		}
		final JsonPrimitive p = el.getAsJsonPrimitive();
		if (p.isNumber())
		{
			return checkId(p.getAsInt());
		}
		final String s = p.getAsString().trim();
		final String lower = s.toLowerCase(Locale.ROOT);
		if (s.isEmpty() || lower.equals("empty"))
		{
			return BankTemplate.EMPTY;
		}
		if (lower.equals("filler"))
		{
			return BankTemplate.FILLER;
		}
		// "<id>: <name>", "<id>" - the id wins whenever it's there.
		int digits = 0;
		while (digits < s.length() && Character.isDigit(s.charAt(digits)))
		{
			digits++;
		}
		if (digits > 0 && (digits == s.length() || s.charAt(digits) == ':' || Character.isWhitespace(s.charAt(digits))))
		{
			try
			{
				return checkId(Integer.parseInt(s.substring(0, digits)));
			}
			catch (NumberFormatException e)
			{
				throw new IllegalArgumentException("\"" + s + "\" has an item id that is too large.");
			}
		}
		return byName(s, idForName);
	}

	private static int byName(String name, Function<String, Integer> idForName)
	{
		final Integer id = idForName.apply(name);
		if (id == null || id <= 0)
		{
			throw new UnknownItem(name);
		}
		return id;
	}

	private static int checkId(int id)
	{
		if (id == BankTemplate.EMPTY)
		{
			return id;
		}
		if (id <= 0 || id > MAX_ITEM_ID)
		{
			throw new IllegalArgumentException(id + " isn't a valid item id.");
		}
		return id;
	}

	private static JsonArray array(JsonObject o, String key)
	{
		final JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	private static String string(JsonObject o, String key)
	{
		final JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static Integer integer(JsonObject o, String key)
	{
		final JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
		{
			return null;
		}
		final double d = e.getAsDouble();
		return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : null;
	}

	// Strips control characters, trims, and caps the length, like the repository does with names.
	private static String clean(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(s.length());
		s.codePoints().filter(c -> c >= 32 && c != 127).forEach(out::appendCodePoint);
		final String t = out.toString().trim();
		return t.length() <= max ? t : t.substring(0, max).trim();
	}

	private static String nullToEmpty(String s)
	{
		return s == null ? "" : s;
	}
}
