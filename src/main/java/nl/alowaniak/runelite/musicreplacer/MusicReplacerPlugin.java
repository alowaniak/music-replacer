package nl.alowaniak.runelite.musicreplacer;

import com.google.inject.Binder;
import com.google.inject.name.Names;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.music.MusicConfig;
import net.runelite.client.plugins.music.MusicPlugin;

import javax.inject.Inject;
import java.util.Objects;
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

	private static final int MUSIC_LOOP_STATE_VAR_ID = 4137;
	private static final double MAX_VOL = 255;

	@Override
	public void configure(Binder binder)
	{
		// Use our own ExecutorService instead of ScheduledExecutorService because the downloads can take a while
		binder.bind(ExecutorService.class).annotatedWith(Names.named(MUSIC_REPLACER_EXECUTOR)).toInstance(Executors.newSingleThreadExecutor());
	}

	@Inject
	private Client client;
	@Inject
	private EventBus eventBus;

	@Inject
	private Tracks tracks;
	@Inject
	private TracksOverridesUi tracksOverridesUi;

	@Inject
	private MusicConfig musicConfig;

	@Inject
	private ClientThread clientThread;

	private MusicPlayer player;
	private TrackOverride trackToPlay;

	private double fading;

	@Override
	protected void startUp()
	{
		eventBus.register(tracksOverridesUi);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Widget curTrackWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
		if (curTrackWidget == null) return;

		String curTrack = curTrackWidget.getText();
		TrackOverride newTrack = tracks.getOverride(curTrack);
		if (!Objects.equals(trackToPlay, newTrack))
		{
			trackToPlay = newTrack;
			if (fading <= 0) fading = 1;
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

	private double oldVolume = -1;
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (fading > 0)
		{
			applyVolume(fading -= .01);

			if (fading <= 0)
			{
				stopPlaying();

				if (trackToPlay != null)
				{
					try
					{
						player = null;
						player = MusicPlayer.create(trackToPlay.getPath().toUri());
						player.play();
					}
					catch (OutOfMemoryError e)
					{
						log.warn("Out of memory when loading " + trackToPlay, e);
						trackToPlay = null;
					}
				}

				applyVolume();
			}
		}
		else if (player != null)
		{
			double volume = (musicConfig.getMusicVolume() - 1) / MAX_VOL;
			if ((oldVolume <= 0 && volume > 0) || (!player.isPlaying() && client.getVarbitValue(MUSIC_LOOP_STATE_VAR_ID) == 1))
			{
				// Restart play if
				// we switched from muted to on (mimic osrs behavior)
				// or we ended and have loop enabled
				player.play();
			}

			applyVolume();
			oldVolume = volume;
		}
	}

	private void applyVolume()
	{
		applyVolume(1);
	}

	private void applyVolume(double multiplier)
	{
		// Setting client music volume with invokeLater seems to prevent the original music from coming through
		// I'm guessing because it depends on "where" in the client loop the vol is set
		// And with invokeLater it happens to "overwrite" Music plugin's write at the correct "point" in the client loop

		if (player == null)
		{
			int volume = (int) ((musicConfig.getMusicVolume() - 1) * multiplier);
			clientThread.invokeLater(() -> client.setMusicVolume(volume));
		}
		else
		{
			if (player.isPlaying())
			{
				double volume = (musicConfig.getMusicVolume() - 1) / MAX_VOL * multiplier;
				player.setVolume(volume);
			}
			clientThread.invokeLater(() -> client.setMusicVolume(0));
		}
	}

	public void stopPlaying()
	{
		fading = 0;
		if (player != null)
		{
			player.close();
			player = null;
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(tracksOverridesUi);
		tracksOverridesUi.shutdown();
		trackToPlay = null;
		stopPlaying();
	}
}
