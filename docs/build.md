# Build

## Prerequisites

### Common

Install the following software in order (required on both platforms):

1. **[Zulu JDK 8](https://www.azul.com/downloads/?version=java-8-lts&package=jdk#zulu)**
   - On Linux you may also install via package manager: [Azul Zulu Doc](https://docs.azul.com/core/install/linux-ca-deb)
   - Any other OpenJDK 8 distribution works just as well

2. **[Apache Ant](https://ant.apache.org/bindownload.cgi)**
   - Windows: download the zip, extract, and add ANT_HOME's `PATH` to system variables
   - Linux: `sudo apt install ant`

3. **[proguard.jar](https://github.com/Guardsquare/proguard/releases)**
   - Download, find `lib/proguard.jar` inside the zip, and place it under the project's `lib/`

4. **[antenna-bin-1.2.1-beta.jar](https://sourceforge.net/projects/antenna/files/antenna/Antenna%201.2.1-beta/)**
   - Download and place it under the project's `lib/`

### Windows / Linux

5. Install **either one** of the following (`build.xml` auto-detects):

   - **[Java ME SDK 3.4](https://www.oracle.com/java/technologies/javame-sdk-downloads.html)** (Windows only)
     - oracle-jmesdk-3-4-rr-win32-bin.exe
     - Default install path: `C:\Java_ME_platform_SDK_3.4`

   - **[Sun Java Wireless Toolkit 2.5.2](https://www.oracle.com/java/technologies/java-archive-downloads-javame-downloads.html)** (Windows / Linux)
     - Windows: sun_java_wireless_toolkit-2.5.2_01-win.exe (default path: `C:\WTK2.5.2_01`)
     - Linux: sun_java_wireless_toolkit-2.5.2_01-linuxi486.bin.sh (default path: `~/WTK2.5.2`)
       ```bash
       chmod +x sun_java_wireless_toolkit-2.5.2_01-linuxi486.bin.sh
       ./sun_java_wireless_toolkit-2.5.2_01-linuxi486.bin.sh
       ```

   - If installed elsewhere, update `wtk.home` in the build script accordingly

### Linux Extra Steps

   - `preverify` is a 32-bit binary; on 64-bit systems, install the compatibility libraries:
     ```bash
     sudo dpkg --add-architecture i386
     sudo apt update
     sudo apt install libc6:i386 libstdc++6:i386 zlib1g:i386 \
                      libxt6:i386 libxext6:i386 libx11-6:i386
     ```

---

> NOTE 1: Downloading `Java ME SDK 3.4` and `Sun Java Wireless Toolkit 2.5.2` requires an Oracle account (quite a hassle, honestly... if you can obtain the dependencies from a trustworthy third-party source, it saves the trouble of going through the installer).

> NOTE 2: `Java ME SDK 3.0` may also work as a substitute for `3.4` — the only difference is the version of `preverify` used. However, you will need to update `wtk.home` in `build.xml` to point to the `3.0` installation path.

## Build Commands

| Command | Description |
|---------|-------------|
| `ant` | Full build |
| `ant clean` | Clean build artifacts |

The output JAR can be found in the `dist/` directory after a successful build.