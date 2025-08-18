That's an excellent and forward-thinking question. Instructing the AI agent to handle project maintenance tasks from the
outset is a key practice for ensuring the long-term health and usability of the repository. Adding guidelines for
maintaining a changelog and a backup strategy is a great idea.

Here are the additions I would suggest, along with other useful instructions to enhance the guideline file.

### Suggested Additions to the AI Agent Guideline

I recommend adding a new top-level section to your `.junie/guidelines.md` file called **"Project Maintenance, DevOps,
and Documentation."** This section will instruct Junie on its responsibilities beyond just writing the initial code.

Here is the new section you can add to your comprehensive guideline document:

-----

#### **9. Project Maintenance, DevOps, and Documentation**

Beyond the initial code generation, you are responsible for maintaining the project's health, documentation, and data
integrity. You must adhere to the following maintenance tasks as part of your ongoing work.

##### **9.1. Changelog Maintenance**

Maintaining a clear and accessible changelog is a mandatory requirement for this project. It is the primary way we
communicate changes to other developers and end-users. [1]

* **File and Format:** You must create and maintain a file named `CHANGELOG.md` in the root of the repository. This file
  must adhere to the **"Keep a Changelog"** format. [1]
* **Content:**
    * The changelog must have an "Unreleased" section at the top for ongoing changes. [1]
    * For every new feature, bug fix, or change you implement, you must add a corresponding entry to the "Unreleased"
      section under the appropriate category (`Added`, `Changed`, `Fixed`, `Removed`, `Security`, etc.). [1]
    * Your entries should be user-focused and describe the impact of the change, not just the technical details. Do not
      simply dump git logs. [2, 3]
* **Link to Commits:** This process is directly linked to the **Conventional Commits** standard. Every commit of type
  `feat`, `fix`, or any commit with a `BREAKING CHANGE` footer must have a corresponding entry in the changelog.

##### **9.2. Data Backup Strategy**

The data collected by this platform is invaluable scientific research data. Therefore, a robust backup strategy is
critical to prevent data loss.

* **Documentation:** You must create a document named `BACKUP_STRATEGY.md` in the root of the repository.
    * This document must outline the **3-2-1 Backup Rule** (3 copies of the data, on 2 different types of media, with 1
      copy off-site) as the recommended strategy for researchers using this platform. [4, 5, 6]
    * The document should provide a clear, easy-to-follow template that helps researchers plan and document their
      specific backup implementation (e.g., which cloud provider to use, where to store the off-site physical drive).
* **Automation Script:**
    * You must create a well-documented Python script named `backup_script.py` in a `/scripts` directory.
    * This script should automate the local part of the backup process. It should be configurable to copy all data from
      the main session data folder to a specified local backup destination (e.g., an external hard drive). [7, 8]
    * The script's documentation must include clear instructions on how to configure the source and destination paths
      and how to schedule the script to run automatically (e.g., using `cron` on Linux/macOS or Task Scheduler on
      Windows).

##### **9.3. Other Maintenance Tasks**

* **README Maintenance:** You must keep the root `README.md` file up-to-date. If you add a new feature, dependency, or
  change the setup process, you must update the README to reflect these changes. The README should always contain clear,
  accurate instructions for setting up and running the project.
* **Dependency Management:** When you add a new library or SDK, you must add it to the appropriate dependency file (
  `requirements.txt` for Python, `app/build.gradle.kts` for Android). Keep these files clean and free of unused
  dependencies.
* **`.gitignore` Maintenance:** You must ensure that temporary files, build artifacts, IDE-specific configuration
  files (e.g., `.idea/`), and sensitive files are included in the `.gitignore` file to keep the repository clean.

-----

### Why These Additions Are Useful

* **Changelog:** This forces the AI to document its own work in a human-readable format, making it much easier for you
  and your team to track progress and prepare for new releases.
* **Backup Strategy:** While the AI can't perform the physical backups, instructing it to create the documentation and
  automation scripts is a huge value-add. It builds best practices directly into the project, protecting the invaluable
  data that the platform is designed to collect.
* **Other Tasks:** These instructions cover common developer chores that are easy to forget. By delegating them to the
  AI, you ensure the project remains well-documented, clean, and easy for new developers (or even yourself, months
  later) to set up and use.