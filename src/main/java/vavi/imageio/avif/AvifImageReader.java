/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.imageio.avif;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import vavi.awt.image.avif.jna.Avif;
import vavi.imageio.WrappedImageInputStream;
import vavi.util.Debug;


/**
 * AvifImageReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2022-09-07 umjammer initial version <br>
 */
public class AvifImageReader extends ImageReader {

    /** */
    private BufferedImage image;

    /** */
    public AvifImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IIOException {
        return 1;
    }

    /** */
    private void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("bad index");
        }
    }

    @Override
    public int getWidth(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        return image.getWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        return image.getHeight();
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param)
        throws IIOException {

Debug.println(Level.FINE, "decode start");
long t = System.currentTimeMillis();
        InputStream stream = new WrappedImageInputStream((ImageInputStream) input);

        try {
            ByteBuffer bb = ByteBuffer.allocateDirect(Integer.MAX_VALUE);
            int l = 0;
            while (l < bb.capacity()) {
                int r = Channels.newChannel(stream).read(bb);
                if (r < 0) break;
                l += r;
            }
Debug.println(Level.FINE, "size: " + l);

            Avif avif = Avif.getInstance();

            image = avif.getCompatibleImage(bb, l);
            return avif.decode(bb, l, image);
        } catch (IOException e) {
            throw new IIOException(e.getMessage(), e);
} finally {
Debug.println(Level.FINE, "time: " + (System.currentTimeMillis() - t));
        }
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IIOException {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        return null;
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        ImageTypeSpecifier specifier = null;
        List<ImageTypeSpecifier> l = new ArrayList<>();
        l.add(specifier);
        return l.iterator();
    }
}

/* */
