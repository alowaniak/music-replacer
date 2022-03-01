package nl.alowaniak.runelite.musicreplacer;

import com.google.common.primitives.Ints;
import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
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
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static nl.alowaniak.runelite.musicreplacer.MusicReplacerConfig.CONFIG_GROUP;
import static nl.alowaniak.runelite.musicreplacer.MusicReplacerPlugin.CURRENTLY_PLAYING_WIDGET_ID;
import static nl.alowaniak.runelite.musicreplacer.Tracks.OVERRIDE_CONFIG_KEY_PREFIX;

@Singleton
class TracksOverridesUi
{
	public static final int PAGE_SIZE = 4;

	public static final int OVERRIDE_FONT = FontID.BOLD_12;
	public static final int NORMAL_FONT = FontID.PLAIN_12;

	public static final int RESIZABLE_VIEWPORT_BOTTOM_LINE_MUSIC_TAB_ID = 10747946;

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
	private boolean overrideWidgetsOutdated;

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Widget playingTrackWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
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
		else if (widgetId == WidgetInfo.FIXED_VIEWPORT_MUSIC_TAB.getId()
				|| widgetId == WidgetInfo.RESIZABLE_VIEWPORT_MUSIC_TAB.getId()
				|| widgetId == RESIZABLE_VIEWPORT_BOTTOM_LINE_MUSIC_TAB_ID)
		{
			if (!tracks.overriddenTracks().isEmpty())
			{
				addMenuEntry("Remove overrides", entry).onClick(e -> tracks.removeAllOverrides());
			}

			addMenuEntry("Override tracks", entry).onClick(e ->
				chatboxPanelManager.openTextInput("Enter directory with override songs")
				.onDone(tracks::bulkCreateOverride)
				.build()
			);
		}
	}

	private MenuEntry addMenuEntry(String option, MenuEntry entryForCopy)
	{
		return client.createMenuEntry(-1)
			.setOption(option)
			.setTarget(entryForCopy.getTarget())
			.setType(MenuAction.RUNELITE)
			.setParam0(entryForCopy.getParam0())
			.setParam1(entryForCopy.getParam1())
			.setIdentifier(entryForCopy.getIdentifier());
	}

	private void overrideByLocal(String trackName)
	{
		chatboxPanelManager.openTextInput("Enter path to override " + trackName)
				.onDone((String s) -> tracks.createOverride(trackName, Paths.get(s)))
				.build();
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
			Widget trackList = client.getWidget(WidgetInfo.MUSIC_TRACK_LIST);
			if (trackList == null) return;
			for (Widget e : trackList.getDynamicChildren())
			{
				e.setFontId(tracks.overrideExists(e.getText()) ? OVERRIDE_FONT : NORMAL_FONT);
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

			String trackName = trackPlayingWidget.getText();
			trackPlayingWidget.setOnClickListener((JavaScriptCallback) e -> scrollToTrack(trackName));
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

				trackPlayingWidget.setOnMouseRepeatListener((JavaScriptCallback) e ->
				{
					if (!tooltipManager.getTooltips().contains(tooltip)) tooltipManager.add(tooltip);
				});
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
		Widget trackList = client.getWidget(WidgetInfo.MUSIC_TRACK_LIST);
		if (trackList == null) return;

		Widget track = Stream.of(trackList.getDynamicChildren())
			.filter(w -> name.equals(w.getText()))
			.findAny().orElse(null);
		if (track == null) return;

		int centralY = track.getRelativeY() + track.getHeight() / 2;

		int newScroll = Ints.constrainToRange(
			centralY - trackList.getHeight() / 2,
			0, trackList.getScrollHeight()
		);

		client.runScript(
			ScriptID.UPDATE_SCROLLBAR,
			WidgetInfo.MUSIC_TRACK_SCROLLBAR.getId(),
			WidgetInfo.MUSIC_TRACK_LIST.getId(),
			newScroll
		);
	}

	private void clearCurrentlyPlayingWidget()
	{
		clientThread.invoke(() ->
		{
			Widget trackPlayingWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
			if (trackPlayingWidget == null) return;

			trackPlayingWidget.setFontId(NORMAL_FONT);

			trackPlayingWidget.setOnClickListener((Object[]) null);
			trackPlayingWidget.setOnMouseRepeatListener((Object[]) null);
			trackPlayingWidget.setHasListener(false);
		});
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
		clearCurrentlyPlayingWidget();
	}

	private static String truncate(String s, int length)
	{
		return s.length() <= length ? s : s.substring(0, length / 2 - 2) + " .. " + s.substring(s.length() - (length / 2 - 2));
	}
}
