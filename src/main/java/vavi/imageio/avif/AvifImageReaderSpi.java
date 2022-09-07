/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.imageio.avif;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Locale;
import java.util.logging.Level;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import vavi.awt.image.avif.jna.Avif;
import vavi.imageio.WrappedImageInputStream;
import vavi.util.Debug;


/**
 * AvifImageReaderSpi.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2022-09-07 umjammer initial version <br>
 */
public class AvifImageReaderSpi extends ImageReaderSpi {

    private static final String VendorName = "https://github.com/umjammer/vavi-image-avif";
    private static final String Version = "0.0.1";
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
    static final String[] WriterSpiNames = {};
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
        return "AVIF Image";
    }

    @Override
    public boolean canDecodeInput(Object obj) throws IOException {
Debug.println(Level.FINE, "input: " + obj);
        if (obj instanceof ImageInputStream) {
            InputStream stream = new BufferedInputStream(new WrappedImageInputStream((ImageInputStream) obj));
            stream.mark(stream.available()); // TODO available is integer.max
            // we currently accept heif only
            ByteBuffer bb = ByteBuffer.allocateDirect(stream.available()); // TODO available is integer.max
            int l = 0;
            while (l < bb.capacity()) {
                int r = Channels.newChannel(stream).read(bb);
                if (r < 0) break;
                l += r;
            }
            stream.reset();
            Avif avif = Avif.getInstance();
            return avif.isAvifImage(bb, l);
        } else {
            return false;
        }
    }

    @Override
    public ImageReader createReaderInstance(Object obj) {
        return new AvifImageReader(this);
    }
}
