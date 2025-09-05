#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Arduino.h>

// OLED display settings
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1
#define OLED_ADDR 0x3C

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// Button and LED pin definitions
const int buttonPins[] = {16, 17, 5, 4}; // GPIO 16, 17, 5, 4
const int ledPins[] = {18, 19, 23, 2};   // GPIO 18, 19, 23, 2
const char* buttonNames[] = {"yellow", "blue", "green", "submit"};
const int numButtons = 4;
unsigned long lastDebounceTime[numButtons];
int lastButtonState[numButtons];
int buttonState[numButtons];
const unsigned long debounceDelay = 50;

void initializeOLED() {
  // Initialize the OLED display
  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    Serial.println(F("SSD1306 allocation failed"));
    while (true); // Infinite loop to halt further execution if OLED fails
  }
  display.display();
  delay(2000); // Pause for 2 seconds
  display.clearDisplay();
}

void displayWelcomeMessage() {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.print("Birds of OaksBottom");
  display.setCursor(0, 10);
  display.print("Quiz by");
  display.setCursor(0, 20);
  display.print("Jonathan Swanson");
  display.display();
  Serial.println(F("Displayed welcome message"));
}

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22); // Initialize I2C with specified pins

  initializeOLED(); // Initialize and verify OLED

  displayWelcomeMessage(); // Display welcome message
  delay(2000); // Display welcome message for 2 seconds

  // Initialize buttons and LEDs
  for (int i = 0; i < numButtons; i++) {
    pinMode(buttonPins[i], INPUT_PULLUP);
    pinMode(ledPins[i], OUTPUT);
    digitalWrite(ledPins[i], LOW); // Ensure all LEDs are off initially

    lastButtonState[i] = HIGH;
    buttonState[i] = HIGH;
    lastDebounceTime[i] = 0;
  }

  // Set LEDs to dim state
  for (int i = 0; i < numButtons; i++) {
    analogWrite(ledPins[i], 128); // Set to half brightness (dim state)
  }
}

void loop() {
  handleButtons();    // Check button states and update the OLED accordingly
  handleSerialInput(); // Check for serial input and update the OLED accordingly
}

void handleButtons() {
  for (int i = 0; i < numButtons; i++) {
    int reading = digitalRead(buttonPins[i]);

    if (reading != lastButtonState[i]) {
      lastDebounceTime[i] = millis();
    }

    if ((millis() - lastDebounceTime[i]) > debounceDelay) {
      if (reading != buttonState[i]) {
        buttonState[i] = reading;

        if (buttonState[i] == LOW) {
          Serial.print(F("Button pressed: "));
          Serial.println(buttonNames[i]); // Print button name to Serial Monitor

          // Send the button name to the Serial Port for Java application
          Serial.println(buttonNames[i]);

          // Light up the corresponding LED to full brightness
          analogWrite(ledPins[i], 255); // Full brightness

          // Display button press message on OLED
          updateOLED("Button Pressed", buttonNames[i]);

        } else {
          Serial.print(F("Button released: "));
          Serial.println(buttonNames[i]);

          // Dim the corresponding LED back to half brightness
          analogWrite(ledPins[i], 128); // Half brightness (dim state)
        }
      }
    }

    lastButtonState[i] = reading; // Save the last state
  }
}

void handleSerialInput() {
  if (Serial.available()) {
    String input = Serial.readStringUntil('\n');
    input.trim();

    Serial.print("Received: ");
    Serial.println(input);

    if (input.equals("correct")) {
      updateOLEDWithAnswer("Correct Answer");
    } else if (input.equals("wrong")) {
      updateOLEDWithAnswer("Wrong Answer");
    }
  }
}

void updateOLEDWithAnswer(const char* answer) {
  updateOLED(answer, "");
  Serial.print(F("Updated OLED with answer: "));
  Serial.println(answer);
}

void updateOLED(const char* mainMessage, const char* subMessage) {
  display.clearDisplay();
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.print("Birds of OaksBottom");
  display.setCursor(0, 10);
  display.print("Quiz by");
  display.setCursor(0, 20);
  display.print("Jonathan Swanson");

  if (strlen(mainMessage) > 0) {
    display.setCursor(0, 30);
    display.print(mainMessage);
  }

  if (strlen(subMessage) > 0) {
    display.setCursor(0, 40);
    display.print(subMessage);
  }

  display.display();
  Serial.print(F("Updated OLED with message: "));
  Serial.println(mainMessage);
}