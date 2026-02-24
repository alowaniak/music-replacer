package nl.alowaniak.runelite.musicreplacer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.junit.Test;

public class MusicReplacerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MusicReplacerPlugin.class);
		RuneLite.main(args);
	}

	//@Test
	public void launchBecauseIntellijIsHavingTroubleWithLaunchingTheMain() throws Exception {
		ExternalPluginManager.loadBuiltin(MusicReplacerPlugin.class);
		RuneLite.main(new String[] {"-developer-mode"});
		while (true) Thread.yield();
	}
}