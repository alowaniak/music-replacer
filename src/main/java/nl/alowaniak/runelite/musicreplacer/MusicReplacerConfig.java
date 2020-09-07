package nl.alowaniak.runelite.musicreplacer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

import static nl.alowaniak.runelite.musicreplacer.MusicReplacerConfig.CONFIG_GROUP;

@ConfigGroup(CONFIG_GROUP)
public interface MusicReplacerConfig extends Config
{
	String CONFIG_GROUP = "musicreplacer";
}
