/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.imageio.avif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.logging.Level;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import vavi.awt.image.avif.jna.Avif;
import vavi.util.Debug;


/**
 * AvifImageReaderSpi.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2022-09-07 umjammer initial version <br>
 */
public class AvifImageReaderSpi extends ImageReaderSpi {

    private static final String VendorName = "https://github.com/umjammer/vavi-image-avif";
    private static final String Version = "0.0.4";
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
Debug.println(Level.FINE, "input: " + obj);
        if (obj instanceof ImageInputStream) {
            ImageInputStream stream = (ImageInputStream) obj;
            stream.mark();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            while (true) {
                int r = stream.read(b, 0, b.length);
                if (r < 0) break;
                baos.write(b, 0, r);
            }
            int l = baos.size();
Debug.println(Level.FINE, "size: " + l);
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
