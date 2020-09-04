package nl.alowaniak.runelite.musicreplacer;

import java.io.File;
import java.util.Map;
import lombok.Value;

@Value
class TrackOverride
{
	String name;
	String originalPath;
	Map<String, String> additionalInfo;

	public File getFile()
	{
		return new File(Tracks.MUSIC_OVERRIDES_DIR, name + ".wav");
	}
}
