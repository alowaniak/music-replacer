package nl.alowaniak.runelite.musicreplacer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class MusicReplacerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MusicReplacerPlugin.class);
		RuneLite.main(args);
	}
}