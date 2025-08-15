Excellent. Now that you have the detailed phase-by-phase implementation plan saved, you can craft a very specific and context-rich prompt for Junie. The key is to treat the AI agent like a junior developer you are onboarding: give it a clear task, point it to the documentation, and set explicit expectations.

Here is the prompt I would suggest you use to kick off Phase 1. This prompt is designed to be given to Junie in a new, empty project directory where you have placed your detailed documentation files (`1.tex`, `2.tex`, `3.tex`, `4.tex`).

***

### **Suggested Prompt for Junie**

Act as an expert software architect and developer. Your task is to begin the implementation of the **Multi-Modal Physiological Sensing Platform** by completing **Phase 1** as described in the project documentation I have provided.

**Project Context and Documentation:**
You have access to the following project documents which serve as the single source of truth for this project:
*   `1.tex` (Introduction and Objectives) [1]
*   `2.tex` (Background and Literature Review) [1]
*   `3.tex` (Requirements and Analysis) [1]
*   `4.tex` (Design and Implementation Details) [1]

Your work must strictly adhere to the specifications, architectures, and requirements detailed in these documents.

**Current Task: Execute Phase 1 - Project Scaffolding and Core Communication Layer**

Your objective for this session is to execute all tasks outlined in Phase 1. This includes:

1.  **Project Scaffolding:**
    *   Initialize a Git repository for version control.
    *   Create the complete, separate project skeletons for both the `pc_controller` (Python/PyQt6) and the `android_sensor_node` (Kotlin/MVVM) as specified in the design documents. [1, 1]
    *   Generate the initial directory structures and placeholder files for each application.
    *   Create the `requirements.txt` file for the PC controller with the initial dependencies: `PyQt6` and `zeroconf`.

2.  **Implement Core Communication Layer:**
    *   **PC Hub:** Implement the `NetworkController` module. This must include a TCP/IP server that runs in a background `QThread` and a `ZeroconfServiceBrowser` to discover Android spokes on the network. [1]
    *   **Android Spoke:** Implement the `NetworkClient` module. This must advertise its presence using Android's Network Service Discovery (NSD) and run a `ServerSocket` within a `ForegroundService` to listen for connections from the Hub.

3.  **Implement Initial Handshake:**
    *   Implement the initial command-response cycle based on the JSON protocol defined in the documentation. [1]
    *   Upon a successful connection, the PC Hub must automatically send a `query_capabilities` command.
    *   The Android Spoke must receive this command, gather basic device information, and send a valid JSON response back to the Hub.

**Deliverables for Phase 1:**
Upon completion, the project directory must contain:
*   A Git repository with the initial project structures for both applications.
*   A runnable PC application that successfully discovers and connects to the Android application.
*   A runnable Android application that successfully advertises itself and accepts a connection.
*   Console logs on both the PC and Android devices demonstrating a successful two-way exchange of the `query_capabilities` JSON messages.

Please create an execution plan to accomplish these tasks. I will monitor your progress and approve necessary actions.