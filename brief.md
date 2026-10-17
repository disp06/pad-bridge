Вот готовый, технически детализированный бриф (System Prompt / Context), специально отформатированный для загрузки в [Claude Code](https://claude.com/product/claude-code) (или Claude 3.5 Sonnet). Он содержит все архитектурные ограничения [Wear OS 2.26](https://support.apple.com/en-us/123002), специфику геймпадов 8BitDo и пошаговый план реализации.

Вы можете скопировать текст ниже, сохранить его как gamepad\_bridge\_brief.md и скормить вашему AI-ассистенту.

## ---

**Project Brief: Wear OS Bluetooth Gamepad Bridge**

## **1\. Overview & Objective**

The goal is to develop a lightweight Android application for **Wear OS 2.26+** (running on [TicWatch Pro 3 GPS](https://www.roadtrailrun.com/2020/11/ticwatch-pro-3-gps-review-all.html)) that acts as a **transit bridge** for Bluetooth gamepads.  
The watch will connect to a gamepad as a Bluetooth Host, capture all hardware input events, and forward them to a target device (PC/Smartphone) via a chosen network/transport protocol.

## **2\. Target Device & Hardware Constraints**

> * **Device:** Mobvoi TicWatch Pro 3 GPS.  
> * **Chipset:** Snapdragon Wear 4100\.  
> * **OS Version:** Wear OS 2.26 (based on **Android 9.0 / API Level 28**).  
> * **Crucial OS Limitations:**  
  * Wear OS 2.x stripped out the default Bluetooth pairing UI for generic HID devices. The app **must implement its own Bluetooth scanning and pairing flow** inside the app.  
  * Strict battery optimization: The bridging mechanism must run inside a **Foreground Service** with a persistent notification to prevent the OS from killing the process.  
  * Screen shape: **Round display**. UI must use WearableLinearLayoutManager or ConstraintLayout adapted for circular screens.

## **3\. Supported Controllers (Phase 1 Focus)**

> * [8BitDo SN30](https://www.amazon.com/8Bitdo-Wireless-Gamepad-Original-Transparent-nintendo/dp/B092DXQYDK) and [8BitDo SN30 Pro](https://www.amazon.com/8Bitdo-Bluetooth-Controller-Joysticks-Compatible/dp/B07PHMSDH1).  
> * **Connection Modes:** The controllers will be manually booted into **D-Input (Android Mode: Start \+ B)** or **X-Input (Xbox Mode: Start \+ X)** before pairing with the watch.

## **4\. Architecture & Technical Strategy**

To ensure maximum reliability and bypass Android 9's strict BluetoothHidDevice profile limitations, the architecture is split into two isolated layers:

## **A. Input Layer (Watch ← Gamepad)**

> * The watch pairs with the 8BitDo controller over Classic Bluetooth/BLE.  
> * Input is captured using standard Android Input Framework:  
  * KeyEvent for digital buttons (KEYCODE\_BUTTON\_A, KEYCODE\_BUTTON\_B, KEYCODE\_BUTTON\_X, KEYCODE\_BUTTON\_Y, KEYCODE\_BUTTON\_L1, KEYCODE\_BUTTON\_R1, KEYCODE\_START, KEYCODE\_BUTTON\_SELECT).  
  * MotionEvent for analog sticks and D-pad axes (AXIS\_X, AXIS\_Y, AXIS\_Z, AXIS\_RZ, AXIS\_HAT\_X, AXIS\_HAT\_Y).

## **B. Output Layer (Watch → Target Server)**

> * For Phase 1, data transmission will use **UDP/TCP sockets over Wi-Fi** or a **custom Bluetooth RFCOMM SPP channel** to a lightweight companion server running on the target machine (PC/Phone).  
> * Data payload: A compact, low-latency JSON or byte-array packet representing the current state of all buttons and axes.

## **5\. Component Breakdown**

## **1\. Bluetooth Connection Manager (BTManager)**

> * Requests ACCESS\_FINE\_LOCATION and Bluetooth permissions.  
> * Discovers nearby Bluetooth devices.  
> * Connects and bonds with the 8BitDo controller programmatically.

## **2\. The Core Bridge Service (BridgeService)**

> * A **Foreground Service** maintaining the network socket to the server.  
> * Intercepts system-wide Input Events when the activity is active, or captures them via an invisible overlay/accessibility-like vector if background capture is required (Note: Phase 1 can rely on Active Activity focus for simplicity).

## **3\. Wear OS UI (MainActivity)**

> * Minimalist, high-contrast round UI.  
> * **View 1:** Connection Status (Gamepad status, Server status).  
> * **View 2:** Discovery list to select and pair the 8BitDo pad.  
> * **View 3:** Configuration (IP Address/Port inputs for the target server).

## **6\. Tech Stack**

> * **Language:** Kotlin  
> * **Build System:** Gradle (Min SDK: 28, Target SDK: 28/30)  
> * **Libraries:** AndroidX Wear, Wearable Support Library, Kotlin Coroutines for async networking.

## ---

**7\. Execution Plan for Claude Code (Step-by-Step Prompting)**

When working on this project, we will execute it in the following strict order:

> * **Step 1: Input Prototyping.** Create a basic Android Activity that logs all onKeyDown, onKeyUp, and onGenericMotionEvent data to verify how the 8BitDo SN30 mappings are registered by Android 9\.  
> * **Step 2: Custom Bluetooth Pairing UI.** Build the Bluetooth scanner and bonding module specifically optimized for Wear OS settings constraints.  
> * **Step 3: Networking & Service.** Implement the Foreground Service and the UDP/TCP client socket to stream the byte array of inputs with minimum latency.  
> * **Step 4: Wear OS UI Polish.** Wrap the logic into a clean, round-screen compliant user interface.

## ---

**Что делать дальше с этим брифом?**

> 1. Запустите сессию в **Claude Code** в пустой папке проекта.  
> 2. Отправьте этот бриф первым сообщением со словами: *"Here is the complete project brief for our Wear OS app. Let's start with Step 1: Create a basic Android Studio project structure with Kotlin and write the input prototyping activity to catch KeyEvent and MotionEvent."*  
> 3. Claude сразу начнет генерировать правильную структуру под нужную версию API (28).