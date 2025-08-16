### A Guide to System Testing for the Multi-Modal Platform

System testing treats the entire platform—the PC Controller, multiple Android Sensor Nodes, and all associated
hardware—as a single entity. Its purpose is to validate the fully assembled system against the functional and
non-functional requirements defined in the project's design documents.[1, 1] This is the final step in verifying that
the platform is not just a collection of working parts, but a robust, reliable, and usable research tool.

#### **1. Scope and Objectives**

The primary objective of system testing is to evaluate the platform's behavior in scenarios that mimic real-world
research use. This involves assessing its performance, reliability, and usability under operational conditions. The key
goals are to:

* **Validate Performance and Scalability:** Ensure the system can handle the load of multiple devices over extended
  periods without degradation or data loss (NFR1, NFR7).[1]
* **Assess Reliability and Recovery:** Test the system's resilience to common real-world failures, such as network
  instability or device issues, and verify its recovery mechanisms (NFR3, FR8).[1]
* **Evaluate the End-to-End Data Pipeline:** Confirm the integrity of the entire data workflow, from sensor capture on
  the Android devices to final, analysis-ready data export on the PC (NFR4, FR10).[1, 1]
* **Verify Usability:** Ensure that a target user (e.g., a researcher with limited technical expertise) can successfully
  operate the system to conduct an experiment (NFR6).[1]
* **Confirm Security:** Verify that the specified security protocols are active and protecting data in transit (
  NFR5).[1, 1]

#### **2. Methodology and Test Environment**

System testing requires a complete, production-like environment that mirrors the setup for an actual research study.

* **Test Environment Setup:**
    * A host PC running the final, packaged `pc_controller` application.
    * A realistic number of Android smartphones (e.g., 4 to 8 devices) running the final, release-signed
      `android_sensor_node` APK.
    * A standard Wi-Fi network (not an idealized, high-performance test network).
    * A full complement of hardware sensors, including both wired and wireless Shimmer3 GSR+ units and Topdon TC001
      thermal cameras.
* **Tools and Frameworks:**
    * Execution is primarily manual, guided by detailed test plans and use-case scenarios.
    * Monitoring tools (e.g., Windows Task Manager, Android Studio Profiler) are used to observe system resource
      utilization (CPU, memory) during endurance tests.
    * Network analysis tools (e.g., Wireshark) are used for security validation.
    * The custom Python script (`validate_sync.py`) is used for the final, empirical analysis of temporal
      synchronization.

#### **3. Key System Test Scenarios**

The following test cases are designed to provide a comprehensive evaluation of the platform's readiness for deployment.

**Test Case 1: Endurance and Load Test**

* **Objective:** To validate the system's stability and performance over an extended period with a realistic number of
  connected devices (NFR1, NFR7).[1]
* **Procedure:**
    1. Connect the maximum number of supported Android Spokes (e.g., eight devices) to the PC Hub.
    2. Start a synchronized recording session with all sensors on all devices enabled.
    3. Let the session run continuously for at least two hours, simulating a long experiment.
    4. During the session, monitor the CPU and memory usage of the PC Controller and a sample of the Android devices.
    5. After two hours, stop the session and allow all data to transfer.
* **Expected Outcome:**
    * The system must remain stable throughout the entire two-hour period, with no crashes or unhandled exceptions on
      the PC or any Android device.
    * Resource utilization (memory, CPU) should remain stable and not show signs of a memory leak (i.e., continuously
      increasing memory usage over time).
    * All data from the two-hour session must be successfully recorded, transferred, and aggregated without any loss or
      corruption.

**Test Case 2: Usability and User Experience Test (FR6, NFR6)**

* **Objective:** To evaluate whether a representative end-user can operate the system effectively with minimal training,
  based on the provided user documentation.
* **Procedure:**
    1. Provide a test subject (ideally someone unfamiliar with the system's development, such as a student or colleague)
       with the `User_Manual.md`.
    2. Ask them to perform a complete workflow: set up the hardware, install and launch the applications, run a short (
       5-minute) recording session with two Android devices, and use the playback tool to review the data.
    3. Observe the user's interactions with the system, noting any points of confusion, difficulty, or unexpected
       behavior.
* **Expected Outcome:**
    * The user should be able to complete the entire workflow successfully using only the provided documentation.
    * The GUI should be intuitive, with clear labels and status indicators.
    * The user's feedback should be collected to identify any areas where the user experience could be improved.

**Test Case 3: Recovery and Fault Tolerance Test (Chaos Test) (FR8, NFR3)**

* **Objective:** To verify the system's resilience to common, real-world failures and its ability to recover gracefully.
* **Procedure:**
    1. Start a synchronized recording session with at least three Android Spokes.
    2. During the session, simulate the following failures one at a time:
        * **Network Interruption:** Temporarily disconnect one Android device from the Wi-Fi network for 30 seconds,
          then reconnect it.
        * **Application Crash:** Manually force-close the `android_sensor_node` application on one device, then relaunch
          it.
        * **Device Failure:** Power off one Android device completely.
    3. Continue the recording for at least one minute after each simulated failure.
    4. Stop the session and analyze the results.
* **Expected Outcome:**
    * In all scenarios, the PC Hub and the other, unaffected Android devices must continue to operate normally.
    * The PC GUI must correctly update the status of the affected device to "Offline" and back to "Online" upon
      recovery.
    * The device that experienced a temporary network interruption or app crash must successfully rejoin the session
      upon recovery and transfer its complete data file at the end.
    * No data from the unaffected devices should be lost. Data from the failed devices should be preserved up to the
      point of failure.

**Test Case 4: Security Validation (NFR5)**

* **Objective:** To verify that the communication between the PC Hub and Android Spokes is encrypted.
* **Procedure:**
    1. Use a network sniffing tool like Wireshark to monitor the network traffic between the PC and an Android device.
    2. Start a session and observe the packets being exchanged.
* **Expected Outcome:**
    * The content of the TCP packets should be unreadable, consistent with TLS encryption. Plaintext JSON commands
      should **not** be visible in the packet capture.[1] This confirms that the security requirement has been met.

By successfully completing this suite of system tests, the platform's readiness for deployment in real-world research
environments is confirmed. This final validation step ensures that the system is not only functional but also reliable,
usable, and secure.