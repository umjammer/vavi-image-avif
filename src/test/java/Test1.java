/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import vavi.awt.image.avif.jna.Avif;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Test1.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2022-09-07 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class Test1 {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "file")
    String file = "src/test/resources/kimono.avif";

    @BeforeEach
    void setup() throws IOException {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    @Test
    @DisplayName("prototype")
    void test0() throws Exception {
        InputStream is = Files.newInputStream(Paths.get(file));
        ByteBuffer bb = ByteBuffer.allocateDirect(is.available());
Debug.println("size: " + bb.capacity());
        int l = 0;
        while (l < bb.capacity()) {
            int r = Channels.newChannel(is).read(bb);
            if (r < 0) break;
            l += r;
        }

        Avif avif = Avif.getInstance();
        boolean r = Avif.isAvifImage(bb, bb.capacity());
Debug.println("image is avif: " + r);

        BufferedImage image = avif.getCompatibleImage(bb, bb.capacity());
Debug.printf("image: %dx%d%n", image.getWidth(), image.getHeight());
        avif.decode(bb, bb.capacity(), image);
    }

    @Test
    @DisplayName("prototype gui")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
        InputStream is = Files.newInputStream(Paths.get(file));
        ByteBuffer bb = ByteBuffer.allocateDirect(is.available());
Debug.println("size: " + bb.capacity());
        int l = 0;
        while (l < bb.capacity()) {
            int r = Channels.newChannel(is).read(bb);
            if (r < 0) break;
            l += r;
        }

        Avif avif = Avif.getInstance();
        boolean r = Avif.isAvifImage(bb, bb.capacity());
Debug.println("image is avif: " + r);

        BufferedImage image = avif.getCompatibleImage(bb, bb.capacity());
Debug.printf("image: %dx%d%n", image.getWidth(), image.getHeight());
        show(avif.decode(bb, bb.capacity(), image));
        while (true) Thread.yield();
    }

    /** gui */
    static void show(BufferedImage image) {
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
    @DisplayName("spi")
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
    @DisplayName("spi specified")
    void test01() throws Exception {
        ImageReader ir = ImageIO.getImageReadersByFormatName("avif").next();
        ImageInputStream iis = ImageIO.createImageInputStream(Files.newInputStream(Paths.get(file)));
        ir.setInput(iis);
        BufferedImage image = ir.read(0);
        assertNotNull(image);
    }

    @Test
    @DisplayName("spi auto")
    void test02() throws Exception {
        BufferedImage image = ImageIO.read(Files.newInputStream(Paths.get(file)));
        assertNotNull(image);
    }

    @Test
    @DisplayName("spi auto gui")
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test2() throws Exception {
        InputStream is = Files.newInputStream(Paths.get(file));
        show(ImageIO.read(is));
        while (true) Thread.yield();
    }
}