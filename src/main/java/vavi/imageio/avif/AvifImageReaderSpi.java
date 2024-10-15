/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.imageio.avif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Properties;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import vavi.awt.image.avif.jna.Avif;

import static java.lang.System.getLogger;


/**
 * AvifImageReaderSpi.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2022-09-07 umjammer initial version <br>
 */
public class AvifImageReaderSpi extends ImageReaderSpi {

    private static final Logger logger = getLogger(AvifImageReaderSpi.class.getName());

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
    private static final String ReaderClassName =
        "vavi.imageio.avif.AvifImageReader";
    private static final String[] Names = {
        "avif", "AVIF"
    };
    private static final String[] Suffixes = {
        "avif"
    };
    private static final String[] mimeTypes = {
        "image/avif"
    };
    static final String[] WriterSpiNames = { "vavi.imageio.avif.AvifImageWriter" };
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

    /** */
    public AvifImageReaderSpi() {
        super(VendorName,
              Version,
              Names,
              Suffixes,
              mimeTypes,
              ReaderClassName,
              new Class[] { ImageInputStream.class },
              WriterSpiNames,
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
    public String getDescription(Locale locale) {
        return "AVIF Image decoder via libavif";
    }

    @Override
    public boolean canDecodeInput(Object obj) throws IOException {
logger.log(Level.DEBUG,"input: " + obj);
        if (obj instanceof ImageInputStream stream) {
            stream.mark();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            while (true) {
                int r = stream.read(b, 0, b.length);
                if (r < 0) break;
                baos.write(b, 0, r);
            }
            int l = baos.size();
logger.log(Level.DEBUG,"size: " + l);
            ByteBuffer bb = ByteBuffer.allocateDirect(l);
            bb.put(baos.toByteArray(), 0, l);
            stream.reset();
            return Avif.isAvifImage(bb, l);
        } else {
            return false;
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new AvifImageReader(this);
    }
}
