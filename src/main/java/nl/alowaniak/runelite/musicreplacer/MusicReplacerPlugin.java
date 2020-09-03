package nl.alowaniak.runelite.musicreplacer;

import com.google.inject.Binder;
import com.google.inject.name.Names;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.embed.swing.JFXPanel;
import javafx.scene.media.MediaPlayer;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.music.MusicConfig;
import net.runelite.client.plugins.music.MusicPlugin;

@Slf4j
@PluginDescriptor(
		name = "Music Replacer",
		description = "Replace OSRS music tracks with user defined music",
		tags = {"music", "replace", "track"}
)
@PluginDependency(MusicPlugin.class)
public class MusicReplacerPlugin extends Plugin
{
	public static final int CURRENTLY_PLAYING_WIDGET_ID = 6;
	public static final double MAX_VOL = 255;

	static // We use jfx's media player which needs some initialization
	{
		new JFXPanel();
	}

	@Override
	public void configure(Binder binder)
	{
		// Use our own ExecutorService instead of ScheduledExecutorService because the downloads can take a while
		binder.bind(ExecutorService.class).annotatedWith(Names.named("musicReplacerExecutor")).toInstance(Executors.newSingleThreadExecutor());
	}

	@Inject
	private Client client;
	@Inject
	private EventBus eventBus;
	@Inject
	private MusicConfig musicConfig;
	@Inject
	private TracksOverrides tracksOverrides;

	private MediaPlayer mediaPlayer;
	private TracksOverrides.TrackOverride currentOverride;

	@Override
	protected void startUp()
	{
		tracksOverrides.startup();
		eventBus.register(tracksOverrides);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		String curTrack = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID).getText();
		TracksOverrides.TrackOverride newOverride = tracksOverrides.getOverrideFor(curTrack);
		if (!Objects.equals(currentOverride, newOverride))
		{
			play(newOverride);
		}
	}

	private void play(TracksOverrides.TrackOverride newOverride)
	{
		currentOverride = newOverride;
		stopPlaying();
		if (currentOverride != null)
		{
			mediaPlayer = new MediaPlayer(newOverride.getMedia());
			mediaPlayer.play();
		}
	}

	private void stopPlaying()
	{
		if (mediaPlayer != null)
		{
			mediaPlayer.dispose();
			mediaPlayer = null;
		}
		client.setMusicVolume(musicConfig.getMusicVolume() - 1);
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (currentOverride != null)
		{
			client.setMusicVolume(0);
			if (mediaPlayer != null)
			{
				mediaPlayer.setVolume((musicConfig.getMusicVolume() - 1) / MAX_VOL);
			}
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(tracksOverrides);
		tracksOverrides.shutdown();
	}
}
