package com.cs3219.foc.supplier.service;

import com.cs3219.foc.supplier.exception.SupplierImageException;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * check uploaded images and re-encodes to JPEG that no larger than {@value #OUTPUT_DIMENSION}px on its longest side.
 * Re-encoding will drop metadata and also anything append to the original file.
 */
@Component
public class SupplierImageProcessor {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_DIMENSION = 6000;
    private static final int OUTPUT_DIMENSION = 1200;

    public byte[] process(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SupplierImageException(HttpStatus.BAD_REQUEST, "Image file is required");
        }
        try (var stream = file.getInputStream()) {
            var bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new SupplierImageException(HttpStatus.PAYLOAD_TOO_LARGE, "Image cannot exceed 5 MB");
            }
            return reencode(bytes);
        } catch (IOException | IllegalArgumentException exception) {
            throw new SupplierImageException(HttpStatus.BAD_REQUEST, "Image cannot be decoded", exception);
        }
    }

    // drop metadata append to the original image
    private static byte[] reencode(byte[] bytes) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupported();
            }

            var reader = readers.next();
            try {
                var format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) {
                    throw unsupported();
                }

                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new SupplierImageException(
                            HttpStatus.BAD_REQUEST, "Image dimensions cannot exceed 6000 by 6000 pixels");
                }

                var decoded = reader.read(0);
                double scale = Math.min(1.0, (double) OUTPUT_DIMENSION / Math.max(width, height));
                var resized = new BufferedImage(
                        Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)),
                        BufferedImage.TYPE_INT_RGB);

                var graphics = resized.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, resized.getWidth(), resized.getHeight());
                    graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, resized.getWidth(), resized.getHeight(), null);
                } finally {
                    graphics.dispose();
                    decoded.flush();
                }

                try (var output = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(resized, "jpg", output)) {
                        throw new SupplierImageException(
                                HttpStatus.SERVICE_UNAVAILABLE, "Image processing is unavailable");
                    }
                    return output.toByteArray();
                } finally {
                    resized.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static SupplierImageException unsupported() {
        return new SupplierImageException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only allow JPEG and PNG images");
    }
}
