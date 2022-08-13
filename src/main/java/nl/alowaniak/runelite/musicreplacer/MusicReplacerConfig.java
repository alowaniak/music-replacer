package nl.alowaniak.runelite.musicreplacer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

import static nl.alowaniak.runelite.musicreplacer.MusicReplacerConfig.CONFIG_GROUP;

@ConfigGroup(CONFIG_GROUP)
public interface MusicReplacerConfig extends Config
{
	String CONFIG_GROUP = "musicreplacer";
	@ConfigItem(
			keyName = "skipAlreadyOverriddenWhenBulkOverride",
			name = "Skip overridden if bulk",
			description = "When on this will skip any already overridden tracks when doing a bulk override.<br>" +
					"If off the bulk override will replace already overridden tracks (if said track is in the bulk)."
	)
	default boolean skipAlreadyOverriddenWhenBulkOverride()
	{
		return true;
	}
}
