# LSPatch Framework

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/LSPatch/main.yml?branch=master&logo=github&label=Build&event=push)](https://github.com/JingMatrix/LSPatch/actions/workflows/main.yml?query=event%3Apush+is%3Acompleted+branch%3Amaster) [![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lspatch_jingmatrix) [![Download](https://img.shields.io/github/v/release/JingMatrix/LSPatch?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/LSPatch/releases/latest) [![Total](https://shields.io/github/downloads/JingMatrix/LSPatch/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/LSPatch/releases)

## Introduction 

Rootless implementation of LSPosed framework, integrating Xposed API by inserting dex and so into the target APK.

## Supported Versions

- Min: Android 9
- Max: In theory, same with [LSPosed](https://github.com/JingMatrix/LSPosed#supported-versions)

## Download

For stable releases, please go to [Github Releases page](https://github.com/JingMatrix/LSPatch/releases)
For canary build, please check [Github Actions](https://github.com/JingMatrix/LSPatch/actions)
Note: debug builds are only available in Github Actions

## Usage

+ Through jar
1. Download `lspatch.jar`
1. Run `java -jar lspatch.jar`

+ Through manager
1. Download and install `manager.apk` on an Android device
1. Follow the instructions of the manager app

+ Adding a meta-data field with the com.google.android.geo.API_KEY parameter
To be able to add a _meta-data_ field to the _AndroidManifest.xml_ file with the com.google.android.geo.API_KEY parameter and its correct value, you must specify it in the _share/java/src/main/java/org/lsposed/lspatch/share/Constants.java_ file before compiling LSPatch.
After that, in the manager you can use the item "Set GeoAPIKey", and in lspatch.jar - the parameter "-g" or "--geoapi".
  


## Translation Contributing

You can contribute translation [here](https://crowdin.com/project/lspatch_jingmatrix).

## Credits

- [LSPosed](https://github.com/JingMatrix/LSPosed): Core framework
- [Xpatch](https://github.com/WindySha/Xpatch): Fork source
- [Apkzlib](https://android.googlesource.com/platform/tools/apkzlib): Repacking tool

## License

LSPatch is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
