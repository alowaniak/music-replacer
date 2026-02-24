package nl.alowaniak.runelite.musicreplacer;

import com.google.common.primitives.Ints;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextMenuInput;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static net.runelite.http.api.RuneLiteAPI.GSON;
import static nl.alowaniak.runelite.musicreplacer.MusicReplacerConfig.CONFIG_GROUP;
import static nl.alowaniak.runelite.musicreplacer.Tracks.OVERRIDE_CONFIG_KEY_PREFIX;

@Slf4j
@Singleton
class TracksOverridesUi
{
	public static final int PAGE_SIZE = 4;

	public static final int OVERRIDE_FONT = FontID.BOLD_12;
	public static final int NORMAL_FONT = FontID.PLAIN_12;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private TooltipManager tooltipManager;
	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private Tracks tracks;
	@Inject
	private YouTubeSearcher ytSearcher;

	private String lastPlayingTrack;
	private boolean overrideWidgetsOutdated = true;

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Widget playingTrackWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		if (playingTrackWidget == null) return;

		String playingTrack = playingTrackWidget.getText();
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
	public void onConfigChanged(ConfigChanged configChanged)
	{
		String key = configChanged.getKey();
		if (CONFIG_GROUP.equals(configChanged.getGroup()) && key.startsWith(OVERRIDE_CONFIG_KEY_PREFIX))
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
		if (varClientIntChanged.getIndex() == VarClientID.TOPLEVEL_PANEL && isOnMusicTab())
		{
			// The widgets could be outdated, just ensure it's updated whenever we go to the music tab
			// TODO figure out better way of ensuring the tracklist is up to date
			overrideWidgetsOutdated = true;
		}
	}

