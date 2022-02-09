package nl.alowaniak.runelite.musicreplacer;

import com.adonax.audiocue.AudioCue;
import lombok.SneakyThrows;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.music.MusicConfig;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class MusicPlayer {
    private static final int MUSIC_LOOP_STATE_VAR_ID = 4137;
    private static final double MAX_VOL = 255;

    @Inject
    private MusicConfig musicConfig;

    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;

    private AudioCue audioCue;
    private TrackOverride currentTrack;

    @SneakyThrows
    public void play(TrackOverride newTrack) {
        if (!Objects.equals(currentTrack, newTrack))
        {
            stopPlaying();
            currentTrack = newTrack;
            if (currentTrack != null)
            {
                client.setMusicVolume(0);
                audioCue = AudioCue.makeStereoCue(newTrack.getPath().toUri().toURL(), 1);
                audioCue.open();
                audioCue.play(0);
            }
            else
            {
                clientThread.invokeLater(() -> client.setMusicVolume(musicConfig.getMusicVolume() - 1));
            }
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

    private double oldVolume = -1;
    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        if (audioCue == null || currentTrack == null) return;

        // Setting the music volume to 0 with invokeLater seems to prevent the original music from coming through
        // I'm guessing because it depends on "where" in the client loop the vol is 0
        // And with invokeLater it happens to "overwrite" Music plugin's write at the correct "point" in the client loop
        clientThread.invokeLater(() -> client.setMusicVolume(0));

        double volume = (musicConfig.getMusicVolume() - 1) / MAX_VOL;
        if (audioCue.getIsActive(0))
        {
            audioCue.setVolume(0, volume);
            if (volume == 0)
            {
                // Mimic osrs behaviour where volume 0 stops track and turning volume up again restarts it
                audioCue.releaseInstance(0);
            }
        }
        else if (volume != 0 && (oldVolume == 0 || client.getVarbitValue(MUSIC_LOOP_STATE_VAR_ID) == 1))
        {
            // If song ended (audio cue not active) we'll want to restart
            // if we've got LOOP on or when oldVolume was 0 (so switched from off to on)
            audioCue.play();
        }
        oldVolume = volume;
    }
}
