package birdquiz;

import javax.sound.sampled.*;
import java.io.*;

public class SoundUtil {

    /**
     * Plays a short sound effect.
     * Looks for the file in:
     *  1) classpath: /sounds/<name>
     *  2) filesystem: ./sounds/<name>
     *  3) filesystem: ./<name>
     *
     * Supports non-PCM WAV by decoding to PCM_SIGNED 16-bit on the fly.
     * Each call uses a fresh Clip so overlapping plays are fine.
     */
    public static void playSound(String soundFileName) {
        if (soundFileName == null || soundFileName.isEmpty()) return;

        // 1) classpath /sounds/<name>
        InputStream cp = SoundUtil.class.getResourceAsStream("/sounds/" + soundFileName);
        if (cp != null) {
            playFromStream(cp, soundFileName);
            return;
        }

        // 2) filesystem ./sounds/<name>
        File f1 = new File("sounds", soundFileName);
        if (f1.exists()) {
            try (InputStream fs = new BufferedInputStream(new FileInputStream(f1))) {
                playFromStream(fs, f1.getAbsolutePath());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return;
        }

        // 3) filesystem ./<name>
        File f2 = new File(soundFileName);
        if (f2.exists()) {
            try (InputStream fs = new BufferedInputStream(new FileInputStream(f2))) {
                playFromStream(fs, f2.getAbsolutePath());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return;
        }

        System.err.println("[SoundUtil] Sound not found: " + soundFileName +
                " (looked in classpath:/sounds, ./sounds, and ./)");
    }

    private static void playFromStream(InputStream raw, String tag) {
        try (BufferedInputStream in = new BufferedInputStream(raw);
             AudioInputStream aisOrig = AudioSystem.getAudioInputStream(in)) {

            // Decode to PCM_SIGNED 16-bit if necessary
            AudioFormat base = aisOrig.getFormat();
            AudioFormat pcm16 = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(),
                    16,
                    base.getChannels(),
                    base.getChannels() * 2,
                    base.getSampleRate(),
                    false // little-endian
            );
            AudioInputStream aisPcm = AudioSystem.getAudioInputStream(pcm16, aisOrig);

            Clip clip = AudioSystem.getClip();
            clip.open(aisPcm);

            // Auto-release line when finished (prevents “too many open lines”)
            clip.addLineListener(ev -> {
                if (ev.getType() == LineEvent.Type.STOP || ev.getType() == LineEvent.Type.CLOSE) {
                    try { clip.close(); } catch (Exception ignore) {}
                }
            });

            clip.start();

        } catch (UnsupportedAudioFileException e) {
            System.err.println("[SoundUtil] Unsupported audio: " + tag);
            e.printStackTrace();
        } catch (LineUnavailableException e) {
            System.err.println("[SoundUtil] Mixer line unavailable for: " + tag);
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("[SoundUtil] IO error: " + tag);
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            // thrown if conversion to PCM not supported
            System.err.println("[SoundUtil] Could not convert to PCM for: " + tag);
            e.printStackTrace();
        }
    }

    // Simple manual test:
    public static void main(String[] args) {
        // Try: java birdquiz.SoundUtil ui-click.wav
        String name = args.length > 0 ? args[0] : "ui-click.wav";
        playSound(name);
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
    }
}