	private boolean isOnMusicTab()
	{
		return client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL) == 13;
	}

	@Subscribe
	public void onMenuOpened(final MenuOpened event)
	{
		final MenuEntry entry = event.getFirstEntry();
		if (entry == null) return;

		int widgetId = entry.getParam1();
		if (widgetId == InterfaceID.Music.JUKEBOX)
		{
			String trackName = Text.removeTags(entry.getTarget());
			addMenuEntry("Override", entry).onClick(e ->
				chatboxPanelManager.openTextMenuInput("How would you like to override " + trackName + "?")
				.option("With a local file.", () -> overrideByLocal(trackName))
				.option("From a youtube search.", () -> overrideBySearch(trackName))
				.build()
			);

			if (tracks.getOverride(trackName) != null)
			{
				addMenuEntry("Remove override", entry).onClick(e -> tracks.removeOverride(trackName));
			}
		}
		else if (widgetId == InterfaceID.Toplevel.STONE13
				|| widgetId == InterfaceID.ToplevelOsrsStretch.STONE13
				|| widgetId == InterfaceID.ToplevelPreEoc.STONE13
                || widgetId == InterfaceID.ToplevelOsm.STONE13)
		{
			if (!tracks.overriddenTracks().isEmpty())
			{
				addMenuEntry("Remove overrides", entry).onClick(e -> tracks.removeAllOverrides());
			}

			addMenuEntry("Override tracks", entry).onClick(e ->
				chatboxPanelManager.openTextMenuInput("How would you like to bulk override?")
						.option("From preset", choosePresetForBulkOverride)
						.option("From directory", () -> SwingUtilities.invokeLater(chooseDirectoryForBulkOverride))
						.build()
			);
		}
	}

	private final Runnable choosePresetForBulkOverride =  () -> {
		try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/presets.json"))) {
			ChatboxTextMenuInput input = chatboxPanelManager.openTextMenuInput("Which preset? (This will download ALL overridden tracks!)");
			List<Preset> presets = GSON.fromJson(reader, Preset.LIST_TYPE.getType());
			presets.forEach(preset -> input.option(preset.getName(), () -> tracks.bulkCreateOverride(preset)));
			input.build();
		} catch (NullPointerException | IOException ex) {
			log.warn("Something went wrong when reading presets.", ex);
		}
	};

	private final Runnable chooseDirectoryForBulkOverride = () -> {
		JFileChooser fileChooser = new JFileChooser();
		fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fileChooser.setMultiSelectionEnabled(false);
		int status = fileChooser.showOpenDialog(client.getCanvas());
		if (status == JFileChooser.APPROVE_OPTION) {
			tracks.bulkCreateOverride(fileChooser.getSelectedFile().toPath());
		}
	};

	private MenuEntry addMenuEntry(String option, MenuEntry entryForCopy)
	{
		return client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget(entryForCopy.getTarget())
			.setType(MenuAction.RUNELITE)
			.setParam0(entryForCopy.getParam0())
			.setParam1(entryForCopy.getParam1())
			.setIdentifier(entryForCopy.getIdentifier());
	}

	private void overrideByLocal(String trackName)
	{
		SwingUtilities.invokeLater(() -> {
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			fileChooser.setMultiSelectionEnabled(false);
			fileChooser.setFileFilter(new FileFilter() {
				@Override
				public boolean accept(File f) {
					return f.isDirectory() || MusicPlayer.PLAYER_PER_EXT.keySet().stream().anyMatch(ext -> f.getName().endsWith(ext));
				}

				@Override
				public String getDescription() {
					return "Supported audio files";
				}
			});
			int status = fileChooser.showOpenDialog(client.getCanvas());
			if (status == JFileChooser.APPROVE_OPTION) {
				tracks.createOverride(trackName, fileChooser.getSelectedFile().toPath());
			}
		});
	}

	private void overrideBySearch(String trackName)
	{
		chatboxPanelManager.openTextInput("Enter your search criteria for " + trackName)
				.value(trackName)
				.onDone((String s) -> ytSearcher.search(s, hits -> paginateSearch(trackName, hits)))
				.build();
	}

	private void paginateSearch(String trackName, List<SearchResult> hits)
	{
		ChatboxTextMenuInput chooser = chatboxPanelManager.openTextMenuInput("Choose which you want to use as override for " + trackName);

		int pageSize = PAGE_SIZE + (PAGE_SIZE + 1 == hits.size() ? 1 : 0);

		hits.stream().limit(pageSize).forEach(hit ->
			chooser.option(
				hit.getName() + " " + Duration.ofSeconds(hit.getDuration()).toString().substring(2) + " " + hit.getUploader(),
				() -> tracks.createOverride(trackName, hit)
			)
		);

		List<SearchResult> remainingHits = hits.subList(Math.min(hits.size(), pageSize), hits.size());
		if (!remainingHits.isEmpty()) chooser.option("Continue", () -> paginateSearch(trackName, remainingHits));

		chooser.build();
	}

	private void updateOverridesInTrackList()
	{
		clientThread.invoke(() ->
		{
			Widget trackList = client.getWidget(InterfaceID.Music.JUKEBOX);
			if (trackList == null) return;
			for (Widget e : trackList.getDynamicChildren())
			{
				e.setFontId(tracks.overrideExists(e.getText()) ? OVERRIDE_FONT : NORMAL_FONT);
				e.revalidate();
			}
		});
	}

	Tooltip trackInfoTooltip;
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		trackInfoTooltip = null;
	}
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (trackInfoTooltip != null) tooltipManager.add(trackInfoTooltip);
	}


	private void updateCurrentlyPlayingWidget()
	{
		clientThread.invoke(() ->
		{
			Widget trackPlayingWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
			if (trackPlayingWidget == null) return;

			trackPlayingWidget.setOnClickListener((JavaScriptCallback) e -> scrollToTrack(e.getSource().getText()));
			trackPlayingWidget.setHasListener(true);

			TrackOverride override = tracks.getOverride(trackPlayingWidget.getText());
			if (override != null)
			{
				trackPlayingWidget.setFontId(OVERRIDE_FONT);

				String origin = truncate(override.getOriginalPath(), 40);

				StringBuilder tooltipTxt = new StringBuilder("From: " + origin + "</br>");
				override.getAdditionalInfo().entrySet().stream()
						.sorted(Map.Entry.comparingByKey(Comparator.comparing(String::length).thenComparing(String::compareTo)))
						.forEach(e -> tooltipTxt.append(e.getKey()).append(": ").append(e.getValue()).append("</br>"));
				Tooltip tooltip = new Tooltip(tooltipTxt.toString());

				trackPlayingWidget.setOnMouseRepeatListener((JavaScriptCallback) e -> trackInfoTooltip = tooltip);
			}
			else
			{
				trackPlayingWidget.setOnMouseRepeatListener((Object[]) null);
				trackPlayingWidget.setFontId(NORMAL_FONT);
			}
		});
	}

	private void scrollToTrack(String name)
	{
		Widget track = findTrackWidget(name);
		if (track == null) return;

		Widget scrollContainer = client.getWidget(InterfaceID.Music.SCROLLABLE);
		if (scrollContainer == null) return;

		int centralY = track.getRelativeY() + track.getHeight() / 2;

		int newScroll = Ints.constrainToRange(
			centralY - scrollContainer.getHeight() / 2,
			0, scrollContainer.getScrollHeight()
		);

		client.runScript(
			ScriptID.UPDATE_SCROLLBAR,
			InterfaceID.Music.SCROLLBAR,
			InterfaceID.Music.SCROLLABLE,
			newScroll
		);
	}

	private Widget findTrackWidget(String name) {
		Widget trackList = client.getWidget(InterfaceID.Music.JUKEBOX);
		if (trackList == null) return null;

		return Stream.of(trackList.getDynamicChildren())
				.filter(w -> name.equals(w.getText()))
				.findAny().orElse(null);
	}

	private void clearCurrentlyPlayingWidget()
	{
		clientThread.invoke(() ->
		{
			Widget trackPlayingWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
			if (trackPlayingWidget == null) return;

			trackPlayingWidget.setFontId(NORMAL_FONT);

			trackPlayingWidget.setOnClickListener((Object[]) null);
			trackPlayingWidget.setOnMouseRepeatListener((Object[]) null);
			trackPlayingWidget.setHasListener(false);
		});
	}

	public void shutdown()
	{
		overrideWidgetsOutdated = true;
		lastPlayingTrack = null;
		clientThread.invoke(() ->
		{
			Widget trackList = client.getWidget(InterfaceID.Music.JUKEBOX);
			if (trackList == null) return;
			for (Widget e : trackList.getDynamicChildren())
			{
				e.setFontId(NORMAL_FONT);
				e.revalidate();
			}
		});
		clearCurrentlyPlayingWidget();
	}

	private static String truncate(String s, int length)
	{
		return s.length() <= length ? s : s.substring(0, length / 2 - 2) + " .. " + s.substring(s.length() - (length / 2 - 2));
	}
}
