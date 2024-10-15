/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.imageio.avif;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import vavi.awt.image.avif.jna.Avif;
import vavi.imageio.WrappedImageInputStream;

import static java.lang.System.getLogger;


/**
 * AvifImageReader.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2022-09-07 umjammer initial version <br>
 */
public class AvifImageReader extends ImageReader {

    private static final Logger logger = getLogger(AvifImageReader.class.getName());

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
    private static void checkIndex(int imageIndex) {
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

logger.log(Level.DEBUG,"decode start");
long t = System.currentTimeMillis();
        InputStream stream = new WrappedImageInputStream((ImageInputStream) input);

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            while (true) {
                int r = stream.read(b, 0, b.length);
                if (r < 0) break;
                baos.write(b, 0, r);
            }
            int l = baos.size();
logger.log(Level.DEBUG,"size: {0} bytes", l);
            ByteBuffer bb = ByteBuffer.allocateDirect(l);
            bb.put(baos.toByteArray(), 0, l);

            Avif avif = Avif.getInstance();

            image = avif.getCompatibleImage(bb, l);
            return avif.decode(bb, l, image);
        } catch (IOException e) {
            throw new IIOException(e.getMessage(), e);
} finally {
logger.log(Level.DEBUG,"time: {0} ms", System.currentTimeMillis() - t);
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
