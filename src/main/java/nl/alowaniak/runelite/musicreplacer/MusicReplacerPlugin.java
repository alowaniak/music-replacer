package nl.alowaniak.runelite.musicreplacer;

import com.google.common.primitives.Doubles;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import javax.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static nl.alowaniak.runelite.musicreplacer.TracksOverridesUi.NORMAL_FONT;
import static nl.alowaniak.runelite.musicreplacer.TracksOverridesUi.OVERRIDE_FONT;

@Slf4j
@PluginDescriptor(
		name = "Music Replacer",
		description = "Replace music tracks with presets (e.g. OSRSBeatz) or your own music",
		tags = {"music", "replace", "override", "track", "song", "youtube", "beats", "osrsbeatz", "rs3"}
)
public class MusicReplacerPlugin extends Plugin
{

	/**
	 * Not sure how this works, but changing client music volume from 0->1 with {@link Client#setMusicVolume(int)} won't
	 * make the track play again. Using this script to turn off and on however will.
	 */
	private static final int RETRIGGER_MUSIC_SCRIPT = 9238;

	public static final String MUSIC_REPLACER_API = "https://alowan.nl/runelite-music-replacer/";
	public static final String MUSIC_REPLACER_EXECUTOR = "musicReplacerExecutor";

	/**
	 * The max the volume sliders ({@link VarPlayerID#OPTION_MASTER_VOLUME}, {@link VarPlayerID#OPTION_MUSIC}) can be
	 */
	private static final double MAX_VOL_OPTION = 100;

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
	private ClientThread clientThread;
	@Inject
	private TooltipManager tooltipManager;

	@Inject
	private Tracks tracks;
	@Inject
	private TracksOverridesUi tracksOverridesUi;

	@Inject
	private MusicReplacerConfig config;

