[![Release](https://jitpack.io/v/umjammer/vavi-image-avif.svg)](https://jitpack.io/#umjammer/vavi-image-avif)
[![Java CI](https://github.com/umjammer/vavi-image-avif/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-image-avif/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-image-avif/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/umjammer/vavi-image-avif/actions/workflows/codeql-analysis.yml)
![Java](https://img.shields.io/badge/Java-8-b07219)
[![Parent](https://img.shields.io/badge/Parent-vavi--image--sandbox-pink)](https://github.com/umjammer/vavi-image-sandbox)

# vavi-image-avif

Java AVIF decoder<br/>
wrapped libavif by jna<br/>
based on https://github.com/AOMediaCodec/libavif/tree/main/android_jni

<img src="https://upload.wikimedia.org/wikipedia/commons/4/45/Avif-logo-rgb.svg" width="256"/>

## Install

 * install `libavif` e.g `brew libavif`
 * https://jitpack.io/#umjammer/vavi-image-avif
 * add `-Djna.library.path=/usr/local/lib` for jvm args

## Usage

```java
    BufferedImage image = ImageIO.read(Paths.get("/foo/baa.avif").toFile());
```