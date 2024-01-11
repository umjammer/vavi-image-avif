[![Release](https://jitpack.io/v/umjammer/vavi-image-avif.svg)](https://jitpack.io/#umjammer/vavi-image-avif)
[![Java CI](https://github.com/umjammer/vavi-image-avif/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-image-avif/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-image-avif/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/umjammer/vavi-image-avif/actions/workflows/codeql-analysis.yml)
![Java](https://img.shields.io/badge/Java-17-b07219)
[![Parent](https://img.shields.io/badge/Parent-vavi--image--sandbox-pink)](https://github.com/umjammer/vavi-image-sandbox)

# vavi-image-avif

Java AVIF decoder and encoder<br/>
wrapped [libavif](https://github.com/AOMediaCodec/libavif) by jna<br/>
based on https://github.com/AOMediaCodec/libavif/tree/main/android_jni

<img src="https://upload.wikimedia.org/wikipedia/commons/4/45/Avif-logo-rgb.svg" width="256"/>
<sub>© <a href="https://aomedia.org/av1/">AOM</a></sub>

## Install

 * install `libavif` 1.0.3 ... e.g. `brew intall libavif`
 * https://jitpack.io/#umjammer/vavi-image-avif
 * add `-Djna.library.path=/opt/homebrew/lib` for jvm args

## Usage

```java
    // read
    BufferedImage image = ImageIO.read(Paths.get("/foo/bar.avif").toFile());
    // write
    ImageIO.write(image, "AVIF", Paths.get("/foo/baz.avif").toFile());
```

## TODO

 * ~~writer~~
