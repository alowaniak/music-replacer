package nl.alowaniak.runelite.musicreplacer;

import com.adonax.audiocue.AudioCue;
import com.adonax.audiocue.AudioCueInstanceEvent;
import com.adonax.audiocue.AudioCueListener;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.scene.media.MediaPlayer;
import javax.inject.Inject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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
public class MusicReplacerPlugin extends Plugin implements AudioCueListener
{
	public static final int CURRENTLY_PLAYING_WIDGET_ID = 6;
	private static final int MUSIC_LOOP_STATE_VAR_ID = 4137;
	public static final double MAX_VOL = 255;

	@Override
	public void configure(Binder binder)
	{
		// Use our own ExecutorService instead of ScheduledExecutorService because the downloads can take a while
		binder.bind(ExecutorService.class).annotatedWith(Names.named("musicReplacerExecutor")).toInstance(Executors.newSingleThreadExecutor());
	}

	@Provides
	MusicReplacerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MusicReplacerConfig.class);
	}

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private EventBus eventBus;
	@Inject
	private MusicConfig musicConfig;

	@Inject
	private Tracks tracks;
	@Inject
	private TracksOverridesUi tracksOverrides;

	private AudioCue audioCue;
	private TrackOverride currentOverride;

	@Override
	protected void startUp()
	{
		eventBus.register(tracksOverrides);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		MediaPlayer d;
		Widget curTrackWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
		if (curTrackWidget == null) return;

		String curTrack = curTrackWidget.getText();
		TrackOverride newOverride = tracks.getOverride(curTrack);
		if (!Objects.equals(currentOverride, newOverride))
		{
			play(newOverride);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			stopPlaying();
		}
	}

	@SneakyThrows
	private void play(TrackOverride newOverride)
	{
		stopPlaying();
		currentOverride = newOverride;
		if (currentOverride != null)
		{
			client.setMusicVolume(0);
			audioCue = AudioCue.makeStereoCue(newOverride.getPath().toUri().toURL(), 1);
			audioCue.addAudioCueListener(this);
			audioCue.open();
			audioCue.play(0);
		}
		else
		{
			clientThread.invokeLater(() -> client.setMusicVolume(musicConfig.getMusicVolume() - 1));
		}
	}

	private void stopPlaying()
	{
		if (audioCue != null)
		{
			audioCue.close();
			audioCue = null;
		}
	}

	@Override
	public void instanceEventOccurred(AudioCueInstanceEvent e)
	{
		if (e.type == AudioCueInstanceEvent.Type.RELEASE_INSTANCE && client.getVarbitValue(MUSIC_LOOP_STATE_VAR_ID) == 1)
		{
			audioCue.play();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (currentOverride != null)
		{
			// Setting the music volume to 0 with invokeLater seems to prevents the original music from coming through
			// I'm guessing because it depends on "where" in the client loop the vol is 0
			// And with invokeLater it "overwrites" Music plugin's write at the correct "point" in the client loop
			clientThread.invokeLater(() -> client.setMusicVolume(0));

			if (audioCue != null)
			{
				double volume = (musicConfig.getMusicVolume() - 1) / MAX_VOL;
				if (volume != 0)
				{
					if (!audioCue.getIsActive(0))
					{
						if (client.getVarbitValue(MUSIC_LOOP_STATE_VAR_ID) == 1)
						{
							audioCue.play();
						}
					}
					else if (!audioCue.getIsPlaying(0))
					{
						audioCue.start(0);
					}
					if (audioCue.getIsActive(0))
					{
						audioCue.setVolume(0, volume);
					}
				}
				else if (audioCue.getIsActive(0))
				{
					audioCue.stop(0);
					audioCue.setMillisecondPosition(0, 0);
				}
			}
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(tracksOverrides);
		tracksOverrides.shutdown();
		stopPlaying();
		clientThread.invokeLater(() -> client.setMusicVolume(musicConfig.getMusicVolume() - 1));
		currentOverride = null;
	}

	@Override public void audioCueOpened(long l, int i, int i1, AudioCue audioCue) { /* not interested */ }
	@Override public void audioCueClosed(long l, AudioCue audioCue) { /* not interested */ }
}
