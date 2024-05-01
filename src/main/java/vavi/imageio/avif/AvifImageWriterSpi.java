/*
 * Copyright (c) 2023 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.imageio.avif;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;


/**
 * AvifImageWriterSpi.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023-05-28 nsano initial version <br>
 */
public class AvifImageWriterSpi extends ImageWriterSpi {

    static {
        try {
            try (InputStream is = AvifImageReaderSpi.class.getResourceAsStream("/META-INF/maven/vavi/vavi-image-avif/pom.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    Version = props.getProperty("version", "undefined in pom.properties");
                } else {
                    Version = System.getProperty("vavi.test.version", "undefined");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String VendorName = "https://github.com/umjammer/vavi-image-avif";
    private static final String Version;
    private static final String WriterClassName =
            "vavi.imageio.avif.AvifImageWriter";
    private static final String[] Names = {
            "avif", "AVIF"
    };
    private static final String[] Suffixes = {
            "avif"
    };
    private static final String[] mimeTypes = {
            "image/avif"
    };
    static final String[] ReaderSpiNames = { "vavi.imageio.avif.AvifImageReader" };
    private static final boolean SupportsStandardStreamMetadataFormat = false;
    private static final String NativeStreamMetadataFormatName = null;
    private static final String NativeStreamMetadataFormatClassName = null;
    private static final String[] ExtraStreamMetadataFormatNames = null;
    private static final String[] ExtraStreamMetadataFormatClassNames = null;
    private static final boolean SupportsStandardImageMetadataFormat = false;
    private static final String NativeImageMetadataFormatName = "avif";
    private static final String NativeImageMetadataFormatClassName = null;
    private static final String[] ExtraImageMetadataFormatNames = null;
    private static final String[] ExtraImageMetadataFormatClassNames = null;

    public AvifImageWriterSpi() {
        super(VendorName, Version,
                Names, Suffixes, mimeTypes, WriterClassName,
                new Class[] { ImageOutputStream.class },
                ReaderSpiNames,
                SupportsStandardStreamMetadataFormat,
                NativeStreamMetadataFormatName,
                NativeStreamMetadataFormatClassName,
                ExtraStreamMetadataFormatNames,
                ExtraStreamMetadataFormatClassNames,
                SupportsStandardImageMetadataFormat,
                NativeImageMetadataFormatName,
                NativeImageMetadataFormatClassName,
                ExtraImageMetadataFormatNames,
                ExtraImageMetadataFormatClassNames);
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return type.getBufferedImageType() == BufferedImage.TYPE_4BYTE_ABGR;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) throws IOException {
        return new AvifImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "AVIF Image encoder wrapped libavif";
    }
}
