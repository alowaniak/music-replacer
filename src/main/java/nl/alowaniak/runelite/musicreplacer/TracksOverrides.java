package nl.alowaniak.runelite.musicreplacer;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import javafx.scene.media.Media;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumID;
import net.runelite.api.FontID;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientInt;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.ArrayUtils;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import static nl.alowaniak.runelite.musicreplacer.MusicReplacerPlugin.CURRENTLY_PLAYING_WIDGET_ID;

@Slf4j
@Singleton
class TracksOverrides
{
	static final File MUSIC_OVERRIDES_DIR = new File(RuneLite.RUNELITE_DIR, "music-overrides");
	static
	{
		if (!MUSIC_OVERRIDES_DIR.exists()) MUSIC_OVERRIDES_DIR.mkdirs();
	}

	public static final String CONFIG_GROUP = "musicreplacer";
	public static final String CONFIG_KEY_PREFIX = "track_";

	private static final String OVERRIDE_OPTION = "Override";
	private static final String REMOVE_OVERRIDE_OPTION = "Remove override";

	private static final String OVERRIDE_ALL_OPTION = "Override tracks";
	private static final String REMOVE_ALL_OVERRIDES_OPTION = "Remove overrides";
	public static final int OVERRIDE_FONT = FontID.BOLD_12;
	public static final int NORMAL_FONT = FontID.PLAIN_12;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ConfigManager configManager;
	@Inject
	private TooltipManager tooltipManager;
	@Inject
	private ChatboxPanelManager chatboxPanelManager;
	@Inject
	@Named("musicReplacerExecutor")
	private ExecutorService executor;
	@Inject
	private YouTubeSearcher ytSearcher;

	private SortedSet<String> trackNames;

