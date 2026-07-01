package com.banktemplates;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.eventbus.Subscribe;

/**
 * A dedicated high-priority event subscriber that pre-renders our out-of-range virtual tabs before RuneLite's
 * core Bank plugin (default priority 0) sums the visible widgets for the title value - so a virtual tab shows
 * its value in the title like a real tab does.
 *
 * <p>This lives outside {@link BankTemplatesPlugin} because RuneLite's EventBus requires a subscriber method
 * to be named {@code onScriptPreFired}, and the plugin already has one (at {@code -1f}, which must run after
 * Bank Tags sets its "Tag tab" title). A single class can't hold two methods with that name, so the earlier
 * pass gets its own class. It's registered/unregistered by the plugin's startUp/shutDown.
 */
@Singleton
class BankValuePreRenderer
{
	private final BankLayoutRenderer renderer;

	@Inject
	BankValuePreRenderer(BankLayoutRenderer renderer)
	{
		this.renderer = renderer;
	}

	@Subscribe(priority = 1f)
	public void onScriptPreFired(ScriptPreFired event)
	{
		renderer.preRenderVirtualTab(event);
	}
}
