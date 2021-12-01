package nl.alowaniak.runelite.musicreplacer;

import com.google.common.collect.ImmutableMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.runelite.http.api.RuneLiteAPI.GSON;
import static nl.alowaniak.runelite.musicreplacer.MusicReplacerConfig.CONFIG_GROUP;
import static nl.alowaniak.runelite.musicreplacer.MusicReplacerPlugin.MUSIC_REPLACER_API;

/**
 * Provides access to all OSRS track names as well as all {@link TrackOverride overridden tracks}.
 */
@Slf4j
@Singleton
class Tracks
{
	static final File MUSIC_OVERRIDES_DIR = new File(RuneLite.RUNELITE_DIR, "music-replacer");
	{ // Not static initializer, if we fail we only want to fail loading our plugin
		if (!MUSIC_OVERRIDES_DIR.exists() && !MUSIC_OVERRIDES_DIR.mkdirs())
		{
			throw new IllegalStateException("Failed to create " + MUSIC_OVERRIDES_DIR);
		}
	}

	public static final String OVERRIDE_CONFIG_KEY_PREFIX = "track_";

	@Inject
	private Client client;

	@Inject
	private ConfigManager config;
	@Inject
	@Named(MusicReplacerPlugin.MUSIC_REPLACER_EXECUTOR)
	private ExecutorService executor;

	@Getter(lazy=true)
	private final SortedSet<String> trackNames = new TreeSet<>(Arrays.asList(client.getEnum(EnumID.MUSIC_TRACK_NAMES).getStringVals()));

	/**
	 * @return whether or not given {@code name} exists as a track.
	 */
	public boolean exists(String name)
	{
		return getTrackNames().contains(name);
	}

	/**
	 * @return whether or not given {@code name} exists as a {@link TrackOverride}
	 */
	public boolean overrideExists(String name)
	{
		return config.getConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name) != null;
	}

	public List<String> overriddenTracks()
	{
		return config.getConfigurationKeys(CONFIG_GROUP).stream()
			.filter(e -> e.startsWith(OVERRIDE_CONFIG_KEY_PREFIX))
			.map(e -> e.replace(OVERRIDE_CONFIG_KEY_PREFIX, ""))
			.collect(Collectors.toList());
	}

	/**
	 * Bulk creates overrides assuming {@code dir} contains audio files with exact same names as tracks
	 */
	public void bulkCreateOverride(String dir)
	{
		executor.submit(() ->
		{
			Path dirPath = Paths.get(dir);
			try (Stream<Path> ls = Files.list(dirPath))
			{
				ls.filter(e -> getTrackNames().contains(fileName(e)))
					.forEach(e -> createOverride(fileName(e), e));
			}
			catch (IOException e)
			{
				log.warn("Error opening `" + dirPath + "` for bulk override.", e);
			}
		});
	}

	private static String fileName(Path path)
	{
		return path.getFileName().toString().replaceAll("\\..+$", "");
	}

	public void createOverride(String name, Path path)
	{
		createOverride(new TrackOverride(name, path.toString(), true, ImmutableMap.of()));
	}

	public void createOverride(String trackName, SearchResult hit)
	{
		// TODO kind of misusing originalPath in TrackOverride, should refactor so it's also prettier for track info overlay
		executor.submit(() -> createOverride(
			new TrackOverride(trackName, hit.id, false,
				ImmutableMap.of(
				"Name", hit.getName(),
				"Duration", Duration.ofSeconds(hit.getDuration()).toString(),
				"Uploader", hit.getUploader()
				)
			)
		));
	}

	private void createOverride(TrackOverride override)
	{
		if (transfer(override))
		{
			config.setConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + override.getName(), GSON.toJson(override));
		}
	}

	public TrackOverride getOverride(String name)
	{
		TrackOverride override = GSON.fromJson(
			config.getConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name),
			TrackOverride.class
		);

		if (override == null || Files.exists(override.getPath()))
		{
			return override;
		}
		else
		{
			log.warn("Deleting: " + override + " because there was no override file for it.");
			config.unsetConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name);
			return null;
		}
	}

	/**
	 * Clears all overridden tracks.
	 */
	public void removeAllOverrides()
	{
		overriddenTracks().forEach(this::removeOverride);
	}

	public void removeOverride(String name)
	{
		TrackOverride override = getOverride(name);
		if (override == null) return;

		executor.submit(() ->
		{
			config.unsetConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name);
			try
			{
				Files.deleteIfExists(override.getPath());
			}
			catch (IOException e)
			{
				log.warn("Couldn't delete " + name, e);
			}
		});
	}

	private boolean transfer(TrackOverride override)
	{
		return override.isFromLocal()
			? transferLocal(override)
			: transferLink(override);
	}

	private boolean transferLocal(TrackOverride override)
	{
		Path path = Paths.get(override.getOriginalPath());
		if (!path.toString().endsWith(".wav"))
		{
			log.warn("Can only load .wav files. " + override);
			return false;
		}

		try
		{
			Files.copy(path, override.getPath(), StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e)
		{
			log.warn("Something went wrong when copying " + override, e);
			return false;
		}
	}

	private boolean transferLink(TrackOverride override)
	{
		String dlUrl = MUSIC_REPLACER_API + "download/" + override.getOriginalPath();
		try (InputStream is = new URL(dlUrl).openStream())
		{
			Files.copy(is, override.getPath(), StandardCopyOption.REPLACE_EXISTING);
			return true;
		}
		catch (IOException e)
		{
			log.warn("Something went wrong when downloading wav for " + override, e);
			return false;
		}
	}
}
