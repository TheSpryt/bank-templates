package com.banktemplates;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TemplateJsonTest
{
	private static final Gson GSON = new Gson();
	private static final int WHIP = 4151;
	private static final int SHARK = 385;
	private static final int ROPE = 954;

	private static final Map<String, Integer> NAMES = new HashMap<>();
	static
	{
		NAMES.put("abyssal whip", WHIP);
		NAMES.put("shark", SHARK);
		NAMES.put("rope", ROPE);
	}

	private static Integer byName(String name)
	{
		return NAMES.get(name.toLowerCase());
	}

	private static String nameOf(int id)
	{
		switch (id)
		{
			case WHIP:
				return "Abyssal whip";
			case SHARK:
				return "Shark";
			default:
				return null;
		}
	}

	private static List<Integer> tab(BankTemplate t, int tab)
	{
		for (TabLayout tl : t.getTabs())
		{
			if (tl.getTab() == tab)
			{
				return tl.getLayout();
			}
		}
		return null;
	}

	@Test
	public void exportThenImportKeepsTheLayout()
	{
		final BankTemplate original = new BankTemplate();
		original.setName("Pvm");
		original.setDescription("Main bank");
		original.setColumns(8);
		original.putTab(0, Arrays.asList(WHIP, BankTemplate.EMPTY, BankTemplate.FILLER));
		original.putTab(2, Arrays.asList(SHARK, ROPE));
		original.setTabIcon(2, SHARK);

		final String json = TemplateJson.export(original, TemplateJsonTest::nameOf, GSON);
		assertTrue(json.contains("\"4151: Abyssal whip\""));
		assertTrue(json.contains("\"empty\""));
		assertTrue(json.contains("\"filler\""));
		// An item with no known name is written as its id alone.
		assertTrue(json.contains("\"954\""));

		final TemplateJson.Result r = TemplateJson.parse(json, TemplateJsonTest::byName, GSON);
		assertNull(r.error);
		assertEquals("Pvm", r.template.getName());
		assertEquals("Main bank", r.template.getDescription());
		assertEquals(8, r.template.getColumns());
		assertEquals(Arrays.asList(WHIP, BankTemplate.EMPTY, BankTemplate.FILLER), tab(r.template, 0));
		assertEquals(Arrays.asList(SHARK, ROPE), tab(r.template, 2));
		assertEquals(SHARK, r.template.getTabIcon(2));
	}

	@Test
	public void importsTheSavedFileFormatAndDropsSharingFields()
	{
		final String saved = "{\"name\":\"DC Bank\",\"columns\":8,\"repoId\":6,\"owned\":true,"
			+ "\"ownerProfile\":{\"name\":\"someone\"},\"shareDownloads\":12,"
			+ "\"tabs\":[{\"tab\":1,\"layout\":[4151,-1,385],\"customIconId\":385}]}";
		final TemplateJson.Result r = TemplateJson.parse(saved, TemplateJsonTest::byName, GSON);
		assertNull(r.error);
		assertEquals(Arrays.asList(WHIP, BankTemplate.EMPTY, SHARK), tab(r.template, 1));
		assertEquals(SHARK, r.template.getTabIcon(1));
		assertNull(r.template.getRepoId());
		assertFalse(r.template.isOwned());
		assertNull(r.template.getOwnerProfile());
	}

	@Test
	public void theIdWinsOverAWrongName()
	{
		final String json = "{\"name\":\"x\",\"tabs\":[{\"tab\":0,\"slots\":[\"4151: Not a real name\"]}]}";
		final TemplateJson.Result r = TemplateJson.parse(json, TemplateJsonTest::byName, GSON);
		assertNull(r.error);
		assertEquals(Arrays.asList(WHIP), tab(r.template, 0));
	}

	@Test
	public void aSlotWithOnlyANameIsLookedUp()
	{
		final String json = "{\"name\":\"x\",\"tabs\":[{\"tab\":0,\"slots\":[\"Shark\",{\"name\":\"Rope\"},{\"id\":4151}]}]}";
		final TemplateJson.Result r = TemplateJson.parse(json, TemplateJsonTest::byName, GSON);
		assertNull(r.error);
		assertEquals(Arrays.asList(SHARK, ROPE, WHIP), tab(r.template, 0));
	}

	@Test
	public void unknownNamesAreListedInTheError()
	{
		final String json = "{\"name\":\"x\",\"tabs\":[{\"tab\":0,\"slots\":[\"Shark\",\"Dragon nonsense\",\"Bronze whatever\"]}]}";
		final TemplateJson.Result r = TemplateJson.parse(json, TemplateJsonTest::byName, GSON);
		assertNull(r.template);
		assertTrue(r.error.contains("\"Dragon nonsense\""));
		assertTrue(r.error.contains("\"Bronze whatever\""));
	}

	@Test
	public void rejectsWhatTheRepositoryWouldReject()
	{
		assertNotNull(TemplateJson.parse("not json", TemplateJsonTest::byName, GSON).error);
		assertNotNull(TemplateJson.parse("[1,2,3]", TemplateJsonTest::byName, GSON).error);
		assertNotNull(TemplateJson.parse("", TemplateJsonTest::byName, GSON).error);
		// tab number out of range
		assertNotNull(TemplateJson.parse("{\"tabs\":[{\"tab\":10,\"slots\":[4151]}]}", TemplateJsonTest::byName, GSON).error);
		// the same tab twice
		assertNotNull(TemplateJson.parse("{\"tabs\":[{\"tab\":1,\"slots\":[4151]},{\"tab\":1,\"slots\":[385]}]}", TemplateJsonTest::byName, GSON).error);
		// invalid item id
		assertNotNull(TemplateJson.parse("{\"tabs\":[{\"tab\":0,\"slots\":[0]}]}", TemplateJsonTest::byName, GSON).error);
		// no items at all
		assertNotNull(TemplateJson.parse("{\"tabs\":[{\"tab\":0,\"slots\":[\"empty\",\"empty\"]}]}", TemplateJsonTest::byName, GSON).error);
		// too many tabs
		final StringBuilder many = new StringBuilder("{\"tabs\":[");
		for (int i = 0; i < 11; i++)
		{
			many.append(i == 0 ? "" : ",").append("{\"tab\":").append(i % 10).append(",\"slots\":[4151]}");
		}
		many.append("]}");
		assertNotNull(TemplateJson.parse(many.toString(), TemplateJsonTest::byName, GSON).error);
	}

	@Test
	public void longNamesAreCappedAndMissingOnesGetADefault()
	{
		final String longName = "{\"name\":\"An extremely long template name for testing\",\"tabs\":[{\"tab\":0,\"slots\":[4151]}]}";
		assertEquals(TemplateJson.MAX_NAME, TemplateJson.parse(longName, TemplateJsonTest::byName, GSON).template.getName().length());
		final String noName = "{\"tabs\":[{\"tab\":0,\"slots\":[4151]}]}";
		assertEquals("Imported template", TemplateJson.parse(noName, TemplateJsonTest::byName, GSON).template.getName());
	}
}
