### A Guide to Unit Testing for the Multi-Modal Platform

Unit testing is a software development process where the smallest testable parts of an application, called "units," are
individually and independently scrutinized for proper operation. A unit is typically a single function or method within
a class. The primary goal of unit testing is to validate that each unit of the software performs as designed, which
helps to catch bugs early in the development cycle, facilitate changes, and improve code quality.

In the context of this project, which involves two distinct codebases (Python for the PC Hub and Kotlin for the Android
Spoke), a tailored approach using the standard testing frameworks for each ecosystem is essential.

#### **Python Unit Testing (PC Controller)**

For the Python-based PC Controller, two primary frameworks are widely used: `unittest`, which is part of Python's
standard library, and `pytest`, a popular third-party framework known for its simplicity and powerful features.

**1. The `unittest` Framework**

The `unittest` module, inspired by the JUnit framework from the Java ecosystem, provides a rich set of tools for
constructing and running tests in an object-oriented way.

* **Core Concepts:**

    * **Test Case:** The individual unit of testing, represented by creating a class that inherits from
      `unittest.TestCase`.
    * **Test Fixture:** The preparation needed to perform one or more tests, such as setting up temporary databases or
      starting a server process, and any associated cleanup actions.
    * **Test Suite:** A collection of test cases, test suites, or both, used to aggregate tests that should be executed
      together.
    * **Test Runner:** A component that orchestrates the execution of tests and provides the outcome to the user.

* **Writing a Test Case:**
  A typical test case involves creating a class that inherits from `unittest.TestCase` and adding methods that start
  with the word `test`. Inside these methods, you use various assertion methods to check for expected outcomes.

    * **Assertion Methods:** The `TestCase` class provides several assertion methods to check for conditions. Common
      examples include:
        * `assertEqual(a, b)`: Checks that `a == b`.
        * `assertNotEqual(a, b)`: Checks that `a!= b`.
        * `assertTrue(x)`: Checks that `bool(x)` is `True`.
        * `assertFalse(x)`: Checks that `bool(x)` is `False`.
        * `assertRaises(exc, func, *args, **kwds)`: Checks that `func(*args, **kwds)` raises the exception `exc`.

* **Running Tests:**
  Tests can be run directly from the command line, which is particularly useful for automation and continuous
  integration pipelines. The test discovery mechanism can find tests in modules and packages.

  ```bash
  # Run tests in a specific module
  python -m unittest test_module

  # Run a specific test class
  python -m unittest test_module.TestClass

  # Run with higher verbosity
  python -m unittest -v test_module
  ```

**2. The `pytest` Framework**

`pytest` is a mature and feature-rich testing framework that is often preferred for its simpler syntax and powerful
features, such as its advanced fixture system and intelligent assertion reporting.

* **Key Features:**

    * **Plain Assert Statements:** `pytest` allows you to use standard Python `assert` statements, which are less
      verbose than the `self.assertEqual()` style methods in `unittest`. When an assertion fails, `pytest` provides
      detailed introspection reports that show the intermediate values of the expression, making it easier to diagnose
      the failure.
    * **Test Discovery:** By default, `pytest` automatically discovers tests in files named `test_*.py` or `*_test.py`,
      and within those files, it runs functions and methods prefixed with `test_`.
    * **Fixtures:** `pytest` has a powerful fixture system that allows you to define and reuse setup and teardown code
      for your tests. For example, you can request a unique temporary directory for a test simply by including
      `tmp_path` as an argument in your test function signature.

* **Writing a Test Case:**
  With `pytest`, tests can be simple functions, or they can be grouped into classes for better organization. If using a
  class, the class name must be prefixed with `Test`.

  ```python
  # content of test_example.py
  class TestMyComponent:
      def test_one(self):
          x = "this"
          assert "h" in x

      def test_two(self):
          x = "hello"
          assert hasattr(x, "check") # This will fail
  ```

#### **Android Unit Testing (Sensor Spoke)**

For the Kotlin-based Android application, unit tests are typically written using `JUnit` and can be run either on a
local JVM or on a device/emulator. For tests that have dependencies on the Android framework, **Robolectric** is the
industry-standard tool.

**1. The `JUnit` Framework**

JUnit is the programmer-friendly testing framework for Java and the JVM, providing a modern foundation for
developer-side testing. For the Android project, local unit tests are placed in the `app/src/test/` directory and are
executed on your local machine's JVM. These tests are fast and are ideal for testing components that have no
dependencies on the Android framework (e.g., pure Kotlin logic in a ViewModel or a data parsing utility).

**2. The `Robolectric` Framework**

When a unit test needs to interact with the Android framework (e.g., testing an Activity's lifecycle or accessing
resources), running it on a local JVM would normally fail. Robolectric solves this problem by providing a **simulated
Android environment that runs directly inside the JVM**, without the overhead of an emulator.

* **Key Advantages:**

    * **Speed:** Robolectric tests are significantly faster than instrumented tests that run on an emulator or physical
      device, allowing for rapid feedback during development.
    * **Framework Simulation:** It handles the inflation of `View`s, resource loading, and simulates many other parts of
      the Android framework that are normally implemented in native C code.
    * **Test APIs:** It extends the Android framework with test APIs that provide fine-grained control over the
      environment, allowing you to simulate specific conditions for your tests.

* **Writing a Robolectric Test:**
  To use Robolectric, you add its dependencies to your `build.gradle.kts` file and annotate your test class with
  `@RunWith(AndroidJUnit4.class)`. You can then use Robolectric's APIs to create and control Android components like
  Activities.

  ```kotlin
  // Example of a simple Robolectric test
  @RunWith(AndroidJUnit4::class)
  class MyActivityTest {
      @Test
      fun clickingButton_shouldChangeMessage() {
          val controller = Robolectric.buildActivity(MyActivity::class.java)
          controller.setup() // Moves the Activity to the RESUMED state
          val activity = controller.get()

          activity.findViewById<Button>(R.id.button).performClick()
          val textView = activity.findViewById<TextView>(R.id.text)
          assertEquals("Robolectric Rocks!", textView.text.toString())
      }
  }
  ```

By leveraging these standard testing frameworks, the project can ensure that both the PC Controller and the Android
Sensor Node are built on a foundation of reliable, verifiable code, which is essential for developing a research-grade
data acquisition platform.