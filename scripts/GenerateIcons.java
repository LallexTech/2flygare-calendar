import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Converts the supplied logo to launcher assets without changing the artwork. */
class GenerateIcons {
    public static void main(String[] args) throws Exception {
        var source = ImageIO.read(Path.of(args.length == 0 ? "logo2flygare.jpg" : args[0]).toFile());
        if (source == null) throw new IllegalArgumentException("Unsupported logo image");
        var output = Path.of("src/main/resources/static");
        Files.createDirectories(output.resolve("icons"));
        for (int size : new int[]{192, 512}) {
            ImageIO.write(resize(source, size, size), "png", output.resolve("icons/icon-" + size + ".png").toFile());
        }
        ImageIO.write(resize(source, 180, 180), "png", output.resolve("icons/apple-touch-icon.png").toFile());
        ImageIO.write(resize(source, 512, 384), "png", output.resolve("icons/icon-maskable-512.png").toFile());

        int[] sizes = {16, 32, 48};
        byte[][] images = new byte[sizes.length][];
        int length = 6 + sizes.length * 16;
        for (int i = 0; i < sizes.length; i++) {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(resize(source, sizes[i], sizes[i]), "png", bytes);
            images[i] = bytes.toByteArray();
            length += images[i].length;
        }
        var ico = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + sizes.length * 16;
        for (int i = 0; i < sizes.length; i++) {
            ico.put((byte) sizes[i]).put((byte) sizes[i]).put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 24).putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        for (var bytes : images) ico.put(bytes);
        Files.write(output.resolve("favicon.ico"), ico.array());
        System.out.println("Generated launcher icons, Apple touch icon, and favicon from "
                + source.getWidth() + "x" + source.getHeight() + " source logo.");
    }

    private static BufferedImage resize(BufferedImage source, int canvasSize, int logoSize) {
        var image = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(source.getRGB(0, 0)));
            graphics.fillRect(0, 0, canvasSize, canvasSize);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min((double) logoSize / source.getWidth(), (double) logoSize / source.getHeight());
            int width = (int) Math.round(source.getWidth() * scale);
            int height = (int) Math.round(source.getHeight() * scale);
            graphics.drawImage(source, (canvasSize - width) / 2, (canvasSize - height) / 2, width, height, null);
        } finally { graphics.dispose(); }
        return image;
    }
}
