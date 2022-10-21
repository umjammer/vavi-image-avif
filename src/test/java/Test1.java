/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import vavi.awt.image.avif.jna.Avif;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Test1.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2022-09-07 nsano initial version <br>
 */
class Test1 {

    @Test
    void test0() throws Exception {
        String file = "/kimono.avif";
        InputStream is = Test1.class.getResourceAsStream(file);
        ByteBuffer bb = ByteBuffer.allocateDirect(is.available());
System.err.println("size: " + bb.capacity());
        int l = 0;
        while (l < bb.capacity()) {
            int r = Channels.newChannel(is).read(bb);
            if (r < 0) break;
            l += r;
        }

        Avif avif = Avif.getInstance();
        boolean r = Avif.isAvifImage(bb, bb.capacity());
System.err.println("image is avif: " + r);

        BufferedImage image = avif.getCompatibleImage(bb, bb.capacity());
System.err.printf("image: %dx%d%n", image.getWidth(), image.getHeight());
        avif.decode(bb, bb.capacity(), image);
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
//        String file = "/kimono.avif";
//        String file = "/data/io/cosmos1650_yuv444_10bpc_p3pq.avif";
        String file = "/data/io/kodim03_yuv420_8bpc.avif";
//        String file = "/data/io/kodim23_yuv420_8bpc.avif";
        InputStream is = Test1.class.getResourceAsStream(file);
        ByteBuffer bb = ByteBuffer.allocateDirect(is.available());
System.err.println("size: " + bb.capacity());
        int l = 0;
        while (l < bb.capacity()) {
            int r = Channels.newChannel(is).read(bb);
            if (r < 0) break;
            l += r;
        }

        Avif avif = Avif.getInstance();
        boolean r = Avif.isAvifImage(bb, bb.capacity());
System.err.println("image is avif: " + r);

        BufferedImage image = avif.getCompatibleImage(bb, bb.capacity());
System.err.printf("image: %dx%d%n", image.getWidth(), image.getHeight());
        show(avif.decode(bb, bb.capacity(), image));
        while (true) Thread.yield();
    }

    static void show(BufferedImage image) {
        //
        JFrame frame = new JFrame();
        JPanel panel = new JPanel() {
            public void paint(Graphics g) {
                g.drawImage(image, 0, 0, this);
            }
        };
        panel.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
        frame.setContentPane(panel);
        frame.setTitle("AVIF");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);
    }

    @Test
    void test00() throws Exception {
        String[] rs = ImageIO.getReaderFormatNames();
System.err.println("-- reader --");
for (String r : rs) {
    System.err.println(r);
}
        assertTrue(Arrays.asList(rs).contains("AVIF"));
        String[] ws = ImageIO.getWriterFormatNames();
System.err.println("-- writer --");
for (String w : ws) {
    System.err.println(w);
}
        assertFalse(Arrays.asList(ws).contains("AVIF"));
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test2() throws Exception {
//        String file = "/kimono.avif";
        String file = "/data/io/kodim03_yuv420_8bpc.avif";
        InputStream is = Test1.class.getResourceAsStream(file);
        show(ImageIO.read(is));
        while (true) Thread.yield();
    }
}