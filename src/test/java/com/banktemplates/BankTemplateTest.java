package com.banktemplates;

import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BankTemplateTest
{
	private static final int WHIP = 4151;
	private static final int SHARK = 385;
	private static final int ROPE = 954;
	private static final int HAMMER = 2347;

	@Test
	public void replacingTheLayoutKeepsNameDescriptionAndSharing()
	{
		final BankTemplate t = new BankTemplate();
		t.setName("Max Main");
		t.setDescription("Everything");
		t.setRepoId(42L);
		t.setOwned(true);
		t.setColumns(8);
		t.putTab(1, Arrays.asList(WHIP, SHARK));
		t.putTab(2, Arrays.asList(ROPE));

		final BankTemplate bank = new BankTemplate();
		bank.setName("ignored");
		bank.setDescription("ignored");
		bank.setColumns(10);
		bank.putTab(1, Arrays.asList(HAMMER));

		t.replaceLayoutFrom(bank);

		assertEquals("Max Main", t.getName());
		assertEquals("Everything", t.getDescription());
		assertEquals(Long.valueOf(42L), t.getRepoId());
		assertTrue(t.isOwned());
		assertEquals(10, t.getColumns());
		assertEquals(Arrays.asList(1), t.definedTabs());
		assertEquals(1, t.itemCount());
	}

	@Test
	public void aCustomTabIconSurvivesOnlyIfTheTabStillHoldsIt()
	{
		final BankTemplate t = new BankTemplate();
		t.setName("x");
		t.putTab(1, Arrays.asList(WHIP, SHARK));
		t.putTab(2, Arrays.asList(ROPE, HAMMER));
		t.setTabIcon(1, SHARK);
		t.setTabIcon(2, HAMMER);

		final BankTemplate bank = new BankTemplate();
		bank.setName("y");
		bank.putTab(1, Arrays.asList(SHARK, WHIP, ROPE));
		bank.putTab(2, Arrays.asList(ROPE));
		t.replaceLayoutFrom(bank);

		assertEquals(SHARK, t.getTabIcon(1));
		// The hammer left tab 2, so its icon goes back to the tab's first item.
		assertEquals(0, t.getTabIcon(2));

		// An icon the new layout sets itself wins.
		final BankTemplate json = new BankTemplate();
		json.setName("z");
		json.putTab(1, Arrays.asList(SHARK, WHIP));
		json.setTabIcon(1, WHIP);
		t.replaceLayoutFrom(json);
		assertEquals(WHIP, t.getTabIcon(1));
	}
}
