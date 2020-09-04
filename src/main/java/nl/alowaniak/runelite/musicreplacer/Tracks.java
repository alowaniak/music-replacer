package nl.alowaniak.runelite.musicreplacer;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.OSType;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import static nl.alowaniak.runelite.musicreplacer.MusicReplacerPlugin.CONFIG_GROUP;

/**
 * Provides access to all OSRS track names as well as all {@link TrackOverride overridden tracks}.
 */
@Slf4j
@Singleton
class Tracks
{
	static final File MUSIC_OVERRIDES_DIR = new File(RuneLite.RUNELITE_DIR, "music-overrides");
	static
	{
		if (!MUSIC_OVERRIDES_DIR.exists() && !MUSIC_OVERRIDES_DIR.mkdirs())
		{
			log.warn("Failed to create " + MUSIC_OVERRIDES_DIR);
		}
	}

	public static final String OVERRIDE_CONFIG_KEY_PREFIX = "track_";

	@Inject
	private Client client;

	@Inject
	private ConfigManager config;
	@Inject
	@Named("musicReplacerExecutor")
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
		try
		{
			createOverride(new TrackOverride(name, path.toString(), ImmutableMap.of()));
		}
		catch (Exception e)
		{
			log.warn("Something went wrong creating override for " + name + " based on " + path, e);
		}
	}

	public void createOverride(String name, StreamInfoItem streamItem)
	{
		executor.submit(() ->
		{
			try
			{
				StreamExtractor stream = ServiceList.YouTube.getStreamExtractor(streamItem.getUrl());
				stream.fetchPage();

				TrackOverride override = stream.getAudioStreams().stream()
					.filter(e -> e.getFormat() == MediaFormat.M4A)
					.map(e -> new TrackOverride(name, e.getUrl(), ImmutableMap.of(
						"Url", streamItem.getUrl(),
						"Name", streamItem.getName(),
						"Duration", Duration.ofSeconds(streamItem.getDuration()).toString(),
						"Uploader", streamItem.getUploaderName(),
						"Uploader url", streamItem.getUploaderUrl()
					)))
					.findAny().orElse(null);

				if (override != null) createOverride(override);
			}
			catch (Exception e)
			{
				log.warn("Something went wrong creating override for " + name + " based on " + streamItem, e);
			}
		});
	}

	private void createOverride(TrackOverride override) throws IOException, InterruptedException
	{
		if (ToWavTransfer.transfer(override))
		{
			config.setConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + override.getName(), new Gson().toJson(override));
		}
	}

	public TrackOverride getOverride(String name)
	{
		TrackOverride override = new Gson().fromJson(
			config.getConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name),
			TrackOverride.class
		);

		if (override == null || override.getFile().exists())
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
				Files.deleteIfExists(override.getFile().toPath());
			}
			catch (IOException e)
			{
				log.warn("Couldn't delete " + name, e);
			}
		});
	}

	/**
	 * Uses ffmpeg to convert to wav audio format
	 */
	private static class ToWavTransfer
	{
		private static final File FFMPEG;
		static
		{
			OSType osType = OSType.getOSType();
			FFMPEG = new File(MUSIC_OVERRIDES_DIR, osType == OSType.Windows ? "ffmpeg.exe" : "ffmpeg");
			if (!FFMPEG.exists())
			{
				String type = getFfmpegType();
				String ffmpegResourceLoc = "/ffmpeg-4.2.2-" + type + "-static/" + FFMPEG.getName();

				try (InputStream in = ToWavTransfer.class.getResourceAsStream(ffmpegResourceLoc))
				{
					Files.copy(in, FFMPEG.toPath());
				} catch (IOException e)
				{
					e.printStackTrace();
				}
			}
		}

		private static String getFfmpegType()
		{
			switch (OSType.getOSType())
			{
				case Windows:
					return "win64"; // TODO win32 (do people still use it?)
				case MacOS:
					return "macos64";
				case Linux:
				case Other:
				default:
					return "amd64"; // TODO figure out cpu type?
			}
		}

		/**
		 * Transfers {@code override}'s {@link TrackOverride#getOriginalPath() original path} to {@link #MUSIC_OVERRIDES_DIR}
		 * with a wav format using ffmpeg.
		 *
		 * @return {@code true} when
		 */
		public static boolean transfer(TrackOverride override) throws IOException, InterruptedException
		{
			return new ProcessBuilder()
				.directory(MUSIC_OVERRIDES_DIR)
				.command(FFMPEG.getAbsolutePath(), "-y", "-i", override.getOriginalPath(), override.getFile().getName())
				.inheritIO()
				.start()
				.waitFor() == 0;
		}

		private ToWavTransfer() { /* Utility class */ }
	}
}