	/**
	 * Every call into the audio library happens on this thread, never on the client thread.
	 * JaCo's MP3Player is one shared, synchronized instance that holds its lock for ~0.4s while starting a track,
	 * so even a cheap isPlaying()/setVolume() from the client thread would freeze the game for that long.
	 * The client thread only reads {@link #playerPlaying} and writes {@link #desiredVolume}; this thread syncs them.
	 */
	private final ScheduledExecutorService playerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "music-replacer-player");
		t.setDaemon(true);
		return t;
	});
	/** Bumped on every stop/switch so a slow load that's no longer wanted doesn't start playing */
	private final AtomicInteger playerGeneration = new AtomicInteger();
	/** Switches queued on {@link #playerExecutor} that haven't finished yet */
	private final AtomicInteger pendingSwitches = new AtomicInteger();
	private volatile boolean restartQueued;
	private volatile MusicPlayer player;
	/** Last known isPlaying() of {@link #player}, refreshed on the player thread */
	private volatile boolean playerPlaying;
	/** Volume the client thread wants; applied on the player thread */
	private volatile double desiredVolume = -1;
	private double appliedVolume = -1; // player thread only
	private MusicPlayer volumeAppliedTo; // player thread only

	{
		playerExecutor.scheduleWithFixedDelay(this::syncPlayerState, 50, 50, TimeUnit.MILLISECONDS);
	}

	private void syncPlayerState()
	{
		try
		{
			MusicPlayer current = player;
			if (current == null) return;
			double volume = desiredVolume;
			if (volume >= 0 && (volume != appliedVolume || current != volumeAppliedTo))
			{
				current.setVolume(volume);
				appliedVolume = volume;
				volumeAppliedTo = current;
			}
			playerPlaying = current.isPlaying();
		}
		catch (Exception e)
		{
			log.warn("Error syncing music player state", e);
		}
	}
	private String actualCurTrack;
	private boolean restoreActualCurTrack;
	private TrackOverride trackToPlay;

	private double fading;

	@Override
	protected void startUp()
	{
		eventBus.register(tracksOverridesUi);
	}

	@Provides
	MusicReplacerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MusicReplacerConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			stopPlaying();
		}
	}

	/**
	 * Tooltips need to be added before each render, so we clear it on client tick and gets added on mouse listener
	 */
	Tooltip upNextTooltip;
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (upNextTooltip != null) tooltipManager.add(upNextTooltip);
	}

	private double oldVolume = -1;
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		upNextTooltip = null;
		applyVolume(); // Always make sure we're on the right volume/fade

		Widget curTrackWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		Widget playingWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TITLE);
		if (curTrackWidget == null || playingWidget == null) return;
		String curTrack = curTrackWidget.getText();
		if (Strings.isNullOrEmpty(curTrack)) return;

		// I would rather do UI kinda stuff in TracksOverridesUi, but let's do it here for now
		if (restoreActualCurTrack)
		{
			restoreActualCurTrack = false;
			curTrackWidget.setText(curTrack = actualCurTrack);
			playingWidget.setFontId(NORMAL_FONT);
			playingWidget.setHasListener(false);
		}
		else if (config.playOverridesToEnd() && trackToPlay != null && !curTrack.equals(trackToPlay.getName()))
		{
			// curTrack is a new one, so keep track of actual playing track and change widget
			actualCurTrack = curTrack;
			playingWidget.setFontId(OVERRIDE_FONT);
			Tooltip tooltip = new Tooltip("Up next: " + actualCurTrack);
			playingWidget.setOnMouseRepeatListener((JavaScriptCallback) e -> upNextTooltip = tooltip);
			playingWidget.setOnClickListener((JavaScriptCallback) e -> restoreActualCurTrack = true);
			playingWidget.setHasListener(true);
			curTrackWidget.setText(curTrack = trackToPlay.getName());
		}

		TrackOverride newTrack = tracks.getOverride(curTrack);
		if (!Objects.equals(trackToPlay, newTrack))
		{
			trackToPlay = newTrack;
			if (fading <= 0) fading = 1;
		}


		if (fading > 0)
		{
			if ((fading -= .017) <= 0)
			{
				stopCurrentAndStartNew();
			}
		}
		else if (player != null)
		{
			MusicPlayer current = player;
			double volume = getEffectiveVolume();
			boolean actualTrackIsBeingOverruled = config.playOverridesToEnd() && actualCurTrack != null && trackToPlay != null && !actualCurTrack.equals(trackToPlay.getName());
			if (actualTrackIsBeingOverruled && (volume <= 0 || !playerPlaying))
			{
				restoreActualCurTrack = true;
			}
			else if (!restartQueued && ((oldVolume <= 0 && volume > 0) || (!playerPlaying && client.getVarbitValue(VarbitID.MUSIC_ENABLELOOP) == 1)))
			{
				// Restart play if
				// we switched from muted to on (mimic osrs behavior)
				// or we ended and have loop enabled
				restartQueued = true;
				playerExecutor.execute(() ->
				{
					try
					{
						if (player == current)
						{
							current.play();
							playerPlaying = current.isPlaying();
						}
					}
					finally
					{
						restartQueued = false;
					}
				});
			}
			oldVolume = volume;
		}
	}

	private void stopCurrentAndStartNew() {
		stopPlaying();

		TrackOverride track = trackToPlay;
		if (track == null) return;

		int generation = playerGeneration.get();
		pendingSwitches.incrementAndGet();
		playerExecutor.execute(() ->
		{
			try
			{
				if (generation != playerGeneration.get()) return; // superseded before we got to it
				MusicPlayer loaded = track.getPaths()
						.filter(Files::exists)
						.map(Path::toUri)
						.map(MusicPlayer::create)
						.filter(Objects::nonNull)
						.findFirst()
						.orElse(null);
				if (loaded == null)
				{
					chatMsg("Deleting " + track + " override because no player could be made (no file or wrong format?).");
					tracks.removeOverride(track.getName());
				}
				else if (generation != playerGeneration.get())
				{
					loaded.close(); // another switch/stop happened while we were loading
				}
				else
				{
					loaded.setVolume(0); // syncPlayerState fades it in from desiredVolume
					appliedVolume = 0;
					volumeAppliedTo = loaded;
					loaded.play();
					playerPlaying = loaded.isPlaying();
					player = loaded;
				}
			}
			catch (OutOfMemoryError e)
			{
				log.warn("Out of memory when loading " + track, e);
				clientThread.invoke(() -> { if (trackToPlay == track) trackToPlay = null; });
			}
			finally
			{
				pendingSwitches.decrementAndGet();
			}
		});
	}

	private void applyVolume()
	{
		// Applying volume is only needed for our own player (osrs obviously handles its own volume)
		MusicPlayer current = player;
		if (current == null && pendingSwitches.get() > 0)
		{
			client.setMusicVolume(0); // our next track is still loading on the player thread
		}
		else if (current == null)
		{ // But if we turned it off before we do need to "activate" it again
			var weTurnedOffMusic = client.getMusicVolume() == 0 && getEffectiveVolume() > 0;
			if (weTurnedOffMusic && client.getGameState() == GameState.LOGGED_IN) {
				// Just a setMusicVolume(>0) won't make the music start playing but running cs2 script to
				// turn music off and on again will trigger it
				var musicVol = client.getVarpValue(VarPlayerID.OPTION_MUSIC);
				client.runScript(RETRIGGER_MUSIC_SCRIPT, InterfaceID.SettingsSide.MUSIC_SLIDER_BOBBLE, 0, 116, 1);
				client.runScript(RETRIGGER_MUSIC_SCRIPT, InterfaceID.SettingsSide.MUSIC_SLIDER_BOBBLE, musicVol, 116, 1);
			}
		}
		else
		{
			var multiplier = fading > 0 ? Math.pow(fading, 3) : 1d;
			desiredVolume = Doubles.constrainToRange(getEffectiveVolume() * multiplier, 0, 1);
			client.setMusicVolume(0); // Constantly applying volume 0 is not needed but ohwell
		}
	}

	private double getEffectiveVolume() {
		var masterVol = client.getVarpValue(VarPlayerID.OPTION_MASTER_VOLUME) / MAX_VOL_OPTION;
		var musicVol = client.getVarpValue(VarPlayerID.OPTION_MUSIC) / MAX_VOL_OPTION;
		var effectiveVol = masterVol * musicVol;
		return effectiveVol * effectiveVol; // Exponential volume since we hear logarithmically
	}

	public void stopPlaying()
	{
		fading = 0;
		playerGeneration.incrementAndGet();
		MusicPlayer old = player;
		player = null;
		playerPlaying = false;
		if (old != null)
		{
			playerExecutor.execute(old::close);
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(tracksOverridesUi);
		tracksOverridesUi.shutdown();
		trackToPlay = null;
		stopPlaying();
		clientThread.invoke(() ->
		{
			applyVolume();
			Widget curTrackWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
			Widget playingWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TITLE);
			if (curTrackWidget == null || playingWidget == null) return;
			if (!Strings.isNullOrEmpty(actualCurTrack)) curTrackWidget.setText(actualCurTrack);
			playingWidget.setFontId(NORMAL_FONT);
			playingWidget.setHasListener(false);
			playingWidget.setOnMouseRepeatListener((Object[]) null);
			actualCurTrack = null;
		});
	}

	public void chatMsg(String... msgs) {
		clientThread.invoke(() -> Arrays.stream(msgs).forEach(msg -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null)));
	}
}
