package birdquiz;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public class SoundUtil {

    public static void playSound(String soundFileName) {
        try (InputStream rawIn = SoundUtil.class.getResourceAsStream("/sounds/" + soundFileName)) {
            if (rawIn == null) {
                throw new IOException("Sound resource not found: /sounds/" + soundFileName);
            }
            try (BufferedInputStream in = new BufferedInputStream(rawIn);
                 AudioInputStream audioStream = AudioSystem.getAudioInputStream(in)) {

                Clip clip = AudioSystem.getClip();
                clip.open(audioStream);
                clip.start();
            }
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            e.printStackTrace();
        }
    }
}
