package nl.alowaniak.runelite.musicreplacer;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Map;
import java.util.stream.Stream;

import lombok.Value;

/*
 * Ideally this class is refactored a bunch.
 * But such a refactor will likely break already existing overrides + not sure what the best design would be.
 *
 * Things that aren't too clean:
 * 	originalPath when downloaded
 *  fromLocal which could be deduced (or maybe have a hierarchy LocalOverride/RemoteOverride)
 *  coupling of name to path and not knowing its extension (which is requiring nasty workarounds)
 */
@Value
class TrackOverride
{
	String name;
	/**
	 * The originalPath for a local override or the {@link SearchResult#id} for downloads
	 */
	String originalPath;
	boolean fromLocal;
	Map<String, String> additionalInfo;

	public String fileSystemNormalizedName()
	{
		return Normalizer.normalize(name, Normalizer.Form.NFKD)
				.replaceAll("\\p{M}", "") // remove accents
				.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") // invalid chars (incl. tabs)
				.replaceAll("^[. ]+|[. ]+$", ""); // trim dots/spaces
	}

	/**
	 * Multiple possibilities because extension is unknown.
	 *
	 * @return the possible paths of the overridden tracks in most favouring first.
	 */
	public Stream<Path> getPaths()
	{
		return MusicPlayer.PLAYER_PER_EXT.keySet().stream().map(e -> Tracks.MUSIC_OVERRIDES_DIR.toPath().resolve(fileSystemNormalizedName() + e));
	}
}
