package nl.alowaniak.runelite.musicreplacer;

import java.nio.file.Path;
import java.util.Map;
import lombok.Value;

@Value
class TrackOverride
{
	String name;
	String originalPath;
	Map<String, String> additionalInfo;

	public Path getPath()
	{
		return Tracks.MUSIC_OVERRIDES_DIR.toPath().resolve(name + ".wav");
	}
}
