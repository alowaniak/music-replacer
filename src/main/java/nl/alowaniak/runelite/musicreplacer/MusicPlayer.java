package nl.alowaniak.runelite.musicreplacer;

import com.adonax.audiocue.AudioCue;
import com.google.common.collect.ImmutableMap;
import jaco.mp3.player.MP3Player;
import lombok.SneakyThrows;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.Function;

public interface MusicPlayer {

    static void preloadNecessaries() {
        // Jaco's MP3Player does some init that needs to happen on the UI thread
        SwingUtilities.invokeLater(JacoPlayer.player::toString);
    }

    ImmutableMap<String, Function<URI, MusicPlayer>> PLAYER_PER_EXT = ImmutableMap.of(
            ".mp3", JacoPlayer::new,
            ".wav", AudioCuePlayer::new
    );

    static MusicPlayer create(URI media)
    {
        for (Map.Entry<String, Function<URI, MusicPlayer>> extAndPlayer : PLAYER_PER_EXT.entrySet()) {
            if (media.getPath().endsWith(extAndPlayer.getKey()))
                try {
                    return extAndPlayer.getValue().apply(media);
                } catch (Exception e) {
                    LoggerFactory.getLogger(MusicPlayer.class).warn("Couldn't load player for " + media, e);
                }
        }
        return null;
    }

    void play();

    boolean isPlaying();

    void setVolume(double volume);

    default void close() {}

    class JacoPlayer implements MusicPlayer {

        public static final MP3Player player = new MP3Player();
        private final File tempPlayFile; // A hacky solution for overriding/deleting current playing song

        @SneakyThrows
        public JacoPlayer(URI mediaFile) {
            player.getPlayList().clear();
            tempPlayFile = File.createTempFile("tmpJacoPlayfile", ".mp3");
            tempPlayFile.deleteOnExit();
            Files.copy(new File(mediaFile).toPath(), tempPlayFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            player.add(tempPlayFile);
        }

        @Override
        public void play() {
            player.play();
        }

        @Override
        public boolean isPlaying() {
            return player.isPlaying();
        }

        @Override
        public void setVolume(double volume) {
            int intVol = (int) (volume * 100);
            if (volume > 0 && intVol == 0) intVol = 1;
            player.setVolume(intVol);
        }

        @Override
        public void close() {
            player.stop();
            tempPlayFile.delete();
        }
    }

    class AudioCuePlayer implements MusicPlayer
    {
        // Same hacky solution as JaCo even though it shouldn't happen here cause audiocue fully loads in memory
        private final File tempPlayFile;
        private final AudioCue audioCue;

        @SneakyThrows
        private AudioCuePlayer(URI media)
        {
            tempPlayFile = File.createTempFile("tmpJacoPlayfile", ".mp3");
            tempPlayFile.deleteOnExit();
            Files.copy(new File(media).toPath(), tempPlayFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            audioCue = AudioCue.makeStereoCue(tempPlayFile.toURL(), 1);
            audioCue.open();
        }

        @Override
        public void play()
        {
            if (audioCue.getIsActive(0)) audioCue.releaseInstance(0);
            audioCue.play();
        }

        @Override
        public boolean isPlaying()
        {
            return audioCue.getIsActive(0);
        }

        @Override
        public void setVolume(double volume)
        {
            audioCue.setVolume(0, volume);
        }

        @Override
        public void close()
        {
            tempPlayFile.delete();
            audioCue.close();
        }
    }
}
