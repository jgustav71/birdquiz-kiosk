[README.md](https://github.com/user-attachments/files/22194141/README.md)
# Bird Quiz (OMSI Kiosk)

An interactive educational kiosk built for the **Oregon Museum of Science and Industry (OMSI) Science Fair**.  
It teaches kids about local birds using a touchscreen GUI, arcade buttons with lights, sounds, and images.

---

## 🐦 Features
- Java Swing GUI quiz with images, sounds, and MySQL database.
- Arcade-style buttons and lights powered by an **ESP32** microcontroller.
- Fullscreen kiosk mode with score tracking.
- Serial communication between ESP32 and Java:
  - ESP32 sends button presses (`yellow`, `blue`, `green`, `submit`).
  - Java sends quiz feedback (`correct`, `wrong`, `ledSequence`).
- Resources (images, sounds, buttons) loaded directly from JAR for easy deployment.

---


## 📂 Project Layout
birdquiz/
arduino/
birdquiz_esp32.ino # ESP32 firmware
src/
main/
java/birdquiz/ # Java source (Swing quiz logic)
resources/ # Images, sounds, buttons
pom.xml # Maven build file
README.md



---

## ⚙️ Requirements
- Java 11+
- Maven 3.8+
- MySQL 8.x (database `birds_db` with quiz tables + `quiz_results`)
- Arduino IDE (ESP32 board support installed)

---

## 🚀 Build & Run (Java)

### Package a runnable JAR
```bash
mvn clean package
java -jar target/birdquiz-1.0-SNAPSHOT-jar-with-dependencies.jar



mvn exec:java -Dexec.mainClass=birdquiz.BirdQuizGUI



mvn exec:java -Dexec.mainClass=birdquiz.BirdQuizGUI


🔌 ESP32 Firmware

Open arduino/birdquiz_esp32.ino in Arduino IDE.

Set Board: ESP32 Dev Module (or your ESP32 variant).

Set Port: (select your ESP32 COM port).

Click Upload.

Serial Protocol

ESP32 → PC (button press):

yellow
blue
green
submit


PC → ESP32 (quiz feedback):

correct
wrong
ledSequence

🖼️ Resources

Place your assets under src/main/resources/:

images/
  songbirds/
  ducks/
  raptors/
sounds/
  right_answer.wav
  wrong_answer.wav
  bird_nerd.wav
  submit_button_pressed.wav
buttons/
  blue.png
  blue_selected.png
  green.png
  green_selected.png
  yellow.png
  yellow_selected.png
  white.png
  white_selected.png


⚠️ Database only stores filenames (e.g., bird.jpg, sound.wav).
The Java app looks them up inside these folders.

🧪 Development Notes

Serial auto-detect is supported — the app will attempt to find an ESP32 COM port automatically.

Quiz results are stored both in MySQL (quiz_results) and locally in session memory.

Designed for fullscreen kiosk mode at OMSI.

🙌 Credits

Developed by Jonathan Swanson for the OMSI Science Fair in Portland, Oregon.
Built with Java, Maven, and ESP32.
