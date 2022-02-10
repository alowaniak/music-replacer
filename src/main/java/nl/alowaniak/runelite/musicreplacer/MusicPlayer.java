package nl.alowaniak.runelite.musicreplacer;

import com.adonax.audiocue.AudioCue;
import lombok.SneakyThrows;

import java.net.URI;

public interface MusicPlayer {

    static MusicPlayer create(URI media)
    {
        return new AudioCuePlayer(media);
    }

    void play();

    boolean isPlaying();

    void setVolume(double volume);

    default void close() {}

    class AudioCuePlayer implements MusicPlayer
    {
        private final AudioCue audioCue;

        @SneakyThrows
        private AudioCuePlayer(URI media)
        {
            audioCue = AudioCue.makeStereoCue(media.toURL(), 1);
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
            audioCue.close();
        }
    }
}
