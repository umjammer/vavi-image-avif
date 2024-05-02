/*
 * Copyright (c) 2023 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.imageio.avif;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

import vavi.awt.image.avif.jna.Avif;
import vavi.util.Debug;


/**
 * AvifImageWriter.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023-05-28 nsano initial version <br>
 */
public class AvifImageWriter extends ImageWriter {

    /**
     * Constructs an <code>ImageWriter</code> and sets its
     * <code>originatingProvider</code> instance variable to the
     * supplied value.
     *
     * <p> Subclasses that make use of extensions should provide a
     * constructor with signature <code>(ImageWriterSpi,
     * Object)</code> in order to retrieve the extension object.  If
     * the extension object is unsuitable, an
     * <code>IllegalArgumentException</code> should be thrown.
     *
     * @param originatingProvider the <code>ImageWriterSpi</code> that
     *                            is constructing this object, or <code>null</code>.
     */
    protected AvifImageWriter(ImageWriterSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
long t = System.currentTimeMillis();
        try {
            Avif avif = Avif.getInstance();
            ByteBuffer bn = avif.encode((BufferedImage) image.getRenderedImage(), 60);
            ByteBuffer bb = ByteBuffer.allocate(bn.capacity());
            bb.put(bn);
            ImageOutputStream ios = (ImageOutputStream) output;
            ios.write(bb.array());
            ios.flush();
        } finally {
Debug.println(Level.FINE, "time: " + (System.currentTimeMillis() - t));
        }
    }
}
