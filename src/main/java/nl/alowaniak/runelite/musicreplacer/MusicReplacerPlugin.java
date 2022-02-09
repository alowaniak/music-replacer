package nl.alowaniak.runelite.musicreplacer;

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.music.MusicPlugin;

import javax.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@PluginDescriptor(
		name = "Music Replacer",
		description = "Replace music tracks with your own music",
		tags = {"music", "replace", "track", "beats"}
)
@PluginDependency(MusicPlugin.class)
public class MusicReplacerPlugin extends Plugin
{
	public static final String MUSIC_REPLACER_API = "https://alowan.nl/runelite-music-replacer/";
	public static final String MUSIC_REPLACER_EXECUTOR = "musicReplacerExecutor";
	public static final int CURRENTLY_PLAYING_WIDGET_ID = 6;

	@Override
	public void configure(Binder binder)
	{
		// Use our own ExecutorService instead of ScheduledExecutorService because the downloads can take a while
		binder.bind(ExecutorService.class).annotatedWith(Names.named(MUSIC_REPLACER_EXECUTOR)).toInstance(Executors.newSingleThreadExecutor());
	}

	@Provides
	MusicReplacerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MusicReplacerConfig.class);
	}

	@Inject
	private Client client;
	@Inject
	private EventBus eventBus;

	@Inject
	private Tracks tracks;
	@Inject
	private MusicPlayer player;
	@Inject
	private TracksOverridesUi tracksOverridesUi;

	@Override
	protected void startUp()
	{
		eventBus.register(tracksOverridesUi);
		eventBus.register(player);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Widget curTrackWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
		if (curTrackWidget == null) return;

		String curTrack = curTrackWidget.getText();
		player.play(tracks.getOverride(curTrack));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			player.play(null);
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(tracksOverridesUi);
		eventBus.unregister(player);
		tracksOverridesUi.shutdown();
		player.play(null);
	}
}
