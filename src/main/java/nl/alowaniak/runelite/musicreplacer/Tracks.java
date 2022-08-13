package nl.alowaniak.runelite.musicreplacer;

import com.google.common.collect.ImmutableMap;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
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
	public static final String FULL_OVERRIDE_CONFIG_KEY_PREFIX = CONFIG_GROUP + '.' + OVERRIDE_CONFIG_KEY_PREFIX;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configMgr;
	@Inject
	private MusicReplacerConfig config;
	@Inject
	private MusicReplacerPlugin musicReplacer;
	@Inject
	@Named(MusicReplacerPlugin.MUSIC_REPLACER_EXECUTOR)
	private ExecutorService executor;

	@Getter(lazy=true)
	private final SortedSet<String> trackNames = new TreeSet<>(Arrays.asList(client.getEnum(EnumID.MUSIC_TRACK_NAMES).getStringVals()));

	/**
	 * @return whether or not given {@code name} exists as a {@link TrackOverride}
	 */
	public boolean overrideExists(String name)
	{
		return configMgr.getConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name) != null;
	}

	public List<String> overriddenTracks()
	{
		return configMgr.getConfigurationKeys(CONFIG_GROUP).stream()
			.filter(e -> e.startsWith(FULL_OVERRIDE_CONFIG_KEY_PREFIX))
			.map(e -> e.replace(FULL_OVERRIDE_CONFIG_KEY_PREFIX, ""))
			.collect(Collectors.toList());
	}

	/**
	 * Bulk creates overrides assuming {@code dir} contains audio files with exact same names as tracks
	 */
	public void bulkCreateOverride(Path dirPath)
	{
		@Value class PathAndFilename {
			Path path;
			@Getter(lazy = true) String filename = path.getFileName().toString().replaceAll("\\..+$", "");
		}
		clientThread.invoke(() -> { // Kinda ugly but we need to (initially) get the tracknames on the client thread
			SortedSet<String> osrsTrackNames = getTrackNames();
			executor.submit(() ->
			{
				musicReplacer.chatMsg("Overriding with tracks in " + dirPath + ".");
				try (Stream<Path> ls = Files.list(dirPath))
				{
					ls.map(e -> new PathAndFilename(e))
							.filter(e -> osrsTrackNames.contains(e.getFilename()))
							.forEach(e -> {
								if (!config.skipAlreadyOverriddenWhenBulkOverride() || !overrideExists(e.getFilename())) {
									createOverride(e.getFilename(), e.getPath());
								} else {
									musicReplacer.chatMsg("Skipping " + e.getFilename() + ", already overridden.");
								}
							});
				}
				catch (IOException e)
				{
					log.warn("Error opening `" + dirPath + "` for bulk override.", e);
				}
				musicReplacer.chatMsg("Done overriding.");
			});
		});
	}

    public void createOverride(String name, Path path)
	{
		createOverride(new TrackOverride(name, path.toString(), true, ImmutableMap.of()));
	}

	public void bulkCreateOverride(Preset preset) {
		musicReplacer.chatMsg(
				"Downloading " + preset.getTracks().size() + " tracks, won't dl all if RL closes prematurely.",
				preset.getCredits()
		);
		preset.getTracks().forEach((name, override) -> {
			if (!config.skipAlreadyOverriddenWhenBulkOverride() || !overrideExists(name)) {
				createOverride(name, override);
			} else {
				musicReplacer.chatMsg("Skipping " + name + ", already overridden.");
			}
		});
		executor.submit(() -> musicReplacer.chatMsg("Finished downloading preset " + preset.getName() + ".", preset.getCredits()));
	}

	public void createOverride(String trackName, SearchResult hit)
	{
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
		Path overridePath = transfer(override);
		if (overridePath != null)
		{
			// Ensure we only keep the current override transferred file
			override.getPaths()
					.filter(e -> !e.equals(overridePath))
					.forEach(path -> {
						try {
							Files.deleteIfExists(path);
						} catch (IOException e) {
							log.warn("Couldn't delete " + path, e);
						}
					});

			configMgr.setConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + override.getName(), GSON.toJson(override));
			musicReplacer.chatMsg(override.isFromLocal()
							? "Overridden " + override.getName()
							: "Overridden " + override.getName() + ", uploaded by " + override.getAdditionalInfo().get("Uploader")
			);
		} else {
			musicReplacer.chatMsg("Failed to override " + override.getName() + ", check the logs.");
		}
	}

	public TrackOverride getOverride(String name)
	{
		TrackOverride override = GSON.fromJson(
			configMgr.getConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name),
			TrackOverride.class
		);

		if (override == null || override.getPaths().anyMatch(Files::exists))
		{
			return override;
		}
		else
		{
			log.warn("Deleting: " + override + " because there was no override file for it.");
			configMgr.unsetConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name);
			return null;
		}
	}

	/**
	 * Clears all overridden tracks.
	 */
	public void removeAllOverrides()
	{
		executor.submit(() -> overriddenTracks().forEach(this::removeOverride));
	}

	public void removeOverride(String name) {
		TrackOverride override = getOverride(name);
		if (override == null) return;

		configMgr.unsetConfiguration(CONFIG_GROUP, OVERRIDE_CONFIG_KEY_PREFIX + name);
		override.getPaths().forEach(overridePath -> {
			try {
				Files.deleteIfExists(overridePath);
			} catch (IOException e) {
				log.warn("Couldn't delete " + name, e);
			}
		});
	}

	private Path transfer(TrackOverride override)
	{
		return override.isFromLocal()
			? transferLocal(override)
			: transferLink(override);
	}

	private Path transferLocal(TrackOverride override)
	{
		Path path = Paths.get(override.getOriginalPath());
		Path targetPath = override.getPaths()
				.filter(e -> extensionOf(path).equals(extensionOf(e)))
				.findFirst().orElse(null);

		if (targetPath == null)
		{
			log.warn("Can only load " + MusicPlayer.PLAYER_PER_EXT.keySet() + " files. " + override);
			return null;
		}

		try
		{
			Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
			return targetPath;
		} catch (IOException e)
		{
			log.warn("Something went wrong when copying " + override, e);
			return null;
		}
	}

	private String extensionOf(Path p) {
		String fileName = p.getFileName().toString();
		return fileName.substring(fileName.lastIndexOf('.'));
	}

	private Path transferLink(TrackOverride override)
	{
		Path targetPath = override.getPaths().findFirst().orElseThrow(IllegalStateException::new);

		String dlUrl = MUSIC_REPLACER_API + "download/" + override.getOriginalPath() + "?ext=" + extensionOf(targetPath);
		try (InputStream is = new URL(dlUrl).openStream())
		{
			Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
			return targetPath;
		}
		catch (IOException e)
		{
			log.warn("Something went wrong when downloading for " + override, e);
			return null;
		}
	}
}
