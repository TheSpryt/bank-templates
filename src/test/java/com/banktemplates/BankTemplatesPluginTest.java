package com.banktemplates;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BankTemplatesPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BankTemplatesPlugin.class);
		RuneLite.main(args);
	}
}
