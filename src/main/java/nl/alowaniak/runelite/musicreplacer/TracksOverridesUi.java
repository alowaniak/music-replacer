package nl.alowaniak.runelite.musicreplacer;

import com.google.common.primitives.Ints;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientInt;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
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
import org.apache.commons.lang3.ArrayUtils;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import static nl.alowaniak.runelite.musicreplacer.MusicReplacerPlugin.CONFIG_GROUP;
import static nl.alowaniak.runelite.musicreplacer.MusicReplacerPlugin.CURRENTLY_PLAYING_WIDGET_ID;
import static nl.alowaniak.runelite.musicreplacer.Tracks.OVERRIDE_CONFIG_KEY_PREFIX;

@Singleton
class TracksOverridesUi
{
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
			addMenuEntry(OVERRIDE_OPTION, entry);

			String trackName = Text.removeTags(entry.getTarget());
			if (tracks.getOverride(trackName) != null)
			{
				addMenuEntry(REMOVE_OVERRIDE_OPTION, entry);
			}
		}
		else if (widgetId == WidgetInfo.FIXED_VIEWPORT_MUSIC_TAB.getId()
				|| widgetId == WidgetInfo.RESIZABLE_VIEWPORT_MUSIC_TAB.getId())
		{
			addMenuEntry(OVERRIDE_ALL_OPTION, entry);

			if (!tracks.overriddenTracks().isEmpty())
			{
				addMenuEntry(REMOVE_ALL_OVERRIDES_OPTION, entry);
			}
		}
	}

	private void addMenuEntry(String option, MenuEntry entryForCopy)
	{
		MenuEntry entry = new MenuEntry();
		entry.setOption(option);
		entry.setTarget(entryForCopy.getTarget());
		entry.setType(MenuAction.RUNELITE.getId());
		entry.setParam0(entryForCopy.getParam0());
		entry.setParam1(entryForCopy.getParam1());
		entry.setIdentifier(entryForCopy.getIdentifier());
		client.setMenuEntries(ArrayUtils.insert(1, client.getMenuEntries(), entry));
	}

	@Subscribe
	public void onMenuOptionClicked(final MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.RUNELITE) return;

		String target = Text.removeTags(event.getMenuTarget());
		String menuOption = event.getMenuOption();
		if (tracks.exists(target))
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
				tracks.removeOverride(target);
			}
		}
		else if (OVERRIDE_ALL_OPTION.equals(menuOption))
		{
			chatboxPanelManager.openTextInput("Enter directory with override songs")
					.onDone(tracks::bulkCreateOverride)
					.build();
		}
		else if (REMOVE_ALL_OVERRIDES_OPTION.equals(menuOption))
		{
			tracks.removeAllOverrides();
		}
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
				.onDone((String s) -> showSearchHits(trackName, s))
				.build();
	}

	private void showSearchHits(String trackName, String searchTerm)
	{
		ytSearcher.search(searchTerm, (hits, continueSearch) ->
		{
			ChatboxTextMenuInput chooser = chatboxPanelManager.openTextMenuInput("Choose which you want to use as override for " + trackName);

			for (StreamInfoItem hit : hits)
			{
				chooser.option(
						hit.getName() + " by " + hit.getUploaderName(),
						() -> tracks.createOverride(trackName, hit)
				);
			}
			if (continueSearch != null) chooser.option("Continue...", continueSearch);

			chooser.build();
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

				String origin = override.getOriginalPath();
				if (origin.length() > 40)
				{
					origin = origin.substring(0, 18) + " .. " + origin.substring(origin.length() - 18);
				}

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
}