	public void startup()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(() -> trackNames = new TreeSet<>(Arrays.asList(client.getEnum(EnumID.MUSIC_TRACK_NAMES).getStringVals())));
		}
		overrideWidgetsOutdated = true;
	}

	private String lastPlayingTrack;
	private boolean overrideWidgetsOutdated;
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		String playingTrack = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID).getText();
		if (!playingTrack.equals(lastPlayingTrack))
		{
			lastPlayingTrack = playingTrack;
			updateCurrentlyPlayingWidget();
		}

		if (overrideWidgetsOutdated)
		{
			updateOverridesInTrackList();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(() -> trackNames = new TreeSet<>(Arrays.asList(client.getEnum(EnumID.MUSIC_TRACK_NAMES).getStringVals())));
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		String key = configChanged.getKey();
		if (CONFIG_GROUP.equals(configChanged.getGroup()) && key.startsWith(CONFIG_KEY_PREFIX))
		{
			overrideWidgetsOutdated = true;
			if (key.contains(lastPlayingTrack))
			{
				updateCurrentlyPlayingWidget();
			}
		}
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged varClientIntChanged)
	{
		if (varClientIntChanged.getIndex() == VarClientInt.INVENTORY_TAB.getIndex() && isOnMusicTab())
		{
			// The widgets could be outdated, just ensure it's updated whenever we go to the music tab
			// TODO figure out better way of ensuring the tracklist is up to date
			overrideWidgetsOutdated = true;
		}
	}

	private boolean isOnMusicTab()
	{
		return client.getVar(VarClientInt.INVENTORY_TAB) == 13;
	}

	@Subscribe
	public void onMenuOpened(final MenuOpened event)
	{
		final MenuEntry entry = event.getFirstEntry();
		if (entry == null) return;

		int widgetId = entry.getParam1();
		if (widgetId == WidgetInfo.MUSIC_TRACK_LIST.getId())
		{
			addMenuEntry(OVERRIDE_OPTION, entry);

			String trackName = Text.removeTags(entry.getTarget());
			if (getOverrideFor(trackName) != null)
			{
				addMenuEntry(REMOVE_OVERRIDE_OPTION, entry);
			}
		}
		else if (widgetId == WidgetInfo.FIXED_VIEWPORT_MUSIC_TAB.getId()
				|| widgetId == WidgetInfo.RESIZABLE_VIEWPORT_MUSIC_TAB.getId())
		{
			addMenuEntry(OVERRIDE_ALL_OPTION, entry);

			if (trackNames.stream().anyMatch(e -> getOverrideFor(e) != null))
			{
				addMenuEntry(REMOVE_ALL_OVERRIDES_OPTION, entry);
			}
		}
	}

	private void addMenuEntry(String option, MenuEntry entryForCopy)
	{
		MenuEntry overrideEntry = new MenuEntry();
		overrideEntry.setOption(option);
		overrideEntry.setTarget(entryForCopy.getTarget());
		overrideEntry.setType(MenuAction.RUNELITE.getId());
		overrideEntry.setParam0(entryForCopy.getParam0());
		overrideEntry.setParam1(entryForCopy.getParam1());
		overrideEntry.setIdentifier(entryForCopy.getIdentifier());
		client.setMenuEntries(ArrayUtils.insert(1, client.getMenuEntries(), overrideEntry));
	}

	@Subscribe
	public void onMenuOptionClicked(final MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.RUNELITE) return;

		String target = Text.removeTags(event.getMenuTarget());
		String menuOption = event.getMenuOption();
		if (trackNames.contains(target))
		{
			if (OVERRIDE_OPTION.equals(menuOption))
			{
				chatboxPanelManager.openTextMenuInput("How would you like to override " + target + "?")
						.option("With a local file.", () -> overrideByLocal(target))
						.option("From a youtube search.", () -> overrideBySearch(target))
						.build();
			}
			else if (REMOVE_OVERRIDE_OPTION.equals(menuOption))
			{
				removeOverrideFor(target);
			}
		}
		else if (OVERRIDE_ALL_OPTION.equals(menuOption))
		{
			chatboxPanelManager.openTextInput("Enter directory with override songs")
					.onDone(this::loadBulkOverrides)
					.build();
		}
		else if (REMOVE_ALL_OVERRIDES_OPTION.equals(menuOption))
		{
			trackNames.forEach(this::removeOverrideFor);
		}
	}

	private void overrideByLocal(String trackName)
	{
		chatboxPanelManager.openTextInput("Enter path to override " + trackName)
				.onDone((String s) -> overrideTrack(trackName, Paths.get(s)))
				.build();
	}

	private void overrideBySearch(String trackName)
	{
		chatboxPanelManager.openTextInput("Enter your search criteria for " + trackName)
				.value(trackName)
				.onDone((String s) -> showSearchHits(trackName, s))
				.build();
	}

	private void showSearchHits(String trackName, String searchTerm)
	{
		ytSearcher.search(searchTerm, (hits, continueSearch) ->
		{
			ChatboxTextMenuInput chooser = chatboxPanelManager.openTextMenuInput("Choose which you want to use as override for " + trackName);

			hits.forEach(hit -> chooser.option(hit.getName() + " by " + hit.getUploaderName(), () -> overrideTrack(trackName, hit)));
			if (!hits.isEmpty()) chooser.option("Continue...", continueSearch);

			chooser.build();
		});
	}

	private void loadBulkOverrides(String dir)
	{
		executor.submit(() ->
		{
			Path dirPath = Paths.get(dir);
			try (Stream<Path> ls = Files.list(dirPath))
			{
				ls.forEach(filePath ->
				{
					String trackName = filePath.getFileName().toString().replaceAll("\\..+$", "");
					overrideTrack(trackName, filePath);
				});
			}
			catch (IOException e)
			{
				log.warn("Error opening `" + dirPath + "` for directory listing.", e);
			}
		});
	}

	private void updateOverridesInTrackList()
	{
		clientThread.invoke(() ->
		{
			Widget trackList = client.getWidget(WidgetInfo.MUSIC_TRACK_LIST);
			if (trackList == null) return;
			for (Widget e : trackList.getDynamicChildren())
			{
				e.setFontId(getOverrideFor(e.getText()) != null ? OVERRIDE_FONT : NORMAL_FONT);
				e.revalidate();
			}
		});
	}

	private void updateCurrentlyPlayingWidget()
	{
		clientThread.invoke(() ->
		{
			Widget trackPlayingWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
			if (trackPlayingWidget == null) return;

			trackPlayingWidget.setNoClickThrough(true);
			String trackName = trackPlayingWidget.getText();
			trackPlayingWidget.setOnClickListener((JavaScriptCallback) e ->
			{
				Widget trackList = client.getWidget(WidgetInfo.MUSIC_TRACK_LIST);
				Stream.of(trackList.getDynamicChildren())
						.filter(w -> trackName.equals(w.getText()))
						.findAny()
						.ifPresent(track ->
								scrollToWidget(WidgetInfo.MUSIC_TRACK_LIST, WidgetInfo.MUSIC_TRACK_SCROLLBAR, track)
						);
			});
			trackPlayingWidget.setHasListener(true);

			TrackOverride override = getOverrideFor(trackPlayingWidget.getText());
			if (override != null)
			{
				trackPlayingWidget.setFontId(OVERRIDE_FONT);
				StringBuilder tooltipTxt = new StringBuilder("From: " + override.getOriginalPath() + "</br>");
				override.getAdditionalInfo().entrySet().stream()
						.sorted(Map.Entry.comparingByKey(Comparator.comparing(String::length).thenComparing(String::compareTo)))
						.forEach(e -> tooltipTxt.append(e.getKey()).append(": ").append(e.getValue()).append("</br>"));
				trackPlayingWidget.setOnMouseRepeatListener((JavaScriptCallback) e -> tooltipManager.add(new Tooltip(tooltipTxt.toString())));
			}
			else
			{
				trackPlayingWidget.setOnMouseRepeatListener((Object[]) null);
				trackPlayingWidget.setFontId(NORMAL_FONT);
			}
		});
	}

	void scrollToWidget(WidgetInfo list, WidgetInfo scrollbar, Widget... toHighlight)
	{
		final Widget parent = client.getWidget(list);
		int averageCentralY = 0;
		int nonnullCount = 0;
		for (Widget widget : toHighlight)
		{
			if (widget != null)
			{
				averageCentralY += widget.getRelativeY() + widget.getHeight() / 2;
				nonnullCount += 1;
			}
		}
		if (nonnullCount == 0)
		{
			return;
		}
		averageCentralY /= nonnullCount;
		final int newScroll = Math.max(0, Math.min(parent.getScrollHeight(),
				averageCentralY - parent.getHeight() / 2));

		client.runScript(
				ScriptID.UPDATE_SCROLLBAR,
				scrollbar.getId(),
				list.getId(),
				newScroll
		);
	}

	public void shutdown()
	{
		clientThread.invoke(() ->
		{
			Widget trackList = client.getWidget(WidgetInfo.MUSIC_TRACK_LIST);
			if (trackList == null) return;
			for (Widget e : trackList.getDynamicChildren())
			{
				e.setFontId(NORMAL_FONT);
				e.revalidate();
			}
		});
		updateCurrentlyPlayingWidget();
	}

	private void overrideTrack(String trackName, StreamInfoItem item)
	{
		TrackOverride override = new TrackOverride(trackName, ".mp4", item.getUrl(), ImmutableMap.of(
				"Name", item.getName(),
				"Duration", Duration.ofSeconds(item.getDuration()).toString(),
				"Uploader", item.getUploaderName(),
				"Uploader url", item.getUploaderUrl()
		));
		executor.submit(() ->
		{
			try
			{
				StreamExtractor stream = ServiceList.YouTube.getStreamExtractor(item.getUrl());
				stream.fetchPage();
				String url = stream.getAudioStreams().stream()
						.filter(e -> e.getFormat() == MediaFormat.M4A)
						.findAny().map(e -> e.getUrl()).orElse("");

				boolean transferred = transfer(
						() -> Channels.newChannel(new URL(url).openStream()),
						override
				);
				if (transferred)
				{
					configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + trackName, new Gson().toJson(override));
				}
			}
			catch (ExtractionException | IOException e)
			{
				log.warn("Something went wrong while trying to retrieve: " + item, e);
			}
		});
	}

	private void overrideTrack(String trackName, Path path)
	{
		if (!trackNames.contains(trackName)) return;

		executor.submit(() ->
		{
			String extension = path.getFileName().toString().replaceAll(".*(\\..+)", "$1");
			TrackOverride override = new TrackOverride(trackName, extension, path.toString(), ImmutableMap.of());
			boolean transferred = transfer(
					() -> FileChannel.open(path),
					override);
			if (transferred)
			{
				configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + trackName, new Gson().toJson(override));
			}
		});
	}

	private boolean transfer(Callable<ReadableByteChannel> in, TrackOverride override)
	{
		Path target = override.getFile().toPath();
		try (ReadableByteChannel _in = in.call();
			 FileChannel out = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE))
		{
			// This is actually not correct; transferFrom spec doesn't guarantuee full read
			// So SHOULD be called in a loop, but it SEEMS it works this way anyway
			out.transferFrom(_in, 0, Long.MAX_VALUE);
			return true;
		}
		catch (Exception e)
		{
			log.warn("Something went wrong when transferring " + override + ".", e);
			return false;
		}
	}

	private void removeOverrideFor(String trackName)
	{
		TrackOverride override = getOverrideFor(trackName);
		if (override == null) return;

		executor.submit(() ->
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + trackName);
			override.getFile().delete();
		});
	}

	TrackOverride getOverrideFor(String trackName)
	{
		TrackOverride override = new Gson().fromJson(
				configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + trackName),
				TrackOverride.class
		);

		if (override == null || override.getFile().exists())
		{
			return override;
		} else
		{
			log.warn("Deleting: " + override + " because there was no override file for it.");
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + trackName);
			return null;
		}
	}

	@Value
	static class TrackOverride
	{
		String name;
		String extension;
		String originalPath;
		Map<String, String> additionalInfo;

		public File getFile()
		{
			return new File(MUSIC_OVERRIDES_DIR, name + extension);
		}

		public Media getMedia()
		{
			return new Media(getFile().toURI().toString());
		}
	}
}
