package nl.alowaniak.runelite.musicreplacer;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import jogamp.opengl.util.pngj.chunks.ChunksListForWrite;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumID;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import static nl.alowaniak.runelite.musicreplacer.MusicReplacerConfig.CONFIG_GROUP;

/**
 * Provides access to all OSRS track names as well as all {@link TrackOverride overridden tracks}.
 */
@Slf4j
@Singleton
class Tracks
{
	private static final String WAV_API_URL = "https://4rri42wrbl.execute-api.us-east-1.amazonaws.com/default/convert-to-wav";

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
	private OkHttpClient http;

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

				stream.getAudioStreams().stream()
					.filter(e -> e.getFormat() == MediaFormat.M4A)
					.map(e -> new TrackOverride(name, e.getUrl(), ImmutableMap.of(
						"Url", streamItem.getUrl(),
						"Name", streamItem.getName(),
						"Duration", Duration.ofSeconds(streamItem.getDuration()).toString(),
						"Uploader", streamItem.getUploaderName(),
						"Uploader url", streamItem.getUploaderUrl()
					)))
					.findAny().ifPresent(this::createOverride);

			}
			catch (Exception e)
			{
				log.warn("Something went wrong creating override for " + name + " based on " + streamItem, e);
			}
		});
	}

	private void createOverride(TrackOverride override)
	{
		if (transfer(override))
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
		try {
			Path path = Paths.get(override.getOriginalPath());
			if (Files.exists(path))
			{
				return transferLocal(override, path);
			}
		} catch (InvalidPathException e)
		{
			log.debug(override + " didn't have a valid Path, perhaps it's a URL?", e);
		}
		return transferLink(override);
	}

	private boolean transferLocal(TrackOverride override, Path path)
	{
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
			log.warn("Something went wrong when copying " + override);
			return false;
		}
	}

	private boolean transferLink(TrackOverride override)
	{
		try
		{
			Response response = http.newBuilder().readTimeout(1, TimeUnit.MINUTES).build()
				.newCall(new Request.Builder()
				.url("https://4rri42wrbl.execute-api.us-east-1.amazonaws.com/default/convert-to-wav?url=" + URLEncoder.encode(override.getOriginalPath(), StandardCharsets.UTF_8.toString()))
				.build()
			).execute();
			if (response.isSuccessful())
			{
				String wavUrl = response.body().string();
				try (InputStream is = new URL(wavUrl).openStream())
				{
					Files.copy(is, override.getPath(), StandardCopyOption.REPLACE_EXISTING);
					return true;
				}
			}
		} catch (IOException e)
		{
			log.warn("Something went wrong when downloading wav for " + override, e);
		}
		return false;
	}
}
