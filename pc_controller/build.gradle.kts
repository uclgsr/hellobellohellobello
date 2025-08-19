plugins {
    base
}

val venvDir = project.layout.projectDirectory.dir(".venv")
val venvPython = venvDir.file("Scripts/python.exe")

// Create a Python virtual environment under pc_controller/.venv
val setupVenv by tasks.registering(Exec::class) {
    group = "python"
    description = "Create a Python virtual environment for pc_controller"
    commandLine("cmd", "/c", "python -m venv .venv")
    workingDir = project.projectDir
    // Only create if missing
    onlyIf { !venvDir.asFile.exists() }
}

// Install Python dependencies into the venv, including pytest and pyinstaller
val installRequirements by tasks.registering(Exec::class) {
    group = "python"
    description = "Install Python dependencies into the venv (requirements + pytest + pyinstaller)"
    dependsOn(setupVenv)
    doFirst {
        val pythonExe = if (venvPython.asFile.exists()) venvPython.asFile.absolutePath else "python"
        val cmd = "\"$pythonExe\" -m pip install -U pip && \"$pythonExe\" -m pip install -r requirements.txt && \"$pythonExe\" -m pip install pytest pyinstaller"
        commandLine("cmd", "/c", cmd)
    }
    workingDir = project.projectDir
}

// Run pytest using the system Python (relies on environment pytest)
val pyTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run pytest for pc_controller"
    // Do not depend on venv to avoid heavy installs in CI; pytest is executed from repo root
    commandLine("cmd", "/c", "pytest")
    workingDir = project.rootDir // pytest.ini is at repo root
}

// Build a standalone executable using PyInstaller
val pyInstaller by tasks.registering(Exec::class) {
    group = "build"
    description = "Build pc_controller executable using PyInstaller"
    dependsOn(installRequirements)
    doFirst {
        val pythonExe = if (venvPython.asFile.exists()) venvPython.asFile.absolutePath else "python"
        val entry = File(project.projectDir, "src/main.py").absolutePath
        val distDir = project.layout.buildDirectory.get().asFile.resolve("dist").absolutePath
        val cmd = "\"$pythonExe\" -m PyInstaller --noconfirm --clean -F --name pc_controller --distpath \"$distDir\" \"$entry\""
        commandLine("cmd", "/c", cmd)
    }
    workingDir = project.projectDir
}

// Make root :build depend on this artifact if desired
tasks.register("assemblePcController") {
    group = "build"
    description = "Assemble pc_controller executable"
    dependsOn(pyInstaller)
}
