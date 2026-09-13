<div align="center">

<img src="icon.webp" width="160" height="160" alt="Nehal's CloudStream Repository Mascot" />

# 🎬 Nehal's Server — CloudStream Repository

[![Build Plugins](https://github.com/nehalDIU/nehal-CloudStream/actions/workflows/build.yml/badge.svg)](https://github.com/nehalDIU/nehal-CloudStream/actions/workflows/build.yml)
[![CloudStream 3](https://img.shields.io/badge/CloudStream-3-blue.svg?style=flat-square&logo=android)](https://github.com/recloudstream/cloudstream)
[![Providers](https://img.shields.io/badge/Providers-19%20Active-brightgreen.svg?style=flat-square)](#-available-providers)
[![BDIX Gigabit](https://img.shields.io/badge/BDIX-Gigabit%20FTPs-orange.svg?style=flat-square)](#-bdix-ftp-providers)
[![License](https://img.shields.io/badge/License-Public%20Domain-lightgrey.svg?style=flat-square)](LICENSE)

**A collection of high-speed BDIX FTP servers, Bengali media hubs, Anime providers, Multi-Language OTT scrapers, and global streaming extensions for CloudStream.**

---

### 🚀 One-Click Install

<a href="cloudstreamrepo://raw.githubusercontent.com/nehalDIU/nehal-CloudStream/master/repo.json">
  <img src="https://img.shields.io/badge/CloudStream-Add%20Repository-6366f1?style=for-the-badge&logo=android&logoColor=white" alt="Add Repository to CloudStream" height="42" />
</a>

</div>

---

## ⚡ Quick Repository Links & Shortcodes

| Method | Value / Link |
|:---|:---|
| **Shortcode** (Android / Desktop) | `nehal` &bull; `bdix` &bull; `nehalbdix` |
| **Manifest URL** (Raw JSON) | `https://raw.githubusercontent.com/nehalDIU/nehal-CloudStream/master/repo.json` |
| **Plugin List** (Builds JSON) | `https://raw.githubusercontent.com/nehalDIU/nehal-CloudStream/builds/plugins.json` |
| **Deep Link Scheme** | `cloudstreamrepo://raw.githubusercontent.com/nehalDIU/nehal-CloudStream/master/repo.json` |

---

## 📥 How to Add to CloudStream

### 📱 CloudStream Android
1. Open **CloudStream** on your Android device or TV.
2. Go to **Settings** ⚙️ &rarr; **Extensions** &rarr; **Add Repository**.
3. In the **Repository URL** field, either:
   - Type the shortcode: **`nehal`** (or `bdix`), **OR**
   - Paste the full URL: `https://raw.githubusercontent.com/nehalDIU/nehal-CloudStream/master/repo.json`
4. Tap **Download / Add** & choose the plugins you wish to install!

### 💻 CloudStream Desktop (Windows / Linux / macOS)
1. Open **CloudStream Desktop**.
2. Navigate to the **Plugins / Extensions** tab.
3. Click **Add Repository** and enter `nehal` or paste `https://raw.githubusercontent.com/nehalDIU/nehal-CloudStream/master/repo.json`.
4. Click **Add & Refresh** to load all provider cards.

---

## 📦 Available Providers

| # | Provider | Types | Lang | Status | Description |
|:---:|:---|:---|:---:|:---:|:---|
| 1 | **CineplexBD** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | Fast Bangladeshi streaming server for movies and TV series |
| 2 | **DhakaFlix** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | Popular domestic streaming and BDIX media index |
| 3 | **DhakaFlix BDIX** | Movies, TV Series, Anime | 🇧🇩 `bn` | 🟢 Active | Multi-server domestic BDIX direct connection (`.12`, `.14`, `.7`) |
| 4 | **DiscoveryFTP** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | High-speed DiscoveryFTP domestic media server |
| 5 | **FTPBD** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | Gigabit domestic FTPBD catalog with multi-res video |
| 6 | **FmFtp** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | FM FTP media library |
| 7 | **ShowTimeBD** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | ShowTime BD Bangladeshi media portal |
| 8 | **BanglaPlex** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | Bengali and South Asian dubbed media library |
| 9 | **JellyfinBD** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | JellyfinBD domestic stream index |
| 10 | **CTGMovies** | Movies, TV Series, Anime, Asian Drama | 🇧🇩 `bn` | 🟢 Active | CTGMovies domestic streaming collection |
| 11 | **MovieLinkBD** | Movies, Series, Anime, Asian Drama | 🇧🇩 `bn` | 🟢 Active | MovieLinkBD streaming & direct download portal |
| 12 | **BdixCircleFTP** | Movies, Series, Anime, Cartoons, Docs | 🇧🇩 `bn` | 🟢 Active | CircleFTP high-speed direct stream (works in outage) |
| 13 | **BdixICCFtp** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | ICC Communication BDIX FTP server |
| 14 | **BdixCircleFtpOld** | Movies, TV Series | 🇧🇩 `bn` | 🟢 Active | Legacy CircleFTP endpoint index |
| 15 | **VegaMovies** | TV Series, Movies, Asian Drama, Anime | 🇮🇳 `hi` | 🟢 Active | VegaMovies, LuxMovies, and Rogmovies multi-source provider |
| 16 | **Netmirror** | Movies, TV Series | 🇮🇳 `hi` | 🟢 Active | Multi-language Netflix, Prime Video, & Disney+ Hotstar scraper |
| 17 | **MovieBox** | Movies, TV Series | 🇮🇳 `ta` / Multi | 🟢 Active | Multi-language regional and global entertainment provider |
| 18 | **Aniwatch** | Anime, Anime Movies | 🌐 `en` | 🟢 Active | Comprehensive subbed & dubbed anime streaming from aniwatch |
| 19 | **AllWish** | Anime, Movies, TV | 🌐 `en` | 🟢 Active | Fast anime & entertainment stream provider from all-wish.me |

---

## 🛠️ Building & Developing Plugins Locally

### Prerequisites
- JDK 17 (or newer)
- Android SDK (API 35+)

### Commands

- **Build all plugins & generate plugins.json:**
  ```powershell
  # Windows
  .\gradlew.bat make makePluginsJson

  # Linux / macOS
  ./gradlew make makePluginsJson
  ```

- **Build a single plugin (e.g., CineplexBD):**
  ```powershell
  .\gradlew.bat CineplexBD:make
  ```

- **Deploy directly to a connected Android device / Emulator via ADB:**
  ```powershell
  .\gradlew.bat CineplexBD:deployWithAdb
  ```

> [!NOTE]
> On Android 11+, CloudStream requires **"All Files Access"** granted to load local `.cs3` plugins via ADB:
> ```bash
> adb shell appops set --uid com.lagradost.cloudstream3 MANAGE_EXTERNAL_STORAGE allow
> ```

---

## 📄 License & Attribution

- Released under the **Public Domain** — feel free to use and distribute.
- Plugin loader and build system architecture based on CloudStream 3 & Aliucord.
- Maintained by **[Nehal](https://github.com/nehalDIU)**.
