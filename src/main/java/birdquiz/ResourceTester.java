package birdquiz;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;

/**
 * A utility class to test if resources (images and sounds) can be found in your project.
 * This can help diagnose resource loading issues in your Bird Quiz application.
 * 
 * To use this class:
 * 1. Add it to your project in the birdquiz package
 * 2. Run the main method
 * 3. Check the console output to see which resources were found and which were not
 */
public class ResourceTester {

    public static void main(String[] args) {
        System.out.println("=== Bird Quiz Resource Tester ===");
        System.out.println("Testing resource loading to diagnose image and sound issues");
        System.out.println();
        
        // Test button images
        System.out.println("=== Testing Button Images ===");
        testResource("buttons/blue.png");
        testResource("buttons/green.png");
        testResource("buttons/yellow.png");
        testResource("buttons/blue_selected.png");
        testResource("buttons/green_selected.png");
        testResource("buttons/yellow_selected.png");
        testResource("buttons/white.png");
        testResource("buttons/white_selected.png");
        
        // Test sound files
        System.out.println("\n=== Testing Sound Files ===");
        testResource("sounds/radio_button_selected.wav");
        testResource("sounds/submit_button_pressed.wav");
        testResource("sounds/right_answer.wav");
        testResource("sounds/wrong_answer.wav");
        testResource("sounds/bird_nerd.wav");
        
        // Test bird images from different categories
        System.out.println("\n=== Testing Bird Images ===");
        System.out.println("Note: These are example paths. Replace with your actual image paths.");
        
        // Test songbirds
        testResource("images/songbirds/robin.jpg");
        testResource("songbirds/robin.jpg");
        
        // Test raptors
        testResource("images/raptors/eagle.jpg");
        testResource("raptors/eagle.jpg");
        
        // Test ducks
        testResource("images/ducks/mallard.jpg");
        testResource("ducks/mallard.jpg");
        
        // Test with your actual image paths from database
        System.out.println("\n=== Testing Your Specific Image Paths ===");
        System.out.println("Add your actual image paths here to test them");
        // Example: testResource("your_actual_image_path_from_database.jpg");
        
        System.out.println("\n=== Resource Test Complete ===");
        System.out.println("For any resources that failed to load, check:");
        System.out.println("1. If the file exists in the correct location");
        System.out.println("2. If the path is correct");
        System.out.println("3. If the file is included in your JAR file");
    }
    
    /**
     * Tests if a resource can be found using different loading methods
     * 
     * @param resourcePath The path to the resource to test
     */
    private static void testResource(String resourcePath) {
        System.out.println("\nTesting resource: " + resourcePath);
        
        // Try different path variations
        String[] pathVariations = {
            resourcePath,           // As provided
            "/" + resourcePath,     // With leading slash
            resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath, // Without leading slash
            "src/main/resources/" + resourcePath,  // With src/main/resources prefix
            "/src/main/resources/" + resourcePath  // With /src/main/resources prefix
        };
        
        boolean found = false;
        
        // Test with getResource
        for (String path : pathVariations) {
            URL url = ResourceTester.class.getResource(path);
            if (url != null) {
                System.out.println("✓ Found with getResource(): " + path);
                System.out.println("  URL: " + url);
                found = true;
                break;
            }
        }
        
        if (!found) {
            System.out.println("✗ Not found with getResource()");
        }
        
        // Test with getResourceAsStream
        found = false;
        for (String path : pathVariations) {
            try (InputStream is = ResourceTester.class.getResourceAsStream(path)) {
                if (is != null) {
                    System.out.println("✓ Found with getResourceAsStream(): " + path);
                    found = true;
                    
                    // Try to load as image or sound based on extension
                    if (path.toLowerCase().endsWith(".png") || path.toLowerCase().endsWith(".jpg") || 
                        path.toLowerCase().endsWith(".jpeg") || path.toLowerCase().endsWith(".gif")) {
                        try {
                            BufferedImage img = ImageIO.read(ResourceTester.class.getResourceAsStream(path));
                            if (img != null) {
                                System.out.println("  Successfully loaded as image: " + img.getWidth() + "x" + img.getHeight());
                            } else {
                                System.out.println("  Failed to load as image (null BufferedImage)");
                            }
                        } catch (Exception e) {
                            System.out.println("  Failed to load as image: " + e.getMessage());
                        }
                    } else if (path.toLowerCase().endsWith(".wav")) {
                        try {
                            AudioInputStream audioStream = AudioSystem.getAudioInputStream(
                                ResourceTester.class.getResourceAsStream(path));
                            if (audioStream != null) {
                                AudioFormat format = audioStream.getFormat();
                                System.out.println("  Successfully loaded as audio: " + 
                                    format.getChannels() + " channels, " + 
                                    format.getSampleRate() + "Hz");
                                audioStream.close();
                            }
                        } catch (Exception e) {
                            System.out.println("  Failed to load as audio: " + e.getMessage());
                        }
                    }
                    
                    break;
                }
            } catch (IOException e) {
                System.out.println("Error testing resource: " + e.getMessage());
            }
        }
        
        if (!found) {
            System.out.println("✗ Not found with getResourceAsStream()");
        }
        
        // Test with ClassLoader
        found = false;
        for (String path : pathVariations) {
            // Remove leading slash for ClassLoader
            String classLoaderPath = path.startsWith("/") ? path.substring(1) : path;
            
            URL url = ResourceTester.class.getClassLoader().getResource(classLoaderPath);
            if (url != null) {
                System.out.println("✓ Found with ClassLoader.getResource(): " + classLoaderPath);
                System.out.println("  URL: " + url);
                found = true;
                break;
            }
        }
        
        if (!found) {
            System.out.println("✗ Not found with ClassLoader.getResource()");
        }
    }
}