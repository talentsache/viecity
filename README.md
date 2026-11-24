<p align="center">
  <img src="branding/viecity-logo.png" width="320" alt="Vie City">
</p>

# VIE City VR

Play your PS5 on a Meta Quest with the Touch controllers in your hands and your head as the camera.

VIE City VR streams the PS5 picture onto a large curved screen in VR and tells the console that a DualSense is connected. You do not need a DualSense, a PC or a jailbreak. It was built for one thing: sitting inside the cars of Vice City.

It is a fork of [chiaki-ng](https://github.com/streetpea/chiaki-ng), the open source Remote Play client. All the hard protocol work is theirs. This fork adds the Quest layer on top of the Android app.

> **Status: early.** Tested on a Quest 3 with a PS5 in the same Wi-Fi. It works, and it is rough. Expect to tune things.

## What it does

- **Curved screen in VR.** The stream is decoded straight into the screen, with no extra copy in between.
- **Touch controllers as a DualSense.** Sticks, buttons, triggers and grips are mapped, and everything the Touch controllers lack is reachable through the left menu button.
- **Head as camera.** Turning your head moves the in-game camera through the right stick.
- **Aim with your hand.** While you hold the left trigger, the right controller steers the camera instead of your head.
- **First-person button.** One combo plays the button sequence for the first-person glitch with the exact timing.
- **Help where you need it.** Hold the left menu button and a panel under the screen shows every combo.
- **Rumble.** PS5 rumble arrives in the Touch controllers.
- **Sign in with PlayStation.** The app fetches your Account ID for pairing, so there is nothing to look up or type.
- **Quick start.** With one paired console the app connects as soon as it opens.

## What it is not

This is not real VR inside the game. The picture is flat, there is no depth, and your hands do not appear in the game world. That would need code inside the game, and a console does not allow that. VIE City VR only replaces the screen and the controller.

## Install

1. Turn on developer mode for your Quest in the Meta Horizon phone app.
2. Download the APK from the [releases](../../releases).
3. Install it with [SideQuest](https://sidequestvr.com) or with `adb install viecity.apk`.
4. Start it from the library under **Unknown Sources**.

## First start

1. On the PS5 open **Settings → System → Remote Play**, turn on Remote Play and choose **Link Device**. The console shows an eight digit PIN.
2. In the app tap **+** and then **Register Console**.
3. Tap **Sign in with PlayStation** and log in. Your password only goes to Sony. The Account ID is filled in for you.
4. Enter the PIN and tap **Register**.
5. Tap the console in the list. The view switches to VR.

The pairing is kept. From then on the app connects by itself when you open it. You can turn that off in the settings under **Quick start**.

## Controls

| Touch controller | DualSense |
|---|---|
| Left stick, right stick | Left stick, right stick |
| Stick click | L3, R3 |
| A, B | Cross, Circle |
| X, Y | Square, Triangle |
| Triggers | L2, R2 |
| Grip buttons | L1, R1 |
| Left menu button, short press | Options |

Hold the **left menu button** for everything else. While you hold it, a panel under the screen lists these combos:

| Menu + … | Does |
|---|---|
| Left stick | D-pad |
| Right stick click | Touchpad button |
| B | PS button |
| Left grip | Create (Share) |
| A | First-person button sequence |
| Right trigger | Put the screen back in front of you |
| Right grip | Switch the head camera: follow, peek, off |
| Right stick up or down | Screen smaller or larger |
| Left trigger | Leave the stream, back to the console list and the settings |

## Head camera modes

- **Follow.** Every turn of your head turns the camera and the screen stays in front of you. Good for looking around on foot.
- **Peek.** The screen stays where it is. Small head movements just look across the picture. A clear look to the side holds the camera there, and it returns when you look back. Good for driving.
- **Off.** The screen stays where it is and your head controls nothing.

## First person in GTA 6

GTA 6 has no first-person view. Players found a glitch that locks the camera inside the car, and the timing is tight. The app presses the buttons for you.

1. In the game settings change the **touchpad button** from **Map** to **Camera**.
2. Sit in a car with the phone put away.
3. Hold the left menu button and press **A**.

The app raises the phone, puts it away and presses the camera button 150 ms after the circle press starts. That timing was measured on the game on 24 November 2026.

Things to know:

- The only known way back to the normal camera is loading a save. Save by hand first.
- Rockstar can close the glitch with any update. Then the button does nothing.
- Use it in single player. Automated input may break the rules online.

## Tuning without rebuilding

With the Quest on a cable you can change values while the stream runs:

```sh
# head camera: degrees per second for full stick, dead zone, minimum stick, smoothing
adb shell am broadcast -a com.talentsache.viecity.TUNE --es speed 110 --es dead 2.5 --es min 0.30 --es smooth 0.25

# screen: radius in metres, arc in radians, height offset in metres, brightness of the surroundings 0 to 1
adb shell am broadcast -a com.talentsache.viecity.TUNE --es radius 2.0 --es angle 2.2 --es height 0 --es ambient 0.75

# head camera mode (follow, peek, off), peek dead zone and full deflection in degrees, follow lag in seconds
adb shell am broadcast -a com.talentsache.viecity.TUNE --es mode peek --es peekdead 12 --es peekfull 38 --es tau 0.18

# play any button sequence: letters are buttons, numbers are milliseconds
adb shell am broadcast -a com.talentsache.viecity.TUNE --es macro "U:150,_:1800,O:100,_:50,T:80"
```

Macro letters: `U D L R` d-pad, `X` cross, `O` circle, `S` square, `Y` triangle, `T` touchpad, `P` options, `1` L1, `2` R1, `_` nothing. Letters can be combined, for example `OT:60`.

## Build

You need the Android SDK with NDK 27.1, CMake 3.22, JDK 17, Python 3 with `protobuf`, and `protoc` on your path.

```sh
git clone --recurse-submodules https://github.com/talentsache/viecity.git
cd viecity/android
./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/`. The build covers `arm64-v8a` only, which is what the Quest uses.

## Where the Quest code lives

Everything this fork adds is in the Android app:

| File | What it holds |
|---|---|
| `android/app/src/main/cpp/viecity-xr.cpp` | OpenXR session, curved screen, controller and head tracking |
| `android/app/src/main/java/com/metallic/chiaki/vr/VrInputMapper.kt` | Mapping to the DualSense, head camera, button sequences |
| `android/app/src/main/java/com/metallic/chiaki/vr/VrStreamActivity.kt` | VR stream view and the tuning receiver |
| `android/app/src/main/java/com/metallic/chiaki/regist/PsnLoginActivity.kt` | Sign in with PlayStation for the Account ID |

The rest of the tree is chiaki-ng as it is upstream, so their fixes can be merged.

## Credits

- [chiaki-ng](https://github.com/streetpea/chiaki-ng) by Street Pea and contributors, and the original [Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) by Florian Märkl. This project would not exist without them.
- The first-person glitch was found by oneboyler and OrangeGuy.

## License

AGPL-3.0-only with the OpenSSL exception, the same as chiaki-ng. See [COPYING](COPYING) and [LICENSES](LICENSES). If you pass on a changed version, you have to pass on its source too.

## Disclaimer

VIE City VR is a fan project. It is not affiliated with or endorsed by Sony Interactive Entertainment, Rockstar Games, Take-Two Interactive or Meta. PlayStation, PS5, DualSense, Grand Theft Auto, Meta Quest and all related names belong to their owners. The project contains no game files, artwork or code from any of them.
