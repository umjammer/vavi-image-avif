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
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import vavi.awt.image.avif.jna.Avif;


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
        boolean r = avif.isAvifImage(bb, bb.capacity());
System.err.println("image is avif: " + r);

        BufferedImage image = avif.getCompatibleImage(bb, bb.capacity());
System.err.printf("image: %dx%d%n", image.getWidth(), image.getHeight());
        avif.decode(bb, bb.capacity(), image);
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
        String file = "/kimono.avif";
//        String file = "/data/io/cosmos1650_yuv444_10bpc_p3pq.avif";
//        String file = "/data/io/kodim03_yuv420_8bpc.avif";
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
        boolean r = avif.isAvifImage(bb, bb.capacity());
System.err.println("image is avif: " + r);

        BufferedImage image = avif.getCompatibleImage(bb, bb.capacity());
System.err.printf("image: %dx%d%n", image.getWidth(), image.getHeight());
        avif.decode(bb, bb.capacity(), image);

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

        while (true) Thread.yield();
    }
}
